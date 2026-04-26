package com.maaframework.android.project

import android.content.res.AssetManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GitHubResourceSubmoduleConfig(
    @SerialName("api_path")
    val apiPath: String,
    @SerialName("target_path")
    val targetPath: String,
    @SerialName("fallback_git_url")
    val fallbackGitUrl: String? = null,
)

@Serializable
data class GitHubResourceCopyMapping(
    @SerialName("from")
    val fromPath: String,
    @SerialName("to")
    val toPath: String,
)

@Serializable
data class GitHubResourceRepositoryConfig(
    val owner: String,
    val repo: String,
    val branch: String = "main",
    @SerialName("asset_root_path")
    val assetRootPath: String = "assets",
    @SerialName("required_paths")
    val requiredPaths: List<String> = listOf("interface.json", "resource"),
    val submodules: List<GitHubResourceSubmoduleConfig> = emptyList(),
    @SerialName("copy_mappings")
    val copyMappings: List<GitHubResourceCopyMapping> = emptyList(),
)

@Serializable
data class MaaProjectManifest(
    @SerialName("project_id")
    val projectId: String = "sample-project",
    @SerialName("display_name")
    val displayName: String = "MAA Android Host",
    @SerialName("default_resource_id")
    val defaultResourceId: String? = null,
    @SerialName("default_task_id")
    val defaultTaskId: String? = null,
    @SerialName("default_preset_id")
    val defaultPresetId: String? = null,
    @SerialName("supported_controllers")
    val supportedControllers: Set<String> = setOf("adb"),
    @SerialName("attach_resource_paths")
    val attachResourcePaths: List<String> = listOf("./resource_adb"),
    @SerialName("resource_package_names")
    val resourcePackageNames: Map<String, String> = emptyMap(),
    @SerialName("github_resource_repository")
    val githubResourceRepository: GitHubResourceRepositoryConfig? = null,
) {
    fun packageNameFor(resourceId: String?): String? {
        if (resourceId.isNullOrBlank()) {
            return defaultResourceId?.let(resourcePackageNames::get) ?: resourcePackageNames.values.firstOrNull()
        }
        return resourcePackageNames[resourceId]
            ?: defaultResourceId?.let(resourcePackageNames::get)
            ?: resourcePackageNames.values.firstOrNull()
    }

    fun hasGitHubResourceRepository(): Boolean {
        val config = githubResourceRepository ?: return false
        return config.owner.isNotBlank() && config.repo.isNotBlank()
    }
}

object MaaProjectManifestLoader {
    private const val ASSET_PATH = "maa_project_manifest.json"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(assets: AssetManager): MaaProjectManifest {
        val text = assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return json.decodeFromString(MaaProjectManifest.serializer(), text)
    }

    fun loadOrDefault(assets: AssetManager): MaaProjectManifest {
        return runCatching { load(assets) }.getOrDefault(MaaProjectManifest())
    }
}
