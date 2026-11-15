package com.maaframework.android.runtime

import com.maaframework.android.project.GitHubReleaseConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.serialization.json.*

internal data class ReleaseAsset(val version: String, val url: String, val size: Long, val sha256: String) {
    companion object {
        fun parse(response: String, pattern: String): ReleaseAsset {
            val root = Json.parseToJsonElement(response).jsonObject
            require(root["draft"]?.jsonPrimitive?.boolean != true && root["prerelease"]?.jsonPrimitive?.boolean != true)
            val asset = root.getValue("assets").jsonArray.map { it.jsonObject }
                .singleOrNull { Regex(pattern).matches(it.getValue("name").jsonPrimitive.content) }
                ?: error("Expected exactly one matching Android release asset")
            val digest = asset["digest"]?.jsonPrimitive?.content.orEmpty()
            require(digest.matches(Regex("sha256:[0-9a-fA-F]{64}"))) { "Upstream release has no SHA-256; refusing unverified update" }
            val size = asset.getValue("size").jsonPrimitive.long
            require(size in 1..(2L * 1024 * 1024 * 1024)) { "Invalid release size" }
            val url = asset.getValue("browser_download_url").jsonPrimitive.content
            require(URL(url).let { it.protocol == "https" && it.host == "github.com" }) { "Invalid release URL" }
            return ReleaseAsset(root.getValue("tag_name").jsonPrimitive.content, url, size, digest.removePrefix("sha256:").lowercase())
        }
    }
}

internal fun File.sha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(128 * 1024)
    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    digest.digest().joinToString("") { "%02x".format(it) }
}

internal class GitHubReleaseUpdater(
    private val config: GitHubReleaseConfig,
    private val base: File,
    private val overlay: ProjectLocalOverlay?,
    private val validate: (File) -> Unit,
    private val progress: (PersistentProjectRepositorySyncProgress) -> Unit,
    private val connect: (String) -> HttpURLConnection = ::openReleaseConnection,
) {
    fun update(existing: PersistentProjectRepositoryStatus): PersistentProjectRepositoryStatus {
        progress(PersistentProjectRepositorySyncProgress(0.01f, "正在检查上游 Android 发行版"))
        require(config.owner.matches(Regex("[A-Za-z0-9-]+")) && config.repo.matches(Regex("[A-Za-z0-9_.-]+")))
        val connection = connect("https://api.github.com/repos/${config.owner}/${config.repo}/releases/latest")
        val asset = try {
            require(connection.responseCode == 200) { "GitHub release check failed: HTTP ${connection.responseCode}" }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            ReleaseAsset.parse(text, config.assetPattern)
        } finally { connection.disconnect() }
        val fingerprint = overlay?.fingerprint()
        if (existing.available && existing.mainRevision == asset.version &&
            (existing.source == "bundled" || (existing.assetSha256 == asset.sha256 && existing.overlayRevision == fingerprint))) return existing
        val cache = File(base, "downloads").apply { mkdirs() }
        val archive = File(cache, "${asset.sha256}.apk")
        download(asset, archive)
        val staging = File(base, "release-staging").also { deleteTreeNoFollow(it); it.mkdirs() }
        val work = File(base, "release-work").also { deleteTreeNoFollow(it); it.mkdirs() }
        try {
            progress(PersistentProjectRepositorySyncProgress(0.76f, "正在解包上游资源与运行时"))
            ReleasePayloadExtractor(config).extract(archive, staging, work)
            progress(PersistentProjectRepositorySyncProgress(0.90f, "正在应用本地覆盖并校验"))
            overlay?.apply(staging)
            validate(staging)
            val current = File(base, "current")
            val status = PersistentProjectRepositoryStatus(
                available = true, source = "github-release", owner = config.owner, repo = config.repo,
                mainRevision = asset.version, updatedAt = System.currentTimeMillis(), rootPath = current.absolutePath,
                assetSha256 = asset.sha256, overlayRevision = fingerprint, includesRuntime = true,
                releaseConfigRevision = config.fingerprint(),
            )
            File(staging, ".maaframework-resource.json").writeText(Json.encodeToString(PersistentProjectRepositoryStatus.serializer(), status))
            RepositoryDirectoryTransaction().publish(staging, current, File(base, "previous"))
            // Keep the active archive for reapplying new local overlays. Discard old completed downloads only.
            cache.listFiles()?.filter { it != archive && it.name.matches(Regex("[0-9a-f]{64}\\.apk(\\.part)?")) }?.forEach { it.delete() }
            progress(PersistentProjectRepositorySyncProgress(1f, "已更新至 ${asset.version}，本地覆盖已应用"))
            return status
        } finally { deleteTreeNoFollow(staging); deleteTreeNoFollow(work) }
    }

    private fun download(asset: ReleaseAsset, archive: File) {
        if (archive.isFile && archive.length() == asset.size && archive.sha256() == asset.sha256) return
        archive.delete()
        val partial = File(archive.path + ".part")
        var failure: Exception? = null
        repeat(3) { attempt ->
            try {
                if (partial.length() > asset.size) partial.delete()
                if (partial.length() < asset.size) {
                    val start = partial.length()
                    val connection = connect(asset.url)
                    if (start > 0) connection.setRequestProperty("Range", "bytes=$start-")
                    try {
                        val code = connection.responseCode
                        require(code == 200 || code == 206) { "Release download failed: HTTP $code" }
                        val append = code == 206 && start > 0
                        if (code == 206) require(connection.getHeaderField("Content-Range")?.startsWith("bytes $start-") == true) { "Invalid download range" }
                        var copied = if (append) start else 0L
                        connection.inputStream.use { input ->
                            java.io.FileOutputStream(partial, append).use { output ->
                                val buffer = ByteArray(128 * 1024)
                                var reported = -1
                                while (true) {
                                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    copied += n
                                    check(copied <= asset.size) { "Release download exceeds declared size" }
                                    output.write(buffer, 0, n)
                                    val percent = (copied * 100 / asset.size).toInt()
                                    if (percent != reported) {
                                        reported = percent
                                        progress(PersistentProjectRepositorySyncProgress(0.05f + .65f * copied / asset.size, "正在下载 ${asset.version} · $percent%"))
                                    }
                                }
                            }
                        }
                    } finally { connection.disconnect() }
                }
                check(partial.length() == asset.size) { "Incomplete release download" }
                if (partial.sha256() != asset.sha256) {
                    partial.delete()
                    error("Release SHA-256 mismatch")
                }
                check(partial.renameTo(archive)) { "Cannot publish verified download" }
                return
            } catch (e: InterruptedException) { throw e }
            catch (e: Exception) { failure = e; if (attempt < 2) Thread.sleep(1000L * (attempt + 1)) }
        }
        throw requireNotNull(failure)
    }

}

private fun openReleaseConnection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000; readTimeout = 120_000
        setRequestProperty("User-Agent", "MaaFramework-Android")
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("Accept-Encoding", "identity")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    }

internal fun GitHubReleaseConfig.fingerprint(): String = MessageDigest.getInstance("SHA-256")
    .digest(Json.encodeToString(GitHubReleaseConfig.serializer(), this).toByteArray())
    .joinToString("") { "%02x".format(it) }
