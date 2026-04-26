package com.maaframework.android.sample.bbb

import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AppSettingsBackup(
    val version: Int = 1,
    val exportedAt: Long = 0L,
    val projectId: String = "maa-bbb",
    val settings: AppSettings = AppSettings(),
)

@Serializable
data class AppSettings(
    val selectedResourceId: String? = null,
    val selectedTaskId: String? = null,
    val selectedPresetId: String? = null,
    val checkedTaskIds: Set<String> = emptySet(),
    val taskOptionSelectionsByTask: Map<String, Map<String, Set<String>>> = emptyMap(),
    val taskInputValuesByTask: Map<String, Map<String, Map<String, String>>> = emptyMap(),
    val overrideJson: String = "{}",
)

class AppSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("maabbb_android_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prettyJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun load(): AppSettings {
        return AppSettings(
            selectedResourceId = prefs.getString(KEY_SELECTED_RESOURCE_ID, null),
            selectedTaskId = prefs.getString(KEY_SELECTED_TASK_ID, null),
            selectedPresetId = prefs.getString(KEY_SELECTED_PRESET_ID, null),
            checkedTaskIds = decode(KEY_CHECKED_TASK_IDS, emptySet()),
            taskOptionSelectionsByTask = decode(KEY_TASK_OPTION_SELECTIONS, emptyMap()),
            taskInputValuesByTask = decode(KEY_TASK_INPUT_VALUES, emptyMap()),
            overrideJson = prefs.getString(KEY_OVERRIDE_JSON, "{}") ?: "{}",
        )
    }

    fun saveSelectedResourceId(resourceId: String?) {
        prefs.edit().putString(KEY_SELECTED_RESOURCE_ID, resourceId).apply()
    }

    fun saveSelectedTaskId(taskId: String?) {
        prefs.edit().putString(KEY_SELECTED_TASK_ID, taskId).apply()
    }

    fun saveSelectedPresetId(presetId: String?) {
        prefs.edit().putString(KEY_SELECTED_PRESET_ID, presetId).apply()
    }

    fun saveCheckedTaskIds(taskIds: Set<String>) {
        encode(KEY_CHECKED_TASK_IDS, taskIds)
    }

    fun saveTaskOptionSelectionsByTask(selections: Map<String, Map<String, Set<String>>>) {
        encode(KEY_TASK_OPTION_SELECTIONS, selections)
    }

    fun saveTaskInputValuesByTask(values: Map<String, Map<String, Map<String, String>>>) {
        encode(KEY_TASK_INPUT_VALUES, values)
    }

    fun saveOverrideJson(value: String) {
        prefs.edit().putString(KEY_OVERRIDE_JSON, value).apply()
    }

    fun exportTo(outputStream: OutputStream) {
        val backup = AppSettingsBackup(
            exportedAt = System.currentTimeMillis(),
            settings = load(),
        )
        outputStream.bufferedWriter().use { writer ->
            writer.write(prettyJson.encodeToString(backup))
        }
    }

    fun importFrom(inputStream: InputStream): AppSettings {
        val backup = inputStream.bufferedReader().use { reader ->
            json.decodeFromString<AppSettingsBackup>(reader.readText())
        }
        require(backup.version <= BACKUP_VERSION) {
            "不支持的配置版本：${backup.version}"
        }
        replaceAll(backup.settings)
        return backup.settings
    }

    fun replaceAll(settings: AppSettings) {
        prefs.edit()
            .clear()
            .putString(KEY_SELECTED_RESOURCE_ID, settings.selectedResourceId)
            .putString(KEY_SELECTED_TASK_ID, settings.selectedTaskId)
            .putString(KEY_SELECTED_PRESET_ID, settings.selectedPresetId)
            .putString(KEY_CHECKED_TASK_IDS, json.encodeToString(settings.checkedTaskIds))
            .putString(KEY_TASK_OPTION_SELECTIONS, json.encodeToString(settings.taskOptionSelectionsByTask))
            .putString(KEY_TASK_INPUT_VALUES, json.encodeToString(settings.taskInputValuesByTask))
            .putString(KEY_OVERRIDE_JSON, settings.overrideJson)
            .apply()
    }

    private inline fun <reified T> decode(key: String, defaultValue: T): T {
        val raw = prefs.getString(key, null) ?: return defaultValue
        return runCatching { json.decodeFromString<T>(raw) }.getOrDefault(defaultValue)
    }

    private inline fun <reified T> encode(key: String, value: T) {
        prefs.edit().putString(key, json.encodeToString(value)).apply()
    }

    private companion object {
        const val BACKUP_VERSION = 1
        const val KEY_SELECTED_RESOURCE_ID = "selected_resource_id"
        const val KEY_SELECTED_TASK_ID = "selected_task_id"
        const val KEY_SELECTED_PRESET_ID = "selected_preset_id"
        const val KEY_CHECKED_TASK_IDS = "checked_task_ids"
        const val KEY_TASK_OPTION_SELECTIONS = "task_option_selections"
        const val KEY_TASK_INPUT_VALUES = "task_input_values"
        const val KEY_OVERRIDE_JSON = "override_json"
    }
}
