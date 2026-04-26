package com.maaframework.android.root

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.util.Log
import android.view.Surface
import com.maaframework.android.bridge.DriverClass
import com.maaframework.android.bridge.InputControlUtils
import com.maaframework.android.bridge.NativeBridgeLib
import com.maaframework.android.catalog.InterfaceCatalogLoader
import com.maaframework.android.ipc.IRootRuntimeService
import com.maaframework.android.maa.MaaFrameworkBridge
import com.maaframework.android.model.FailureArtifact
import com.maaframework.android.model.ResourceDescriptor
import com.maaframework.android.model.RunRequest
import com.maaframework.android.model.RunSessionPhase
import com.maaframework.android.model.RuntimeStateSnapshot
import com.maaframework.android.preview.ActivityUtils
import com.maaframework.android.preview.DefaultDisplayConfig
import com.maaframework.android.preview.VirtualDisplayManager
import com.maaframework.android.project.MaaProjectManifest
import com.maaframework.android.project.MaaProjectManifestLoader
import com.maaframework.android.runtime.RuntimeBootstrapper
import com.maaframework.android.runtime.RuntimeLogger
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RootRuntimeService(
    private val context: Context,
) : IRootRuntimeService.Stub() {
    private val projectManifest = MaaProjectManifestLoader.loadOrDefault(context.assets)
    private val catalogLoader = InterfaceCatalogLoader(context.assets, projectManifest.supportedControllers)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val stateLock = Any()
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var runtimeRoot: File? = null

    @Volatile
    private var logger: RuntimeLogger? = null

    @Volatile
    private var currentFuture: Future<*>? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var maaBridge: MaaFrameworkBridge? = null

    @Volatile
    private var snapshot = RuntimeStateSnapshot(
        lastMessage = "Root runtime bootstrapped",
    )

    init {
        runCatching {
            InputControlUtils.initialize(context)
            DriverClass.installContext(context)
            DisplayPowerController.recoverIfNeeded(::log)
        }.onFailure { error ->
            Log.e(TAG, "Failed to initialize input controller", error)
        }
    }

    override fun ping(): String {
        return buildString {
            append("MaaFramework Android Root Runtime")
            append(" | prepared=")
            append(snapshot.runtimePrepared)
            append(" | root=")
            append(runtimeRootDirectory().absolutePath)
        }
    }

    override fun prepareRuntime(): Boolean {
        updateSnapshot { it.copy(phase = RunSessionPhase.Preparing, lastMessage = "Preparing runtime") }
        return runCatching {
            val root = runtimeRootDirectory()
            val runtimeLogger = RuntimeLogger(root)
            logger = runtimeLogger
            val result = RuntimeBootstrapper.prepare(context, runtimeLogger, root)
            runtimeRoot = result.runtimeRoot
            updateSnapshot {
                it.copy(
                    phase = RunSessionPhase.Idle,
                    runtimePrepared = true,
                    runtimeRoot = result.runtimeRoot.absolutePath,
                    displayPowerOffActive = DisplayPowerController.isDisplayPowerOffActive(),
                    capabilities = result.capabilities,
                    lastMessage = result.message,
                    recentLogs = runtimeLogger.tail(),
                )
            }
            true
        }.getOrElse { error ->
            failRun(taskId = null, message = "Runtime prepare failed: ${error.message}")
            false
        }
    }

    override fun startRun(runRequestJson: String): Boolean {
        val request = runCatching { json.decodeFromString<RunRequest>(runRequestJson) }
            .getOrElse { error ->
                failRun(taskId = null, message = "Invalid run request: ${error.message}")
                return false
            }

        if (snapshot.phase == RunSessionPhase.Running || snapshot.phase == RunSessionPhase.Preparing) {
            return false
        }

        if (!snapshot.runtimePrepared && !prepareRuntime()) {
            failRun(taskId = request.taskId, message = "Runtime prepare failed before run")
            return false
        }

        if (!snapshot.capabilities.hasRunnableAgent || !snapshot.capabilities.hasBundledMaaFramework) {
            failRun(
                taskId = request.taskId,
                message = buildMissingRuntimeMessage(),
            )
            return false
        }

        val taskIds = request.sequenceTaskIds.ifEmpty {
            request.taskId?.let(::listOf) ?: emptyList()
        }
        if (taskIds.isEmpty()) {
            failRun(taskId = null, message = "No task to run")
            return false
        }

        val resource = resolveResourceDescriptor(request.resourceName)
        if (resource == null) {
            failRun(taskId = request.taskId ?: taskIds.firstOrNull(), message = "Resource not found for ${request.resourceName}")
            return false
        }

        stopRequested = false
        currentFuture = executor.submit {
            val runLabel = request.taskId ?: request.presetId ?: "sequence"
            val priorityState = elevateTaskExecutionThreadPriority(runLabel)
            try {
                log("Run started: ${request.taskId ?: request.presetId ?: taskIds.joinToString(",")}")
                if (!prepareVirtualDisplayForRun(resource)) {
                    failRun(
                        taskIds.firstOrNull(),
                        "Failed to prepare 16:9 virtual display for resource ${resource.id}",
                    )
                    return@submit
                }
                for (taskId in taskIds) {
                    if (stopRequested) {
                        completeRun(taskId, "Run stopped by user", RunSessionPhase.Completed)
                        return@submit
                    }
                    if (!runSingleTask(taskId, request.optionOverridesByTask[taskId], resource, request.logLevel)) {
                        return@submit
                    }
                }
                completeRun(taskIds.last(), "Run completed", RunSessionPhase.Completed)
            } finally {
                restoreTaskExecutionThreadPriority(runLabel, priorityState)
            }
        }
        return true
    }

    override fun stopRun() {
        stopRequested = true
        maaBridge?.stop()
        ensureDisplayPowerOn("Run stopping, restoring screen power")
        updateSnapshot { it.copy(phase = RunSessionPhase.Stopping, lastMessage = "Stop requested") }
        currentFuture?.cancel(true)
        updateSnapshot { it.copy(phase = RunSessionPhase.Idle, currentTaskId = null, lastMessage = "Run stopped") }
    }

    override fun setMonitorSurface(surface: Surface?) {
        VirtualDisplayManager.setMonitorSurface(surface)
    }

    override fun startWindowedGame(resourceId: String?): Boolean {
        val launched = launchResourceOnVirtualDisplay(
            resourceId = resourceId,
            requirePackage = true,
            forceStop = true,
            excludeFromRecents = false,
        )
        if (launched) {
            log("Windowed game launched for resource=$resourceId displayId=${VirtualDisplayManager.getDisplayId()}")
        }
        return launched
    }

    override fun touchDown(contactId: Int, x: Int, y: Int): Boolean {
        return dispatchWindowTouch(contactId, x, y) { id, tx, ty, displayId ->
            DriverClass.touchDown(id, tx, ty, displayId)
        }
    }

    override fun touchMove(contactId: Int, x: Int, y: Int): Boolean {
        return dispatchWindowTouch(contactId, x, y) { id, tx, ty, displayId ->
            DriverClass.touchMove(id, tx, ty, displayId)
        }
    }

    override fun touchUp(contactId: Int, x: Int, y: Int): Boolean {
        return dispatchWindowTouch(contactId, x, y) { id, tx, ty, displayId ->
            DriverClass.touchUp(id, tx, ty, displayId)
        }
    }

    override fun getWindowedDisplayId(): Int = VirtualDisplayManager.getDisplayId()

    override fun stopWindowedPreview() {
        VirtualDisplayManager.stop()
    }

    override fun setDisplayPower(on: Boolean): Boolean {
        val success = DisplayPowerController.setDisplayPower(on)
        if (success) {
            val message = if (on) "Screen power restored" else "Screen turned off for background run"
            log(message)
            updateSnapshot {
                it.copy(
                    displayPowerOffActive = DisplayPowerController.isDisplayPowerOffActive(),
                    lastMessage = message,
                    recentLogs = logger?.tail() ?: emptyList(),
                )
            }
        } else {
            log("Failed to change screen power: on=$on")
        }
        return success
    }

    override fun getState(): String {
        return json.encodeToString(snapshot.copy(recentLogs = logger?.tail() ?: emptyList()))
    }

    override fun readLogChunk(offsetBytes: Long, maxBytes: Int): String {
        return json.encodeToString(logger?.readChunk(offsetBytes, maxBytes) ?: com.maaframework.android.model.RuntimeLogChunk())
    }

    override fun exportDiagnostics(): String {
        val root = runtimeRoot ?: runtimeRootDirectory()
        val diagnosticsDir = File(root, "diagnostics").apply { mkdirs() }
        val output = File(diagnosticsDir, "maaframework-android-diagnostics-${System.currentTimeMillis()}.zip")
        val logFile = logger?.file()
        val stateJson = json.encodeToString(snapshot.copy(recentLogs = logger?.tail() ?: emptyList()))
        val screenshot = snapshot.lastFailure?.screenshotPath?.let(::File)

        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("state.json"))
            zip.write(stateJson.toByteArray())
            zip.closeEntry()

            if (logFile != null && logFile.exists()) {
                zip.putNextEntry(ZipEntry("logs/root-runtime.log"))
                logFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

            if (screenshot != null && screenshot.exists()) {
                zip.putNextEntry(ZipEntry("artifacts/${screenshot.name}"))
                screenshot.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        updateSnapshot {
            it.copy(
                lastDiagnosticsPath = output.absolutePath,
                recentLogs = logger?.tail() ?: emptyList(),
            )
        }
        return output.absolutePath
    }

    override fun destroy() {
        stopRequested = true
        DisplayPowerController.destroy(::log)
        VirtualDisplayManager.stop()
        maaBridge?.destroy()
        maaBridge = null
        currentFuture?.cancel(true)
        executor.shutdownNow()
        exitProcess(0)
    }

    private fun runSingleTask(
        taskId: String,
        optionOverrideJson: String?,
        resource: ResourceDescriptor,
        logLevel: String,
    ): Boolean {
        updateSnapshot {
            it.copy(
                phase = RunSessionPhase.Running,
                currentTaskId = taskId,
                lastMessage = "Running $taskId",
            )
        }
        log("Running task: $taskId")

        val entry = resolveTaskEntry(taskId)
        if (entry.isNullOrBlank()) {
            failRun(taskId, "Task entry not found for $taskId")
            return false
        }

        return runCatching {
            val bridge = MaaFrameworkBridge().also {
                it.init(
                    context,
                    runtimeRoot ?: runtimeRootDirectory(),
                    resource.id,
                    resource.label,
                    resource.paths,
                    projectManifest.attachResourcePaths,
                    logLevel,
                )
            }
            maaBridge?.destroy()
            maaBridge = bridge
            log("Loaded MaaFramework ${bridge.version()}")

            val overrideJson = mergeOverrideJson("{}", optionOverrideJson)
            val result = bridge.runTask(entry, overrideJson)
            log("Task result: success=${result.success}, message=${result.message}")
            check(result.success) { result.message.ifBlank { "Task execution failed" } }
            true
        }.getOrElse { error ->
            failRun(taskId, error.message ?: "Task execution failed")
            false
        }
    }

    private fun resolveTaskEntry(taskId: String): String? {
        return loadCatalogSnapshot().tasks
            .firstOrNull { it.id == taskId }
            ?.entry
            ?.takeIf { it.isNotBlank() }
    }

    private fun resolveResourceDescriptor(resourceId: String): ResourceDescriptor? {
        val catalog = loadCatalogSnapshot()
        return catalog.resources.firstOrNull { it.id == resourceId }
            ?: catalog.resources.firstOrNull { it.label == resourceId }
            ?: catalog.resources.firstOrNull()
    }

    private fun prepareVirtualDisplayForRun(resource: ResourceDescriptor): Boolean {
        val ready = launchResourceOnVirtualDisplay(
            resourceId = resource.id,
            requirePackage = false,
            forceStop = true,
            excludeFromRecents = true,
        )
        if (ready) {
            log(
                "Run display prepared: resource=${resource.id} displayId=${VirtualDisplayManager.getDisplayId()} " +
                    "size=${DefaultDisplayConfig.WIDTH}x${DefaultDisplayConfig.HEIGHT}",
            )
        }
        return ready
    }

    private fun launchResourceOnVirtualDisplay(
        resourceId: String?,
        requirePackage: Boolean,
        forceStop: Boolean,
        excludeFromRecents: Boolean,
    ): Boolean {
        val displayId = VirtualDisplayManager.start(context)
        if (displayId == DefaultDisplayConfig.DISPLAY_NONE) {
            log("Failed to start virtual display for resource=$resourceId")
            return false
        }

        log(
            "Virtual display ready: resource=$resourceId displayId=$displayId " +
                "size=${DefaultDisplayConfig.WIDTH}x${DefaultDisplayConfig.HEIGHT}",
        )

        val packageName = projectManifest.packageNameFor(resourceId)
        if (packageName.isNullOrBlank()) {
            log("No package configured for resource=$resourceId")
            return !requirePackage
        }

        val launched = ActivityUtils.startApp(
            context = context,
            packageName = packageName,
            displayId = displayId,
            forceStop = forceStop,
            excludeFromRecents = excludeFromRecents,
        )
        if (!launched) {
            log("Failed to launch package=$packageName on virtual display $displayId for resource=$resourceId")
            return false
        }

        log("Virtual display app launched: package=$packageName displayId=$displayId resource=$resourceId")
        return true
    }

    private fun loadCatalogSnapshot() = if ((runtimeRoot ?: runtimeRootDirectory()).resolve("interface.json").exists()) {
        catalogLoader.loadFromDirectory(runtimeRoot ?: runtimeRootDirectory())
    } else {
        catalogLoader.load()
    }

    private fun completeRun(taskId: String?, message: String, phase: RunSessionPhase) {
        ensureDisplayPowerOn("Run completed, restoring screen power")
        log(message)
        updateSnapshot {
            it.copy(
                phase = phase,
                currentTaskId = taskId,
                lastMessage = message,
                recentLogs = logger?.tail() ?: emptyList(),
            )
        }
    }

    private fun failRun(taskId: String?, message: String) {
        ensureDisplayPowerOn("Run failed, restoring screen power")
        log(message)
        val screenshotPath = captureFailureScreenshot(taskId)
        updateSnapshot {
            it.copy(
                phase = RunSessionPhase.Failed,
                currentTaskId = taskId,
                lastMessage = message,
                displayPowerOffActive = DisplayPowerController.isDisplayPowerOffActive(),
                lastFailure = FailureArtifact(
                    taskId = taskId,
                    screenshotPath = screenshotPath,
                    occurredAt = System.currentTimeMillis(),
                ),
                recentLogs = logger?.tail() ?: emptyList(),
            )
        }
    }

    private fun buildMissingRuntimeMessage(): String {
        val missing = mutableListOf<String>()
        if (!snapshot.capabilities.hasBundledMaaFramework) {
            missing += "runtime/maafw"
        }
        if (!snapshot.capabilities.hasRunnableAgent) {
            val preferredAgent = snapshot.capabilities.agentRuntimes
                .firstOrNull { it.source == "project-interface" }
                ?: snapshot.capabilities.agentRuntimes.firstOrNull()
            val detail = preferredAgent?.detail?.takeIf { it.isNotBlank() && it != "ok" }
            missing += detail ?: "Android agent runtime"
        }
        return "Bundled Maa runtime missing: ${missing.joinToString("; ")}"
    }

    private fun captureFailureScreenshot(taskId: String?): String? {
        val root = runtimeRoot ?: return null
        val screenshotsDir = File(root, "diagnostics").apply { mkdirs() }
        val screenshot = File(screenshotsDir, "failure-${taskId ?: "unknown"}-${System.currentTimeMillis()}.png")
        val previewCaptured = runCatching {
            val bitmap = NativeBridgeLib.capturePreviewFrame() ?: return@runCatching false
            try {
                saveBitmap(bitmap, screenshot)
            } finally {
                bitmap.recycle()
            }
        }.getOrDefault(false)
        return if (previewCaptured || runShellCommand("/system/bin/screencap -p ${shellQuote(screenshot.absolutePath)}")) {
            screenshot.absolutePath
        } else {
            null
        }
    }

    private fun saveBitmap(bitmap: Bitmap, destination: File): Boolean {
        return runCatching {
            destination.outputStream().buffered().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }.getOrDefault(false)
    }

    private fun runShellCommand(command: String): Boolean {
        return runCatching {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            if (output.isNotBlank()) {
                log(output.trim())
            }
            code == 0
        }.getOrElse { error ->
            log("Shell command failed: ${error.message}")
            false
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun ensureDisplayPowerOn(reason: String) {
        if (!DisplayPowerController.isDisplayPowerOffActive()) {
            return
        }
        runCatching { DisplayPowerController.setDisplayPower(true) }
            .onSuccess { log(reason) }
            .onFailure { error ->
                log("Failed to restore screen power: ${error.message}")
            }
    }

    private fun updateSnapshot(transform: (RuntimeStateSnapshot) -> RuntimeStateSnapshot) {
        synchronized(stateLock) {
            snapshot = transform(snapshot).copy(
                displayPowerOffActive = DisplayPowerController.isDisplayPowerOffActive(),
            )
        }
    }

    private fun log(message: String) {
        logger?.log(message)
    }

    private fun elevateTaskExecutionThreadPriority(runLabel: String): TaskExecutionThreadPriorityState {
        val tid = Process.myTid()
        val before = readThreadPriority(tid)
        val after = runCatching {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            readThreadPriority(tid)
        }.getOrElse { error ->
            log(
                "Task execution thread priority raise failed: run=$runLabel tid=$tid " +
                    "before=$before target=${Process.THREAD_PRIORITY_DISPLAY} error=${error.message}",
            )
            readThreadPriority(tid)
        }
        log(
            "Task execution thread priority raised: run=$runLabel tid=$tid " +
                "before=$before after=$after target=${Process.THREAD_PRIORITY_DISPLAY}",
        )
        return TaskExecutionThreadPriorityState(
            tid = tid,
            originalPriority = before,
        )
    }

    private fun restoreTaskExecutionThreadPriority(
        runLabel: String,
        state: TaskExecutionThreadPriorityState,
    ) {
        val beforeRestore = readThreadPriority(state.tid)
        val afterRestore = runCatching {
            Process.setThreadPriority(state.originalPriority)
            readThreadPriority(state.tid)
        }.getOrElse { error ->
            log(
                "Task execution thread priority restore failed: run=$runLabel tid=${state.tid} " +
                    "beforeRestore=$beforeRestore target=${state.originalPriority} error=${error.message}",
            )
            readThreadPriority(state.tid)
        }
        log(
            "Task execution thread priority restored: run=$runLabel tid=${state.tid} " +
                "beforeRestore=$beforeRestore afterRestore=$afterRestore target=${state.originalPriority}",
        )
    }

    private fun readThreadPriority(tid: Int): Int {
        return runCatching { Process.getThreadPriority(tid) }
            .getOrElse { error ->
                log(
                    "Task execution thread priority read failed: tid=$tid " +
                        "default=${Process.THREAD_PRIORITY_DEFAULT} error=${error.message}",
                )
                Process.THREAD_PRIORITY_DEFAULT
            }
    }

    private inline fun dispatchWindowTouch(
        contactId: Int,
        x: Int,
        y: Int,
        block: (contactId: Int, x: Int, y: Int, displayId: Int) -> Boolean,
    ): Boolean {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId == DefaultDisplayConfig.DISPLAY_NONE) {
            return false
        }
        val tx = x.coerceIn(0, DefaultDisplayConfig.WIDTH - 1)
        val ty = y.coerceIn(0, DefaultDisplayConfig.HEIGHT - 1)
        return block(contactId, tx, ty, displayId)
    }

    private fun runtimeRootDirectory(): File {
        return RuntimeBootstrapper.defaultRuntimeRoot(context)
    }

    private fun mergeOverrideJson(baseJson: String, extraJson: String?): String {
        if (extraJson.isNullOrBlank() || extraJson == "{}") {
            return baseJson
        }
        if (baseJson.isBlank() || baseJson == "{}") {
            return extraJson
        }

        val base = runCatching { json.decodeFromString<JsonObject>(baseJson) }.getOrDefault(JsonObject(emptyMap()))
        val extra = runCatching { json.decodeFromString<JsonObject>(extraJson) }.getOrDefault(JsonObject(emptyMap()))
        return mergeJsonObjects(base, extra).toString()
    }

    private fun mergeJsonObjects(base: JsonObject, overlay: JsonObject): JsonObject {
        return buildJsonObject {
            val keys = base.keys + overlay.keys
            keys.forEach { key ->
                val baseValue = base[key]
                val overlayValue = overlay[key]
                when {
                    baseValue is JsonObject && overlayValue is JsonObject -> put(key, mergeJsonObjects(baseValue, overlayValue))
                    overlayValue != null -> put(key, overlayValue)
                    baseValue != null -> put(key, baseValue)
                }
            }
        }
    }

    private companion object {
        const val TAG = "RootRuntimeService"
    }

    private data class TaskExecutionThreadPriorityState(
        val tid: Int,
        val originalPriority: Int,
    )
}
