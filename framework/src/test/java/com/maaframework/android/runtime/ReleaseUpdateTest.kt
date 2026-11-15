package com.maaframework.android.runtime

import com.maaframework.android.project.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReleaseUpdateTest {
    @get:Rule val temp = TemporaryFolder()
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry() } }
    }.toByteArray()
    private fun config() = GitHubReleaseConfig("owner", "repo", "app-arm64.*\\.apk", "v1", listOf(ReleaseArchiveMapping(listOf("assets/pi.zip"))), requiredPaths = listOf("interface.json"))
    private fun archive(content: String = "old") = zip("assets/pi.zip" to zip("interface.json" to "{}".toByteArray(), "agent/main.py" to content.toByteArray()))
    private fun overlay(replacement: String = "local"): ProjectLocalOverlay {
        val manifest = """{"version":1,"operations":[{"op":"text","file":"agent/main.py","old":"old","new":"$replacement"}]}"""
        return ProjectLocalOverlay { manifest.toByteArray() }
    }
    private fun release(bytes: ByteArray, version: String = "v2"): String {
        val f = temp.newFile(); f.writeBytes(bytes)
        return """{"tag_name":"$version","draft":false,"prerelease":false,"assets":[{"name":"app-arm64.apk","size":${bytes.size},"digest":"sha256:${f.sha256()}","browser_download_url":"https://github.com/owner/repo/releases/download/$version/app-arm64.apk"}]}"""
    }
    private fun connection(url: String, body: ByteArray, code: Int = 200, range: String? = null) = object : HttpURLConnection(URL(url)) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getHeaderField(name: String?) = if (name == "Content-Range") range else null
    }
    private fun updater(base: File, bytes: ByteArray, patch: ProjectLocalOverlay? = overlay(), validate: (File) -> Unit = {}) : GitHubReleaseUpdater {
        val release = release(bytes)
        return GitHubReleaseUpdater(config(), base, patch, validate, {}, { url -> connection(url, if (url.endsWith("latest")) release.toByteArray() else bytes) })
    }

    @Test fun `update publishes overlaid payload and retains prior version`() {
        val base = temp.newFolder()
        File(base, "current").mkdir(); File(base, "current/interface.json").writeText("old-version")
        val result = updater(base, archive()).update(PersistentProjectRepositoryStatus(available = true, mainRevision = "v1"))
        assertEquals("v2", result.mainRevision)
        assertEquals("local", File(base, "current/agent/main.py").readText())
        assertEquals("old-version", File(base, "previous/interface.json").readText())
        assertNotNull(result.overlayRevision)
    }

    @Test fun `overlay incompatibility never replaces current`() {
        val base = temp.newFolder(); File(base, "current").mkdir(); File(base, "current/interface.json").writeText("working")
        assertTrue(runCatching { updater(base, archive("upstream changed")).update(PersistentProjectRepositoryStatus()) }.isFailure)
        assertEquals("working", File(base, "current/interface.json").readText())
        assertFalse(File(base, "release-staging").exists())
    }

    @Test fun `catalog validation fails before publication`() {
        val base = temp.newFolder()
        assertTrue(runCatching { updater(base, archive(), validate = { error("invalid catalog") }).update(PersistentProjectRepositoryStatus()) }.isFailure)
        assertFalse(File(base, "current").exists())
    }

    @Test fun `new local overlay reapplies even at same upstream version`() {
        val base = temp.newFolder(); val bytes = archive()
        val initial = updater(base, bytes).update(PersistentProjectRepositoryStatus())
        updater(base, bytes, overlay("new-local")).update(initial)
        assertEquals("new-local", File(base, "current/agent/main.py").readText())
    }

    @Test fun `partial download resumes and verifies digest`() {
        val base = temp.newFolder(); val bytes = archive()
        val json = release(bytes); val asset = ReleaseAsset.parse(json, config().assetPattern)
        val partial = File(base, "downloads/${asset.sha256}.apk.part"); partial.parentFile!!.mkdirs(); partial.writeBytes(bytes.take(12).toByteArray())
        lateinit var download: HttpURLConnection
        val updater = GitHubReleaseUpdater(config(), base, overlay(), {}, {}, { url ->
            if (url.endsWith("latest")) connection(url, json.toByteArray())
            else connection(url, bytes.drop(12).toByteArray(), 206, "bytes 12-${bytes.size - 1}/${bytes.size}").also { download = it }
        })
        updater.update(PersistentProjectRepositoryStatus())
        assertEquals("bytes=12-", download.getRequestProperty("Range"))
        assertEquals("local", File(base, "current/agent/main.py").readText())
    }

    @Test fun `no digest and ambiguous architecture cannot be installed`() {
        val json = release(archive())
        assertTrue(runCatching { ReleaseAsset.parse(json.replace("sha256:", "sha512:"), config().assetPattern) }.isFailure)
        assertTrue(runCatching { ReleaseAsset.parse(json, "no-matching-apk") }.isFailure)
    }

    @Test fun `digest mismatch cannot replace a working installation`() {
        val base = temp.newFolder(); File(base, "current").mkdir(); File(base, "current/interface.json").writeText("working")
        val bytes = archive(); val metadata = release(bytes)
        val corrupted = bytes.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        val updater = GitHubReleaseUpdater(config(), base, overlay(), {}, {}, { url ->
            connection(url, if (url.endsWith("latest")) metadata.toByteArray() else corrupted)
        })
        val failure = runCatching { updater.update(PersistentProjectRepositoryStatus()) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("SHA-256"))
        assertEquals("working", File(base, "current/interface.json").readText())
    }

    @Test fun `nested zip traversal and user config are rejected`() {
        listOf("../escape", "config/account.json", "/absolute", "a/../../escape").forEach { name ->
            val file = temp.newFile(); file.writeBytes(zip("assets/pi.zip" to zip(name to byteArrayOf(1))))
            assertTrue(name, runCatching { ReleasePayloadExtractor(config()).extract(file, temp.newFolder(), temp.newFolder()) }.isFailure)
        }
    }

    @Test fun `runtime mismatch and extraction limits are rejected`() {
        val file = temp.newFile(); file.writeBytes(archive())
        val mismatch = config().copy(checks = listOf(ReleaseJsonCheck("interface.json", emptyList(), JsonPrimitive("wrong"))))
        assertTrue(runCatching { ReleasePayloadExtractor(mismatch).extract(file, temp.newFolder(), temp.newFolder()) }.isFailure)
        assertTrue(runCatching { ReleasePayloadExtractor(config().copy(maxExpandedBytes = 1)).extract(file, temp.newFolder(), temp.newFolder()) }.isFailure)
    }

    @Test fun `pipeline node overlay rejects an upstream layout with no matches`() {
        val patch = """{"version":1,"operations":[{"op":"json_nodes","files":"resource/.*","value":{"known-node":{"timeout":123}}}]}"""
        assertTrue(runCatching { ProjectLocalOverlay { patch.toByteArray() }.apply(temp.newFolder()) }.isFailure)
    }

    @Test fun `optional actual release and app overlay smoke test`() {
        val fixture = System.getenv("MAA_RELEASE_FIXTURE"); val manifest = System.getenv("MAA_PROJECT_MANIFEST"); val overlayDir = System.getenv("MAA_OVERLAY_DIR")
        assumeTrue(fixture != null && manifest != null && overlayDir != null)
        val project = Json.decodeFromString<MaaProjectManifest>(File(manifest!!).readText())
        val config = project.githubRelease!!
        val root = temp.newFolder()
        ReleasePayloadExtractor(config).extract(File(fixture!!), root, temp.newFolder())
        val local = ProjectLocalOverlay { File(overlayDir!!, it).readBytes() }
        local.apply(root); local.apply(root)
        config.requiredPaths.forEach { assertTrue("Missing $it", File(root, it).isFile) }
        val catalog = com.maaframework.android.catalog.InterfaceCatalogLoader(supportedControllers = project.supportedControllers).loadFromDirectory(root)
        assertTrue(catalog.tasks.isNotEmpty())
        project.defaultTaskId?.let { id -> assertTrue(catalog.tasks.any { it.id == id && it.controllerSupported }) }
        project.defaultResourceId?.let { id -> assertTrue(catalog.resources.any { it.id == id }) }
    }
}
