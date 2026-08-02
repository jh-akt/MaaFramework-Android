package com.maaframework.android.host

import android.content.Context
import com.maaframework.android.model.MaaLogLevels
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

@Serializable
data class MaaHostSettings(
    val selectedResourceId: String? = null,
    val selectedTaskId: String? = null,
    val selectedPresetId: String? = null,
    val checkedTaskIds: Set<String> = emptySet(),
    val taskOptionSelectionsByTask: Map<String, Map<String, Set<String>>> = emptyMap(),
    val taskInputValuesByTask: Map<String, Map<String, Map<String, String>>> = emptyMap(),
    val sharedOptionSelectionsByScope: Map<String, Map<String, Set<String>>> = emptyMap(),
    val sharedInputValuesByScope: Map<String, Map<String, Map<String, String>>> = emptyMap(),
    val overrideJson: String = "{}",
    val logLevel: String = MaaLogLevels.INFO,
)

@Serializable
data class MaaHostSettingsBackup(
    val version: Int = CURRENT_BACKUP_VERSION,
    val exportedAt: Long = 0L,
    val projectId: String = "",
    val settings: MaaHostSettings = MaaHostSettings(),
)

const val CURRENT_BACKUP_VERSION = 2

/**
 * Canonical settings store for Android host applications.
 *
 * It deliberately mirrors the legacy preference keys used by the three existing
 * consumers. The canonical v2 JSON snapshot is written in addition to those keys,
 * so upgrades and downgrades keep the user's selections whenever possible.
 */
class MaaHostSettingsRepository(
    context: Context,
    preferencesName: String,
    private val projectId: String,
) {
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prettyJson = Json(json) { prettyPrint = true }

    fun load(): MaaHostSettings {
        prefs.getString(KEY_SETTINGS_V2, null)?.let { raw ->
            runCatching { json.decodeFromString<MaaHostSettings>(raw) }.getOrNull()?.let { settings ->
                return settings.copy(logLevel = MaaLogLevels.normalize(settings.logLevel))
            }
        }
        return MaaHostSettings(
            selectedResourceId = prefs.getString(KEY_SELECTED_RESOURCE_ID, null),
            selectedTaskId = prefs.getString(KEY_SELECTED_TASK_ID, null)
                ?: prefs.getString(KEY_LAST_TASK_ID, null),
            selectedPresetId = prefs.getString(KEY_SELECTED_PRESET_ID, null)
                ?: prefs.getString(KEY_LAST_PRESET_ID, null),
            checkedTaskIds = decode(KEY_CHECKED_TASK_IDS, emptySet()),
            taskOptionSelectionsByTask = decode(KEY_TASK_OPTION_SELECTIONS, emptyMap()),
            taskInputValuesByTask = decode(KEY_TASK_INPUT_VALUES, emptyMap()),
            sharedOptionSelectionsByScope = decode(KEY_SHARED_OPTION_SELECTIONS, emptyMap()),
            sharedInputValuesByScope = decode(KEY_SHARED_INPUT_VALUES, emptyMap()),
            overrideJson = prefs.getString(KEY_OVERRIDE_JSON, "{}") ?: "{}",
            logLevel = MaaLogLevels.normalize(prefs.getString(KEY_LOG_LEVEL, MaaLogLevels.INFO)),
        )
    }

    fun saveSelectedResourceId(value: String?) = update { copy(selectedResourceId = value) }

    fun saveSelectedTaskId(value: String?) = update { copy(selectedTaskId = value) }

    fun saveSelectedPresetId(value: String?) = update { copy(selectedPresetId = value) }

    fun saveCheckedTaskIds(value: Set<String>) = update { copy(checkedTaskIds = value) }

    fun saveTaskOptionSelectionsByTask(value: Map<String, Map<String, Set<String>>>) =
        update { copy(taskOptionSelectionsByTask = value) }

    fun saveTaskInputValuesByTask(value: Map<String, Map<String, Map<String, String>>>) =
        update { copy(taskInputValuesByTask = value) }

    fun saveSharedOptionSelectionsByScope(value: Map<String, Map<String, Set<String>>>) =
        update { copy(sharedOptionSelectionsByScope = value) }

    fun saveSharedInputValuesByScope(value: Map<String, Map<String, Map<String, String>>>) =
        update { copy(sharedInputValuesByScope = value) }

    fun saveOverrideJson(value: String) = update { copy(overrideJson = value) }

    fun saveLogLevel(value: String) = update { copy(logLevel = MaaLogLevels.normalize(value)) }

    fun exportTo(outputStream: OutputStream) {
        val backup = MaaHostSettingsBackup(
            exportedAt = System.currentTimeMillis(),
            projectId = projectId,
            settings = load(),
        )
        outputStream.bufferedWriter().use { writer ->
            writer.write(prettyJson.encodeToString(backup))
        }
    }

    fun importFrom(inputStream: InputStream): MaaHostSettings {
        val text = inputStream.bufferedReader().use { it.readText() }
        val root = json.parseToJsonElement(text).jsonObject
        val version = root["version"]?.toString()?.trim('"')?.toIntOrNull() ?: 1
        require(version <= CURRENT_BACKUP_VERSION) { "不支持的配置版本：$version" }
        val backupProjectId = root["projectId"]?.toString()?.trim('"').orEmpty()
        require(version < CURRENT_BACKUP_VERSION || backupProjectId.isBlank() || backupProjectId == projectId) {
            "配置属于其他项目：$backupProjectId"
        }
        val settingsObject = root["settings"]?.jsonObject ?: error("配置文件缺少 settings")
        val normalized = normalizeLegacySettings(settingsObject)
        val decoded = json.decodeFromJsonElement<MaaHostSettings>(normalized)
        val settings = decoded.copy(logLevel = MaaLogLevels.normalize(decoded.logLevel))
        replaceAll(settings)
        return settings
    }

    fun replaceAll(settings: MaaHostSettings) {
        val normalized = settings.copy(logLevel = MaaLogLevels.normalize(settings.logLevel))
        prefs.edit()
            .putString(KEY_SETTINGS_V2, json.encodeToString(normalized))
            .putString(KEY_SELECTED_RESOURCE_ID, normalized.selectedResourceId)
            .putString(KEY_SELECTED_TASK_ID, normalized.selectedTaskId)
            .putString(KEY_LAST_TASK_ID, normalized.selectedTaskId)
            .putString(KEY_SELECTED_PRESET_ID, normalized.selectedPresetId)
            .putString(KEY_LAST_PRESET_ID, normalized.selectedPresetId)
            .putString(KEY_CHECKED_TASK_IDS, json.encodeToString(normalized.checkedTaskIds))
            .putString(KEY_TASK_OPTION_SELECTIONS, json.encodeToString(normalized.taskOptionSelectionsByTask))
            .putString(KEY_TASK_INPUT_VALUES, json.encodeToString(normalized.taskInputValuesByTask))
            .putString(KEY_SHARED_OPTION_SELECTIONS, json.encodeToString(normalized.sharedOptionSelectionsByScope))
            .putString(KEY_SHARED_INPUT_VALUES, json.encodeToString(normalized.sharedInputValuesByScope))
            .putString(KEY_OVERRIDE_JSON, normalized.overrideJson)
            .putString(KEY_LOG_LEVEL, normalized.logLevel)
            .apply()
    }

    private fun update(transform: MaaHostSettings.() -> MaaHostSettings) {
        replaceAll(load().transform())
    }

    private fun normalizeLegacySettings(settings: JsonObject): JsonObject {
        val normalized = settings.toMutableMap()
        if ("selectedTaskId" !in normalized) {
            settings["lastSelectedTaskId"]?.let { normalized["selectedTaskId"] = it }
        }
        if ("selectedPresetId" !in normalized) {
            settings["lastSelectedPresetId"]?.let { normalized["selectedPresetId"] = it }
        }
        return JsonObject(normalized)
    }

    private inline fun <reified T> decode(key: String, fallback: T): T {
        val raw = prefs.getString(key, null) ?: return fallback
        return runCatching { json.decodeFromString<T>(raw) }.getOrDefault(fallback)
    }

    private companion object {
        const val KEY_SETTINGS_V2 = "host_settings_v2"
        const val KEY_SELECTED_RESOURCE_ID = "selected_resource_id"
        const val KEY_SELECTED_TASK_ID = "selected_task_id"
        const val KEY_LAST_TASK_ID = "last_task_id"
        const val KEY_SELECTED_PRESET_ID = "selected_preset_id"
        const val KEY_LAST_PRESET_ID = "last_preset_id"
        const val KEY_CHECKED_TASK_IDS = "checked_task_ids"
        const val KEY_TASK_OPTION_SELECTIONS = "task_option_selections"
        const val KEY_TASK_INPUT_VALUES = "task_input_values"
        const val KEY_SHARED_OPTION_SELECTIONS = "shared_option_selections"
        const val KEY_SHARED_INPUT_VALUES = "shared_input_values"
        const val KEY_OVERRIDE_JSON = "override_json"
        const val KEY_LOG_LEVEL = "log_level"
    }
}
