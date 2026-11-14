package com.maaframework.android.catalog

import com.maaframework.android.catalog.TaskOptionSupport
import com.maaframework.android.model.TaskOptionDescriptor
import com.maaframework.android.model.TaskOptionType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** Deterministic option expansion shared by every Android host. */
object MaaOptionOverrides {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val inputPlaceholderRegex = Regex("\\{\\s*([A-Za-z0-9_.-]+)\\s*\\}")

    fun buildOptionOverride(
        options: List<TaskOptionDescriptor>,
        selectedByOption: Map<String, Set<String>>,
        inputValuesByOption: Map<String, Map<String, String>>,
    ): String? {
        if (options.isEmpty()) {
            return null
        }

        var merged = JsonObject(emptyMap())
        var hasOverride = false
        applyOptionOverrides(
            options = options,
            selectedByOption = selectedByOption,
            inputValuesByOption = inputValuesByOption,
            onMerge = { overrideJson ->
                val overrideObject = parseOverrideObject(overrideJson) ?: return@applyOptionOverrides
                merged = mergeJsonObjects(merged, overrideObject)
                hasOverride = true
            },
        )
        return if (hasOverride) merged.toString() else null
    }

    private fun applyOptionOverrides(
        options: List<TaskOptionDescriptor>,
        selectedByOption: Map<String, Set<String>>,
        inputValuesByOption: Map<String, Map<String, String>>,
        onMerge: (String) -> Unit,
    ) {
        options.forEach { option ->
            when (option.type) {
                TaskOptionType.Switch,
                TaskOptionType.Select,
                TaskOptionType.Checkbox -> {
                    val selectedCaseNames = selectedByOption[option.id].takeUnless { it.isNullOrEmpty() }
                        ?: TaskOptionSupport.defaultSelectionForOption(option)
                    option.cases
                        .filter { it.name in selectedCaseNames }
                        .forEach { optionCase ->
                            onMerge(optionCase.pipelineOverrideJson)
                            applyOptionOverrides(
                                options = optionCase.nestedOptions,
                                selectedByOption = selectedByOption,
                                inputValuesByOption = inputValuesByOption,
                                onMerge = onMerge,
                            )
                        }
                }

                TaskOptionType.Input -> {
                    val values = buildInputValues(option, inputValuesByOption[option.id].orEmpty())
                    onMerge(applyInputPlaceholders(option.pipelineOverrideJson, values))
                }
            }
        }
    }

    private fun buildInputValues(
        option: TaskOptionDescriptor,
        currentValues: Map<String, String>,
    ): Map<String, PipelineInputValue> {
        return option.inputs.associate { input ->
            input.name to PipelineInputValue(
                value = currentValues[input.name] ?: input.defaultValue,
                pipelineType = input.pipelineType,
            )
        }
    }

    private fun applyInputPlaceholders(
        rawJson: String,
        values: Map<String, PipelineInputValue>,
    ): String {
        if (values.isEmpty() || rawJson.isBlank()) {
            return rawJson
        }
        val element = runCatching { json.parseToJsonElement(rawJson) }.getOrNull()
            ?: return replacePlaceholdersInText(rawJson, values)
        return replacePlaceholders(element, values).toString()
    }

    private fun replacePlaceholders(
        element: JsonElement,
        values: Map<String, PipelineInputValue>,
    ): JsonElement {
        return when (element) {
            is JsonObject -> buildJsonObject {
                element.forEach { (key, value) ->
                    put(key, replacePlaceholders(value, values))
                }
            }

            is JsonArray -> buildJsonArray {
                element.forEach { item ->
                    add(replacePlaceholders(item, values))
                }
            }

            is JsonPrimitive -> {
                if (!element.isString) {
                    element
                } else {
                    replaceStringPlaceholder(element.content, values)
                }
            }
        }
    }

    private fun replaceStringPlaceholder(
        rawValue: String,
        values: Map<String, PipelineInputValue>,
    ): JsonElement {
        val exactMatch = inputPlaceholderRegex.matchEntire(rawValue)
        if (exactMatch != null) {
            val key = exactMatch.groupValues[1]
            values[key]?.let(::toJsonPrimitive)?.let { return it }
        }
        return JsonPrimitive(replacePlaceholdersInText(rawValue, values))
    }

    private fun replacePlaceholdersInText(
        rawValue: String,
        values: Map<String, PipelineInputValue>,
    ): String {
        return inputPlaceholderRegex.replace(rawValue) { match ->
            values[match.groupValues[1]]?.value ?: match.value
        }
    }

    private fun toJsonPrimitive(value: PipelineInputValue): JsonPrimitive {
        return when (value.pipelineType.lowercase()) {
            "int" -> value.value.toLongOrNull()?.let(::JsonPrimitive)
                ?: value.value.toDoubleOrNull()?.let(::JsonPrimitive)
                ?: JsonPrimitive(value.value)

            "bool" -> when (value.value.lowercase()) {
                "true" -> JsonPrimitive(true)
                "false" -> JsonPrimitive(false)
                else -> JsonPrimitive(value.value)
            }

            else -> JsonPrimitive(value.value)
        }
    }

    fun mergeOverrideJson(baseJson: String?, overlayJson: String?): String? {
        val base = parseOverrideObject(baseJson)
        val overlay = parseOverrideObject(overlayJson)
        return when {
            base == null && overlay == null -> null
            base == null -> overlay?.toString()
            overlay == null -> base.toString()
            else -> mergeJsonObjects(base, overlay).toString()
        }
    }

    private fun parseOverrideObject(rawJson: String?): JsonObject? {
        if (rawJson.isNullOrBlank() || rawJson == "{}") {
            return null
        }
        return runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
    }

    private fun mergeJsonObjects(base: JsonObject, overlay: JsonObject): JsonObject {
        return buildJsonObject {
            val keys = base.keys + overlay.keys
            keys.forEach { key ->
                val baseValue = base[key]
                val overlayValue = overlay[key]
                when {
                    baseValue is JsonObject && overlayValue is JsonObject -> put(key, mergeJsonObjects(baseValue, overlayValue))
                    overlayValue != null -> put(key, overlayValue)
                    baseValue != null -> put(key, baseValue)
                }
            }
        }
    }

    private data class PipelineInputValue(val value: String, val pipelineType: String)
}
