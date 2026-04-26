package com.maaframework.android.runtime

import android.content.Context
import com.maaframework.android.project.GitHubResourceRepositoryConfig
import com.maaframework.android.project.MaaProjectManifest
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class PersistentProjectRepositoryStatus(
    val available: Boolean = false,
    val source: String = "github",
    val owner: String? = null,
    val repo: String? = null,
    val branch: String? = null,
    val mainRevision: String? = null,
    val updatedAt: Long = 0L,
    val rootPath: String? = null,
    val lastError: String? = null,
)

data class PersistentProjectRepositorySyncProgress(
    val fraction: Float = 0f,
    val label: String = "",
)

object PersistentProjectRepositoryManager {
    private const val META_FILE_NAME = ".maaframework-resource.json"
    private const val USER_AGENT = "MaaFramework-Android/0.1"
    private const val DOWNLOAD_ATTEMPTS = 4
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun currentRoot(context: Context, manifest: MaaProjectManifest): File {
        manifest.requireGitHubResourceRepository()
        return File(sharedBaseDir(context, manifest.projectId), "current")
    }

    fun loadStatus(
        context: Context,
        manifest: MaaProjectManifest,
    ): PersistentProjectRepositoryStatus {
        val config = manifest.githubResourceRepository
            ?: return PersistentProjectRepositoryStatus(
                available = false,
                lastError = "GitHub resource repository is not configured",
            )
        val root = currentRoot(context, manifest)
        val metadata = readMetadata(root)
        val ready = isRepositoryReady(root, config)
        return if (ready) {
            (metadata ?: PersistentProjectRepositoryStatus(
                available = true,
                source = "github",
                owner = config.owner,
                repo = config.repo,
                branch = config.branch,
                rootPath = root.absolutePath,
            )).copy(
                available = true,
                owner = config.owner,
                repo = config.repo,
                branch = config.branch,
                rootPath = root.absolutePath,
                lastError = null,
            )
        } else {
            PersistentProjectRepositoryStatus(
                available = false,
                source = metadata?.source ?: "github",
                owner = config.owner,
                repo = config.repo,
                branch = config.branch,
                rootPath = root.absolutePath,
                lastError = metadata?.lastError,
            )
        }
    }

    fun ensureAvailable(
        context: Context,
        manifest: MaaProjectManifest,
        logger: ((String) -> Unit)? = null,
        progress: ((PersistentProjectRepositorySyncProgress) -> Unit)? = null,
    ): PersistentProjectRepositoryStatus {
        val existing = loadStatus(context, manifest)
        if (existing.available) {
            return existing
        }
        return updateFromGithub(context, manifest, logger, progress)
    }

    fun clearLocalCache(
        context: Context,
        manifest: MaaProjectManifest,
    ): PersistentProjectRepositoryStatus {
        val config = manifest.githubResourceRepository
            ?: return PersistentProjectRepositoryStatus(
                available = false,
                lastError = "GitHub resource repository is not configured",
            )
        return runCatching {
            val baseDir = sharedBaseDir(context, manifest.projectId)
            if (baseDir.exists()) {
                deleteRecursively(baseDir)
            }
            loadStatus(context, manifest)
        }.getOrElse { error ->
            PersistentProjectRepositoryStatus(
                available = false,
                source = "github",
                owner = config.owner,
                repo = config.repo,
                branch = config.branch,
                rootPath = currentRoot(context, manifest).absolutePath,
                lastError = error.message ?: error::class.java.simpleName,
            )
        }
    }

    fun updateFromGithub(
        context: Context,
        manifest: MaaProjectManifest,
        logger: ((String) -> Unit)? = null,
        progress: ((PersistentProjectRepositorySyncProgress) -> Unit)? = null,
    ): PersistentProjectRepositoryStatus {
        val config = manifest.githubResourceRepository
            ?: return PersistentProjectRepositoryStatus(
                available = false,
                lastError = "GitHub resource repository is not configured",
            )
        val baseDir = sharedBaseDir(context, manifest.projectId).apply { mkdirs() }
        val currentRoot = currentRoot(context, manifest)
        val stagingRoot = File(baseDir, "staging-${System.currentTimeMillis()}").apply { mkdirs() }
        val previousRoot = File(baseDir, "previous")

        return runCatching {
            reportProgress(progress, 0.04f, "准备同步 GitHub 资源")
            logger?.invoke("Downloading ${config.owner}/${config.repo} from GitHub")
            downloadAndExtractMainRepository(
                config = config,
                targetRoot = stagingRoot,
                logger = logger,
                progress = progress,
            )
            config.submodules.forEachIndexed { index, submodule ->
                downloadAndExtractSubmodule(
                    parentConfig = config,
                    submodule = submodule,
                    targetRoot = File(stagingRoot, normalizeRelativePath(submodule.targetPath)),
                    offset = 0.52f + (index * 0.18f),
                    logger = logger,
                    progress = progress,
                )
            }
            applyCopyMappings(stagingRoot, manifest, logger)
            check(isRepositoryReady(stagingRoot, config)) {
                "GitHub resource repository is incomplete after sync"
            }

            reportProgress(progress, 0.94f, "正在写入本地缓存")
            val status = PersistentProjectRepositoryStatus(
                available = true,
                source = "github",
                owner = config.owner,
                repo = config.repo,
                branch = config.branch,
                mainRevision = config.branch,
                updatedAt = System.currentTimeMillis(),
                rootPath = currentRoot.absolutePath,
            )
            writeMetadata(stagingRoot, status)

            if (previousRoot.exists()) {
                deleteRecursively(previousRoot)
            }
            if (currentRoot.exists()) {
                currentRoot.renameTo(previousRoot)
            }
            if (!stagingRoot.renameTo(currentRoot)) {
                copyDirectoryContents(stagingRoot, currentRoot)
                deleteRecursively(stagingRoot)
            }
            deleteRecursively(previousRoot)

            reportProgress(progress, 1f, "GitHub 资源同步完成")
            logger?.invoke("Persistent GitHub resource repository updated")
            loadStatus(context, manifest)
        }.getOrElse { error ->
            val message = error.message ?: error::class.java.simpleName
            logger?.invoke("GitHub resource repository update failed: $message")
            deleteRecursively(stagingRoot)
            loadStatus(context, manifest).copy(lastError = message)
        }
    }

    private fun sharedBaseDir(context: Context, projectId: String): File {
        val externalRoot = resolveExternalFilesRoot(context.packageName) {
            context.getExternalFilesDir(null)
        }
        return File(externalRoot, "maaframework-resource/$projectId")
    }

    internal fun resolveExternalFilesRoot(
        packageName: String,
        externalFilesDirProvider: () -> File?,
    ): File {
        return runCatching { externalFilesDirProvider() }.getOrNull()
            ?: File("/sdcard/Android/data/$packageName/files")
    }

    private fun downloadAndExtractMainRepository(
        config: GitHubResourceRepositoryConfig,
        targetRoot: File,
        logger: ((String) -> Unit)?,
        progress: ((PersistentProjectRepositorySyncProgress) -> Unit)?,
    ) {
        reportProgress(progress, 0.10f, "正在下载 GitHub 主资源")
        val zipFile = downloadToTempFile(
            url = repoZipUrl(config.owner, config.repo, config.branch),
            prefix = "maafw-main",
            logger = logger,
            onProgress = { fraction ->
                reportProgress(progress, 0.10f + (fraction * 0.28f), "正在下载 GitHub 主资源")
            },
        )
        try {
            reportProgress(progress, 0.40f, "正在解压 GitHub 主资源")
            val assetRoot = normalizeRelativePath(config.assetRootPath)
            val marker = "/$assetRoot/"
            extractZip(zipFile, targetRoot) { entryName ->
                val normalized = entryName.replace('\\', '/')
                if (assetRoot.isBlank()) {
                    normalized.substringAfter('/', "").takeIf { it.isNotBlank() }
                } else {
                    val index = normalized.indexOf(marker)
                    if (index < 0) {
                        null
                    } else {
                        normalized.substring(index + marker.length).takeIf { it.isNotBlank() }
                    }
                }
            }
        } finally {
            zipFile.delete()
        }
    }

    private fun downloadAndExtractSubmodule(
        parentConfig: GitHubResourceRepositoryConfig,
        submodule: com.maaframework.android.project.GitHubResourceSubmoduleConfig,
        targetRoot: File,
        offset: Float,
        logger: ((String) -> Unit)?,
        progress: ((PersistentProjectRepositorySyncProgress) -> Unit)?,
    ) {
        val submoduleInfo = fetchSubmodule(parentConfig, submodule, logger)
        reportProgress(progress, offset, "正在下载子模块 ${submodule.targetPath}")
        val zipFile = downloadToTempFile(
            url = repoRevisionZipUrl(submoduleInfo.owner, submoduleInfo.repo, submoduleInfo.revision),
            prefix = "maafw-submodule",
            logger = logger,
            onProgress = { fraction ->
                reportProgress(progress, offset + (fraction * 0.12f), "正在下载子模块 ${submodule.targetPath}")
            },
        )
        try {
            reportProgress(progress, offset + 0.13f, "正在解压子模块 ${submodule.targetPath}")
            extractZip(zipFile, targetRoot) { entryName ->
                entryName.substringAfter('/', "").takeIf { it.isNotBlank() }
            }
        } finally {
            zipFile.delete()
        }
    }

    private fun fetchSubmodule(
        parentConfig: GitHubResourceRepositoryConfig,
        submodule: com.maaframework.android.project.GitHubResourceSubmoduleConfig,
        logger: ((String) -> Unit)?,
    ): GitSubmoduleInfo {
        logger?.invoke("Resolving submodule ${submodule.apiPath} from GitHub API")
        val connection = openConnection(submoduleApiUrl(parentConfig.owner, parentConfig.repo, submodule.apiPath, parentConfig.branch))
        connection.inputStream.bufferedReader().use { reader ->
            val root = json.parseToJsonElement(reader.readText()).jsonObject
            val revision = root["sha"]?.jsonPrimitive?.content
                ?: error("Missing submodule revision for ${submodule.apiPath}")
            val gitUrl = root["submodule_git_url"]?.jsonPrimitive?.content
                ?: submodule.fallbackGitUrl
                ?: error("Missing submodule Git URL for ${submodule.apiPath}")
            return GitSubmoduleInfo.fromUrl(gitUrl, revision)
        }
    }

    private fun applyCopyMappings(
        root: File,
        manifest: MaaProjectManifest,
        logger: ((String) -> Unit)?,
    ) {
        manifest.githubResourceRepository?.copyMappings.orEmpty().forEach { mapping ->
            val source = File(root, normalizeRelativePath(mapping.fromPath))
            if (!source.exists()) {
                logger?.invoke("Copy mapping source missing: ${mapping.fromPath}")
                return@forEach
            }
            val target = File(root, normalizeRelativePath(mapping.toPath))
            if (target.exists()) {
                deleteRecursively(target)
            }
            copyPath(source, target)
            logger?.invoke("Applied copy mapping: ${mapping.fromPath} -> ${mapping.toPath}")
        }
    }

    private fun downloadToTempFile(
        url: String,
        prefix: String,
        logger: ((String) -> Unit)?,
        onProgress: ((Float) -> Unit)? = null,
    ): File {
        var lastError: Throwable? = null
        repeat(DOWNLOAD_ATTEMPTS) { index ->
            val attempt = index + 1
            var connection: HttpURLConnection? = null
            var tempFile: File? = null
            try {
                logger?.invoke("Downloading $url (attempt $attempt/$DOWNLOAD_ATTEMPTS)")
                tempFile = File.createTempFile(prefix, ".zip")
                connection = openConnection(url)
                val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloadedBytes = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) {
                                break
                            }
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            totalBytes?.let { total ->
                                onProgress?.invoke((downloadedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                onProgress?.invoke(1f)
                return tempFile
            } catch (error: Throwable) {
                lastError = error
                tempFile?.delete()
                logger?.invoke("Download failed on attempt $attempt/$DOWNLOAD_ATTEMPTS: ${error.message ?: error::class.java.simpleName}")
                if (attempt < DOWNLOAD_ATTEMPTS) {
                    Thread.sleep(1_500L * attempt)
                }
            } finally {
                connection?.disconnect()
            }
        }
        throw lastError ?: IllegalStateException("Download failed")
    }

    private fun reportProgress(
        progress: ((PersistentProjectRepositorySyncProgress) -> Unit)?,
        fraction: Float,
        label: String,
    ) {
        progress?.invoke(
            PersistentProjectRepositorySyncProgress(
                fraction = fraction.coerceIn(0f, 1f),
                label = label,
            ),
        )
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            val body = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
            connection.disconnect()
            error("HTTP $code for $url${body?.let { ": $it" } ?: ""}")
        }
        return connection
    }

    private fun extractZip(
        zipFile: File,
        targetRoot: File,
        pathMapper: (String) -> String?,
    ) {
        targetRoot.mkdirs()
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val relativePath = pathMapper(entry.name)?.let(::normalizeRelativePath)
                if (relativePath.isNullOrBlank()) {
                    zip.closeEntry()
                    continue
                }
                val output = File(targetRoot, relativePath)
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    output.outputStream().use { stream ->
                        zip.copyTo(stream)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun isRepositoryReady(root: File, config: GitHubResourceRepositoryConfig): Boolean {
        if (!root.isDirectory) {
            return false
        }
        val requiredPaths = config.requiredPaths.ifEmpty { listOf("interface.json", "resource") }
        return requiredPaths.all { File(root, normalizeRelativePath(it)).exists() }
    }

    private fun readMetadata(root: File): PersistentProjectRepositoryStatus? {
        val file = File(root, META_FILE_NAME)
        if (!file.exists()) {
            return null
        }
        return runCatching {
            json.decodeFromString<PersistentProjectRepositoryStatus>(file.readText())
        }.getOrNull()
    }

    private fun writeMetadata(root: File, status: PersistentProjectRepositoryStatus) {
        File(root, META_FILE_NAME).writeText(json.encodeToString(status))
    }

    private fun copyPath(source: File, target: File) {
        if (source.isDirectory) {
            copyDirectoryContents(source, target)
            return
        }
        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
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

    private fun deleteRecursively(file: File) {
        if (!file.exists()) {
            return
        }
        if (file.isDirectory) {
            file.listFiles()?.forEach(::deleteRecursively)
        }
        check(file.delete() || !file.exists()) {
            "Failed to delete ${file.absolutePath}"
        }
    }

    private fun normalizeRelativePath(path: String): String {
        val normalized = path
            .replace('\\', '/')
            .removePrefix("./")
            .removePrefix("/")
            .trim()
        return if (normalized == ".") "" else normalized
    }

    private fun repoZipUrl(owner: String, repo: String, branch: String): String {
        return "https://codeload.github.com/$owner/$repo/zip/refs/heads/$branch"
    }

    private fun submoduleApiUrl(owner: String, repo: String, apiPath: String, branch: String): String {
        return "https://api.github.com/repos/$owner/$repo/contents/${normalizeRelativePath(apiPath)}?ref=$branch"
    }

    private fun repoRevisionZipUrl(owner: String, repo: String, revision: String): String {
        return "https://codeload.github.com/$owner/$repo/zip/$revision"
    }
}

private data class GitSubmoduleInfo(
    val owner: String,
    val repo: String,
    val revision: String,
) {
    companion object {
        fun fromUrl(url: String, revision: String): GitSubmoduleInfo {
            val normalized = url.removeSuffix(".git").trimEnd('/')
            val parts = normalized.substringAfter("github.com/").split('/')
            require(parts.size >= 2) { "Unsupported submodule url: $url" }
            return GitSubmoduleInfo(
                owner = parts[0],
                repo = parts[1],
                revision = revision,
            )
        }
    }
}

private fun MaaProjectManifest.requireGitHubResourceRepository(): GitHubResourceRepositoryConfig {
    return checkNotNull(githubResourceRepository) {
        "GitHub resource repository is not configured in maa_project_manifest.json"
    }
}
