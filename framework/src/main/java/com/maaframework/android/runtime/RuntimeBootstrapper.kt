package com.maaframework.android.runtime

import android.content.Context
import android.content.res.AssetManager
import com.maaframework.android.catalog.JsonWithComments
import com.maaframework.android.model.RuntimeAgentCapability
import com.maaframework.android.model.RuntimeCapabilities
import com.maaframework.android.model.RuntimeLogChunk
import com.maaframework.android.project.MaaProjectManifestLoader
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class RuntimePrepareResult(
    val runtimeRoot: File,
    val capabilities: RuntimeCapabilities,
    val message: String,
)

object RuntimeBootstrapper {
    private const val RUNTIME_VERSION = "v1"
    private val baseAssetEntries = listOf(
        "interface.json",
        "locales",
        "i18n",
        "tasks",
        "resource",
        "resource_adb",
        "agent",
        "custom",
        "requirements.txt",
        "python",
        "MaaAgentBinary",
        "maafw",
        "agent-core.json",
        "licenses",
    )
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun prepare(
        context: Context,
        logger: RuntimeLogger,
        runtimeRoot: File = defaultRuntimeRoot(context),
        checkCancelled: () -> Unit = {},
    ): RuntimePrepareResult {
        val assets = context.assets
        val manifest = MaaProjectManifestLoader.load(context.assets)
        checkCancelled()
        stopStaleAgentProcesses(runtimeRoot, logger)
        val apk = File(context.applicationInfo.sourceDir)
        val apkIdentity = "payload-v2:${apk.absolutePath}:${apk.length()}:${apk.lastModified()}"
        val store = RuntimePayloadStore(runtimeRoot)
        fun preparePayload(repo: File?, revision: String, includesRuntime: Boolean = false): File = store.prepare(
            identity = "$apkIdentity:$revision",
            checkCancelled = checkCancelled,
            build = { staging ->
                if (repo != null) {
                    collectRequiredRepoEntries(repo).forEach { entry ->
                        checkCancelled()
                        require(entry.split('/').none { it == ".." } && !entry.startsWith('/')) { "Invalid project asset path" }
                        copyFileEntry(File(repo, entry), File(staging, entry), logger, checkCancelled)
                    }
                } else {
                    copyAssetEntry(assets, requireNotNull(manifest.bundledResourcePath), staging, logger, checkCancelled)
                }
                if (!includesRuntime && assetEntryExists(assets, "bundled_runtime")) {
                    copyAssetEntry(assets, "bundled_runtime", staging, logger, checkCancelled)
                }
                checkCancelled()
                overlayBundledPrivatePipeline(staging, logger)
                seedPythonAgentDeployVersion(staging, logger)
                applyPythonAgentCompatibilityPatches(staging, logger)
            },
            validate = { root ->
                File(root, "interface.json").isFile &&
                    listOf("libMaaFramework.so", "libMaaAgentClient.so", "libMaaAgentServer.so").all { File(root, "maafw/$it").isFile } &&
                    RuntimeAgentResolver.resolve(root)?.isRunnable == true
            },
        )
        val preparedRoot = if (!manifest.hasResourceUpdateSource() && manifest.bundledResourcePath != null) {
            preparePayload(null, "bundled:${manifest.projectId}")
        } else {
            // Network sync belongs to the host. The worker only waits for a cancellable snapshot lease.
            PersistentProjectRepositoryManager.withSnapshot(context, manifest, checkCancelled) { repo, status ->
                preparePayload(
                    repo.takeUnless { status.source == "bundled" },
                    "${status.mainRevision}:${status.updatedAt}:${status.overlayRevision}", status.includesRuntime,
                )
            }
        }
        checkCancelled()
        val maafwDir = File(preparedRoot, "maafw")
        val agentRuntimes = RuntimeAgentResolver.detect(preparedRoot)
        agentRuntimes.forEach { agent ->
            agent.executableFile?.takeIf { it.isFile }?.setExecutable(true, false)
        }
        val selectedAgent = RuntimeAgentResolver.resolve(preparedRoot)
        agentRuntimes.forEach { agent ->
            logger.log(
                "Detected agent runtime: kind=${agent.kind} source=${agent.source} " +
                    "runnable=${agent.isRunnable} path=${agent.displayPath} detail=${agent.detail}",
            )
        }

        val capabilities = RuntimeCapabilities(
            hasBundledGoService = File(preparedRoot, "agent/go-service").isFile,
            hasBundledMaaFramework = maafwDir.exists() && maafwDir.list()?.isNotEmpty() == true,
            canLaunchWindowedGame = true,
            agentRuntimes = agentRuntimes.map { agent ->
                RuntimeAgentCapability(
                    kind = agent.kind,
                    source = agent.source,
                    path = agent.displayPath,
                    runnable = agent.isRunnable,
                    detail = agent.detail,
                )
            },
            hasRunnableAgent = selectedAgent?.isRunnable == true,
        )
        val message = buildString {
            append("Runtime prepared")
            if (!capabilities.hasRunnableAgent || !capabilities.hasBundledMaaFramework) {
                append(" with missing bundled Maa runtime components")
            }
        }

        logger.log(message)
        return RuntimePrepareResult(
            runtimeRoot = preparedRoot,
            capabilities = capabilities,
            message = message,
        )
    }

    fun defaultRuntimeRoot(context: Context): File {
        return File("/data/local/tmp/${context.packageName}/maaframework-runtime/$RUNTIME_VERSION")
    }

    private fun collectRequiredRepoEntries(repoRoot: File): List<String> {
        val entries = linkedSetOf<String>()
        entries += baseAssetEntries.filter { entry -> File(repoRoot, entry).exists() }
        runCatching {
            val root = parseInterfaceRoot(repoRoot)
            collectAdditionalEntries(root).forEach { entries += it }
        }.onFailure { error ->
            loggerSafe("Failed to parse repository interface.json while collecting runtime assets: ${error.message}")
        }
        return entries.toList()
    }

    private fun parseInterfaceRoot(repoRoot: File): JsonObject {
        val text = File(repoRoot, "interface.json").readText()
        return json.parseToJsonElement(JsonWithComments.stripLineComments(text)).jsonObject
    }

    private fun collectAdditionalEntries(root: JsonObject): List<String> {
        val entries = linkedSetOf<String>()
        root["resource"].asObjects().forEach { resource ->
            stringArray(resource["path"]).forEach { entries += normalizeAssetPath(it) }
        }
        root["controller"].asObjects().forEach { controller ->
            stringArray(controller["attach_resource_path"]).forEach { entries += normalizeAssetPath(it) }
        }
        (root["agent"] as? JsonObject)?.let { agent ->
            (agent["child_exec"] as? JsonPrimitive)
                ?.contentOrNull
                ?.let(::normalizeAgentConfigPath)
                ?.let { entries += it }
            stringArray(agent["child_args"]).forEach { path ->
                normalizeAgentConfigPath(path)?.let { entries += it }
            }
        }
        return entries.toList()
    }

    private fun assetEntryExists(assets: AssetManager, path: String): Boolean {
        return try {
            val entries = assets.list(path)
            if (entries != null && entries.isNotEmpty()) {
                true
            } else {
                assets.open(path).close()
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun copyAssetEntry(
        assets: AssetManager,
        assetPath: String,
        target: File,
        logger: RuntimeLogger,
        checkCancelled: () -> Unit = {},
    ) {
        checkCancelled()
        val children = try {
            assets.list(assetPath) ?: emptyArray()
        } catch (_: Throwable) {
            emptyArray()
        }

        if (children.isEmpty()) {
            if (!assetEntryExists(assets, assetPath)) {
                return
            }
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                target.outputStream().use { output ->
                    copyCancellable(input, output, checkCancelled)
                }
            }
            return
        }

        target.mkdirs()
        for (child in children) {
            copyAssetEntry(
                assets = assets,
                assetPath = "$assetPath/$child",
                target = File(target, child),
                logger = logger,
                checkCancelled = checkCancelled,
            )
        }
        logger.log("Extracted asset directory: $assetPath")
    }

    private fun copyFileEntry(
        source: File,
        target: File,
        logger: RuntimeLogger,
        checkCancelled: () -> Unit = {},
    ) {
        checkCancelled()
        if (!source.exists()) {
            return
        }
        if (source.isDirectory) {
            copyDirectoryContents(source, target, checkCancelled)
            logger.log("Extracted repository directory: ${source.relativeToOrSelf(source.parentFile ?: source).path}")
            return
        }
        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                copyCancellable(input, output, checkCancelled)
            }
        }
    }

    private fun overlayBundledPrivatePipeline(runtimeRoot: File, logger: RuntimeLogger) {
        val overlayRoot = File(runtimeRoot, "private_pipeline")
        if (!overlayRoot.exists()) {
            return
        }

        val resourcePrivateRoot = File(overlayRoot, "resource/CommonPrivate")
        if (resourcePrivateRoot.exists()) {
            copyDirectoryContents(
                sourceRoot = resourcePrivateRoot,
                targetRoot = File(runtimeRoot, "resource/pipeline/Common/__Private"),
            )
            logger.log("Overlayed private_pipeline into resource/pipeline/Common/__Private")
        }

        val resourceAdbPrivateRoot = File(overlayRoot, "resource_adb/CommonPrivate")
        if (resourceAdbPrivateRoot.exists()) {
            copyDirectoryContents(
                sourceRoot = resourceAdbPrivateRoot,
                targetRoot = File(runtimeRoot, "resource_adb/pipeline/Common/__Private"),
            )
            logger.log("Overlayed private_pipeline into resource_adb/pipeline/Common/__Private")
        }
    }

    private fun seedPythonAgentDeployVersion(runtimeRoot: File, logger: RuntimeLogger) {
        val deployFile = File(runtimeRoot, "agent/deploy/deploy.py")
        val mainFile = File(runtimeRoot, "agent/main.py")
        if (!deployFile.isFile || !mainFile.isFile) {
            return
        }
        runCatching {
            val version = parseInterfaceRoot(runtimeRoot)["version"]
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
                ?: return
            val versionFile = File(runtimeRoot, "agent/deploy/.version")
            versionFile.parentFile?.mkdirs()
            versionFile.writeText(version)
            logger.log("Seeded Python agent deploy version: $version")
        }.onFailure { error ->
            logger.log("Failed to seed Python agent deploy version: ${error.message}")
        }
    }

    private fun applyPythonAgentCompatibilityPatches(runtimeRoot: File, logger: RuntimeLogger) {
        copyPythonModuleAlias(
            runtimeRoot = runtimeRoot,
            from = "agent/custom/action/Role/Mobius.py",
            to = "agent/custom/action/Role/Meibiwusi.py",
            logger = logger,
        )
    }

    private fun copyPythonModuleAlias(
        runtimeRoot: File,
        from: String,
        to: String,
        logger: RuntimeLogger,
    ) {
        val source = File(runtimeRoot, from)
        val target = File(runtimeRoot, to)
        if (!source.isFile || target.exists()) {
            return
        }
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = false)
        logger.log("Added Python module compatibility alias: $to -> $from")
    }

    private fun copyDirectoryContents(sourceRoot: File, targetRoot: File, checkCancelled: () -> Unit = {}) {
        sourceRoot.walkTopDown().forEach { file ->
            checkCancelled()
            val relative = file.relativeTo(sourceRoot)
            val target = File(targetRoot, relative.path)
            if (file.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                file.inputStream().use { input ->
                    target.outputStream().use { output ->
                        copyCancellable(input, output, checkCancelled)
                    }
                }
            }
        }
    }

    private fun copyCancellable(input: java.io.InputStream, output: java.io.OutputStream, checkCancelled: () -> Unit) {
        val buffer = ByteArray(128 * 1024)
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
        }
    }

    private fun stopStaleAgentProcesses(runtimeRoot: File, logger: RuntimeLogger) {
        val runtimePath = runtimeRoot.absolutePath
        val command = listOf(
            "pkill -f '^$runtimePath/(current/|previous/)?agent/' || true",
            "pkill -f '^$runtimePath/(current/|previous/)?python/' || true",
            "pkill -f '^$runtimePath/(current/|previous/)?MaaAgentBinary/' || true",
        ).joinToString("; ")
        runCatching {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val code = process.waitFor()
            if (output.isNotBlank()) {
                logger.log(output)
            }
            logger.log("Stopped stale agent processes before prepare: exit=$code")
        }.onFailure { error ->
            logger.log("Failed to stop stale agent processes: ${error.message}")
        }
    }

    private fun stringArray(element: JsonElement?): List<String> {
        return (element as? JsonArray)
            ?.mapNotNull { item -> (item as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
    }

    private fun JsonElement?.asObjects(): List<JsonObject> {
        return (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    }

    private fun normalizeAssetPath(path: String): String {
        return path.removePrefix("./")
    }

    private fun normalizeAgentConfigPath(path: String): String? {
        var normalized = path.replace('\\', '/').trim()
        if (normalized.isBlank() || normalized.startsWith("-") || normalized.startsWith("/")) {
            return null
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2)
        }
        if (normalized.startsWith("../agent/")) {
            return normalized.substring(3)
        }
        while (normalized.startsWith("../")) {
            normalized = normalized.substring(3)
        }
        return normalized.takeIf { it.contains("/") }
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) {
            return
        }
        if (file.isDirectory) {
            file.listFiles()?.forEach(::deleteRecursively)
        }
        file.delete()
    }

    private fun loggerSafe(message: String) {
        android.util.Log.w("RuntimeBootstrapper", message)
    }
}

class RuntimeLogger(runtimeRoot: File) {
    private val logFile = File(runtimeRoot, "logs/root-runtime.log").apply {
        parentFile?.mkdirs()
        if (!exists()) {
            createNewFile()
        }
    }
    private val liveLines = ArrayDeque<LiveLogEntry>()
    private var nextCursor = 0L

    @Synchronized
    fun log(message: String) {
        val line = "${System.currentTimeMillis()} $message"
        logFile.appendText("$line\n")
        nextCursor += 1L
        liveLines.addLast(LiveLogEntry(cursor = nextCursor, line = line))
        while (liveLines.size > MAX_IN_MEMORY_LINES) {
            liveLines.removeFirst()
        }
    }

    @Synchronized
    fun tail(maxLines: Int = 120): List<String> {
        return liveLines.takeLast(maxLines).map { it.line }
    }

    @Synchronized
    fun readChunk(offsetBytes: Long, maxBytes: Int = DEFAULT_CHUNK_BYTES): RuntimeLogChunk {
        val oldestCursor = liveLines.firstOrNull()?.cursor?.minus(1L) ?: nextCursor
        val reset = offsetBytes < oldestCursor || offsetBytes > nextCursor
        val startCursor = if (reset) oldestCursor else offsetBytes
        if (maxBytes <= 0) {
            return RuntimeLogChunk(
                nextOffsetBytes = nextCursor,
                reset = reset,
            )
        }

        val maxLines = maxBytes.coerceAtLeast(1)
        val lines = liveLines.asSequence()
            .filter { it.cursor > startCursor }
            .take(maxLines)
            .toList()

        return RuntimeLogChunk(
            nextOffsetBytes = lines.lastOrNull()?.cursor ?: nextCursor,
            reset = reset,
            lines = lines.map { it.line },
        )
    }

    fun file(): File = logFile

    private companion object {
        const val DEFAULT_CHUNK_BYTES = 512
        const val MAX_IN_MEMORY_LINES = 10_000
    }

    private data class LiveLogEntry(
        val cursor: Long,
        val line: String,
    )
}
