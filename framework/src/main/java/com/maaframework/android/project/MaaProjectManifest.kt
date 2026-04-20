package com.maaframework.android.project

import android.content.res.AssetManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
) {
    fun packageNameFor(resourceId: String?): String? {
        if (resourceId.isNullOrBlank()) {
            return defaultResourceId?.let(resourcePackageNames::get) ?: resourcePackageNames.values.firstOrNull()
        }
        return resourcePackageNames[resourceId]
            ?: defaultResourceId?.let(resourcePackageNames::get)
            ?: resourcePackageNames.values.firstOrNull()
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
