package com.maaframework.android.runtime

import android.content.Context
import android.content.res.AssetManager
import com.maaframework.android.catalog.JsonWithComments
import com.maaframework.android.model.RuntimeCapabilities
import com.maaframework.android.model.RuntimeLogChunk
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
        "tasks",
        "resource",
        "resource_adb",
    )
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun prepare(
        context: Context,
        logger: RuntimeLogger,
        runtimeRoot: File = defaultRuntimeRoot(context),
    ): RuntimePrepareResult {
        val assets = context.assets

        runtimeRoot.mkdirs()
        File(runtimeRoot, "logs").mkdirs()
        File(runtimeRoot, "diagnostics").mkdirs()
        stopStaleAgentProcesses(runtimeRoot, logger)
        resetRuntimePayload(runtimeRoot, logger)

        collectRequiredAssetEntries(assets).forEach { entry ->
            copyAssetEntry(assets, entry, File(runtimeRoot, entry), logger)
        }
        logger.log("Runtime resources prepared from bundled assets")

        if (assetEntryExists(assets, "bundled_runtime")) {
            copyAssetEntry(assets, "bundled_runtime", runtimeRoot, logger)
        }
        overlayBundledPrivatePipeline(runtimeRoot, logger)

        val goService = File(runtimeRoot, "agent/go-service")
        val maafwDir = File(runtimeRoot, "maafw")
        goService.parentFile?.mkdirs()
        if (goService.exists()) {
            goService.setExecutable(true, false)
        }

        val capabilities = RuntimeCapabilities(
            hasBundledGoService = goService.exists(),
            hasBundledMaaFramework = maafwDir.exists() && maafwDir.list()?.isNotEmpty() == true,
            canLaunchWindowedGame = true,
        )
        val message = buildString {
            append("Runtime prepared")
            if (!capabilities.hasBundledGoService || !capabilities.hasBundledMaaFramework) {
                append(" with missing bundled Maa runtime components")
            }
        }

        logger.log(message)
        return RuntimePrepareResult(
            runtimeRoot = runtimeRoot,
            capabilities = capabilities,
            message = message,
        )
    }

    fun defaultRuntimeRoot(context: Context): File {
        return File("/data/local/tmp/${context.packageName}/maaframework-runtime/$RUNTIME_VERSION")
    }

    private fun collectRequiredAssetEntries(assets: AssetManager): List<String> {
        val entries = linkedSetOf<String>()
        entries += baseAssetEntries.filter { entry -> assetEntryExists(assets, entry) }
        runCatching {
            val root = parseInterfaceRoot(assets)
            collectAdditionalEntries(root).forEach { entries += it }
        }.onFailure { error ->
            loggerSafe("Failed to parse interface.json while collecting runtime assets: ${error.message}")
        }
        return entries.toList()
    }

    private fun parseInterfaceRoot(assets: AssetManager): JsonObject {
        val text = assets.open("interface.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
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
    ) {
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
                    input.copyTo(output)
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
            )
        }
        logger.log("Extracted asset directory: $assetPath")
    }

    private fun resetRuntimePayload(runtimeRoot: File, logger: RuntimeLogger) {
        val staleEntries = listOf(
            "interface.json",
            "locales",
            "tasks",
            "resource",
            "resource_adb",
            "agent",
            "maafw",
            "MaaPiCli",
            "bundled_runtime",
            "private_pipeline",
        )
        staleEntries.forEach { name ->
            deleteRecursively(File(runtimeRoot, name))
        }
        deleteRecursively(File(runtimeRoot, "maafw/plugins.disabled"))
        logger.log("Cleared stale runtime payload before prepare")
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

    private fun copyDirectoryContents(sourceRoot: File, targetRoot: File) {
        sourceRoot.walkTopDown().forEach { file ->
            val relative = file.relativeTo(sourceRoot)
            val target = File(targetRoot, relative.path)
            if (file.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                file.inputStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun stopStaleAgentProcesses(runtimeRoot: File, logger: RuntimeLogger) {
        val agentPath = File(runtimeRoot, "agent/go-service").absolutePath
        val command = "pkill -f '$agentPath' || true"
        runCatching {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val code = process.waitFor()
            if (output.isNotBlank()) {
                logger.log(output)
            }
            logger.log("Stopped stale go-service processes before prepare: exit=$code")
        }.onFailure { error ->
            logger.log("Failed to stop stale go-service processes: ${error.message}")
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
