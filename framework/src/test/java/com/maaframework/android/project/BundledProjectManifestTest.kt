package com.maaframework.android.project

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BundledProjectManifestTest {
    @Test fun `bundled source is independent of GitHub and keeps package mapping`() {
        val manifest = Json.decodeFromString<MaaProjectManifest>("""{
          "project_id":"test", "bundled_resource_path":"project",
          "default_resource_id":"jp", "resource_package_names":{"jp":"example.game"}
        }""")
        assertEquals("project", manifest.bundledResourcePath)
        assertFalse(manifest.hasGitHubResourceRepository())
        assertEquals("example.game", manifest.packageNameFor(null))
    }

    @Test fun `invalid or ambiguous sources are rejected`() {
        listOf("", "../project", "/project", "project/../other", "project//other").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { MaaProjectManifest(bundledResourcePath = path) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            MaaProjectManifest(bundledResourcePath = "project",
                githubResourceRepository = GitHubResourceRepositoryConfig("owner", "repo"))
        }
    }
}
