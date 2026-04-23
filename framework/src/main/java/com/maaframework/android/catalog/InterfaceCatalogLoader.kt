package com.maaframework.android.catalog

import android.content.res.AssetManager
import com.maaframework.android.model.CatalogSnapshot
import com.maaframework.android.model.PresetDescriptor
import com.maaframework.android.model.ResourceDescriptor
import com.maaframework.android.model.TaskOptionCase
import com.maaframework.android.model.TaskOptionDescriptor
import com.maaframework.android.model.TaskOptionInput
import com.maaframework.android.model.TaskOptionType
import com.maaframework.android.model.TaskDescriptor
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class InterfaceCatalogLoader(
    private val assets: AssetManager? = null,
    private val supportedControllers: Set<String> = setOf("adb"),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val normalizedSupportedControllers = supportedControllers
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .toSet()

    fun load(): CatalogSnapshot {
        val interfaceText = readAssetText("interface.json")
        val localeText = readAssetTextOrNull("locales/interface/zh_cn.json")
        return parseCatalog(
            interfaceText = interfaceText,
            localeText = localeText,
            importResolver = ::readAssetText,
        )
    }

    fun loadFromDirectory(rootDir: File): CatalogSnapshot {
        val interfaceText = File(rootDir, "interface.json").readText()
        val localeText = readFileTextOrNull(File(rootDir, "locales/interface/zh_cn.json"))
        return parseCatalog(
            interfaceText = interfaceText,
            localeText = localeText,
            importResolver = { path ->
                File(rootDir, path.removePrefix("./")).readText()
            },
        )
    }

    internal fun parseCatalog(
        interfaceText: String,
        localeText: String?,
        importResolver: (String) -> String,
    ): CatalogSnapshot {
        val localeMap = parseLocaleMap(localeText)
        val root = parseJsonObject(interfaceText)
        val importPaths = stringArray(root["import"])

        val optionEntries = linkedMapOf<String, JsonElement>()
        val controllerEntries = linkedMapOf<String, JsonObject>()
        val taskObjects = mutableListOf<JsonObject>()
        val presetObjects = mutableListOf<JsonObject>()
        val resourceObjects = mutableListOf<JsonObject>()

        optionEntries.putAll((root["option"] as? JsonObject).orEmpty())
        root["controller"].asObjects().forEach { controller ->
            controller["name"].primitiveContent()?.let { controllerEntries[it] = controller }
        }
        taskObjects += root["task"].asObjects()
        presetObjects += root["preset"].asObjects()
        resourceObjects += root["resource"].asObjects()

        for (importPath in importPaths) {
            val importedRoot = parseJsonObject(importResolver(importPath))
            optionEntries.putAll((importedRoot["option"] as? JsonObject).orEmpty())
            importedRoot["controller"].asObjects().forEach { controller ->
                controller["name"].primitiveContent()?.let { controllerEntries[it] = controller }
            }
            taskObjects += importedRoot["task"].asObjects()
            presetObjects += importedRoot["preset"].asObjects()
            resourceObjects += importedRoot["resource"].asObjects()
        }

        val optionRoot = JsonObject(optionEntries)
        val controllerAliases = controllerEntries.values.associate { controller ->
            (controller["name"].primitiveContent() ?: "").trim() to
                (controller["type"].primitiveContent() ?: "").trim().lowercase()
        }

        val resources = resourceObjects.mapNotNull { resource ->
            parseResource(resource, localeMap, optionRoot, controllerAliases)
        }
        val tasks = taskObjects.mapNotNull { task ->
            parseTask(task, localeMap, optionRoot, controllerAliases)
        }
        val presets = presetObjects.mapNotNull { preset ->
            parsePreset(preset, localeMap)
        }
        val globalOptions = parseTaskOptions(
            optionIds = stringArray(root["global_option"]),
            localeMap = localeMap,
            optionRoot = optionRoot,
            controllerAliases = controllerAliases,
            seen = linkedSetOf(),
        )

        return CatalogSnapshot(
            tasks = tasks,
            presets = presets,
            resources = resources,
            globalOptions = globalOptions,
        )
    }

    private fun parseLocaleMap(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) {
            return emptyMap()
        }
        val root = parseJsonObject(text)
        return root.mapValuesNotNull { (_, value) -> value.primitiveContent() }
    }

    private fun parseTask(
        obj: JsonObject,
        localeMap: Map<String, String>,
        optionRoot: JsonObject,
        controllerAliases: Map<String, String>,
    ): TaskDescriptor? {
        val id = obj["name"].primitiveContent() ?: return null
        val controllers = stringArray(obj["controller"])
        if (!supportsControllers(controllers, controllerAliases)) {
            return null
        }

        return TaskDescriptor(
            id = id,
            label = resolveText(obj["label"].primitiveContent(), localeMap, fallback = id),
            description = resolveText(obj["description"].primitiveContent(), localeMap, fallback = ""),
            iconPath = resolveText(obj["icon"].primitiveContent(), localeMap, fallback = ""),
            entry = obj["entry"].primitiveContent() ?: "",
            groups = stringArray(obj["group"]),
            controllers = controllers,
            supportedResources = stringArray(obj["resource"]),
            defaultChecked = obj["default_check"]?.jsonPrimitive?.booleanOrNull ?: false,
            repeatable = obj["repeatable"]?.jsonPrimitive?.booleanOrNull ?: false,
            repeatCount = obj["repeat_count"]?.jsonPrimitive?.intOrNull ?: 1,
            options = parseTaskOptions(
                optionIds = stringArray(obj["option"]),
                localeMap = localeMap,
                optionRoot = optionRoot,
                controllerAliases = controllerAliases,
                seen = linkedSetOf(),
            ),
        )
    }

    private fun parsePreset(
        obj: JsonObject,
        localeMap: Map<String, String>,
    ): PresetDescriptor? {
        val id = obj["name"].primitiveContent() ?: return null
        val taskIds = obj["task"].asObjects()
            .mapNotNull { task -> task["name"].primitiveContent() }

        return PresetDescriptor(
            id = id,
            label = resolveText(obj["label"].primitiveContent(), localeMap, fallback = id),
            description = resolveText(obj["description"].primitiveContent(), localeMap, fallback = ""),
            taskIds = taskIds,
        )
    }

    private fun parseResource(
        obj: JsonObject,
        localeMap: Map<String, String>,
        optionRoot: JsonObject,
        controllerAliases: Map<String, String>,
    ): ResourceDescriptor? {
        val id = obj["name"].primitiveContent() ?: return null
        val controllers = stringArray(obj["controller"])
        if (!supportsControllers(controllers, controllerAliases)) {
            return null
        }

        return ResourceDescriptor(
            id = id,
            label = resolveText(obj["label"].primitiveContent(), localeMap, fallback = id),
            description = resolveText(obj["description"].primitiveContent(), localeMap, fallback = ""),
            iconPath = resolveText(obj["icon"].primitiveContent(), localeMap, fallback = ""),
            paths = stringArray(obj["path"]),
            controllers = controllers,
            options = parseTaskOptions(
                optionIds = stringArray(obj["option"]),
                localeMap = localeMap,
                optionRoot = optionRoot,
                controllerAliases = controllerAliases,
                seen = linkedSetOf(),
            ),
        )
    }

    private fun parseTaskOptions(
        optionIds: List<String>,
        localeMap: Map<String, String>,
        optionRoot: JsonObject,
        controllerAliases: Map<String, String>,
        seen: Set<String>,
    ): List<TaskOptionDescriptor> {
        if (optionIds.isEmpty()) {
            return emptyList()
        }
        return optionIds.mapNotNull { optionId ->
            if (optionId in seen) {
                return@mapNotNull null
            }
            val optionObj = optionRoot[optionId] as? JsonObject ?: return@mapNotNull null
            parseTaskOption(
                optionId = optionId,
                obj = optionObj,
                localeMap = localeMap,
                optionRoot = optionRoot,
                controllerAliases = controllerAliases,
                seen = seen + optionId,
            )
        }
    }

    private fun parseTaskOption(
        optionId: String,
        obj: JsonObject,
        localeMap: Map<String, String>,
        optionRoot: JsonObject,
        controllerAliases: Map<String, String>,
        seen: Set<String>,
    ): TaskOptionDescriptor? {
        val type = when (obj["type"].primitiveContent()) {
            "switch" -> TaskOptionType.Switch
            "checkbox" -> TaskOptionType.Checkbox
            "select" -> TaskOptionType.Select
            "input" -> TaskOptionType.Input
            else -> return null
        }

        val controllers = stringArray(obj["controller"])
        if (!supportsControllers(controllers, controllerAliases)) {
            return null
        }

        val defaultCaseNames = when (val defaultCase = obj["default_case"]) {
            is JsonPrimitive -> defaultCase.contentOrNull?.let(::listOf).orEmpty()
            is JsonArray -> defaultCase.mapNotNull { it.primitiveContent() }
            else -> emptyList()
        }

        val cases = obj["cases"].asObjects().mapNotNull { caseObj ->
            val caseName = caseObj["name"].primitiveContent() ?: return@mapNotNull null
            val pipelineOverride = caseObj["pipeline_override"] as? JsonObject ?: JsonObject(emptyMap())
            val nestedOptionIds = stringArray(caseObj["option"])
            TaskOptionCase(
                name = caseName,
                label = resolveOptionCaseLabel(optionId, caseObj, caseName, localeMap),
                description = resolveText(caseObj["description"].primitiveContent(), localeMap, fallback = ""),
                iconPath = resolveText(caseObj["icon"].primitiveContent(), localeMap, fallback = ""),
                pipelineOverrideJson = pipelineOverride.toString(),
                nestedOptions = parseTaskOptions(
                    optionIds = nestedOptionIds,
                    localeMap = localeMap,
                    optionRoot = optionRoot,
                    controllerAliases = controllerAliases,
                    seen = seen,
                ),
            )
        }

        val inputs = obj["input"].asObjects().mapNotNull { inputObj ->
            val name = inputObj["name"].primitiveContent() ?: return@mapNotNull null
            TaskOptionInput(
                name = name,
                label = resolveText(inputObj["label"].primitiveContent(), localeMap, fallback = name),
                description = resolveText(inputObj["description"].primitiveContent(), localeMap, fallback = ""),
                defaultValue = inputObj["default"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                verifyRegex = inputObj["verify"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                patternMessage = resolveText(inputObj["pattern_message"].primitiveContent(), localeMap, fallback = ""),
                pipelineType = inputObj["pipeline_type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }

        val pipelineOverride = obj["pipeline_override"] as? JsonObject ?: JsonObject(emptyMap())

        return TaskOptionDescriptor(
            id = optionId,
            type = type,
            label = resolveOptionLabel(optionId, obj, localeMap),
            description = resolveText(obj["description"].primitiveContent(), localeMap, fallback = ""),
            iconPath = resolveText(obj["icon"].primitiveContent(), localeMap, fallback = ""),
            supportedResources = stringArray(obj["resource"]),
            defaultCaseNames = defaultCaseNames,
            cases = cases,
            inputs = inputs,
            pipelineOverrideJson = pipelineOverride.toString(),
        )
    }

    private fun supportsControllers(
        controllerRefs: List<String>,
        controllerAliases: Map<String, String>,
    ): Boolean {
        if (normalizedSupportedControllers.isEmpty() || controllerRefs.isEmpty()) {
            return true
        }

        return controllerRefs.any { ref ->
            val normalizedRef = ref.trim().lowercase()
            normalizedRef in normalizedSupportedControllers ||
                controllerAliases[ref]?.let { it in normalizedSupportedControllers } == true
        }
    }

    private fun readAssetText(path: String): String {
        val assetManager = checkNotNull(assets) { "AssetManager is required for load()" }
        return assetManager.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun readAssetTextOrNull(path: String): String? {
        return runCatching { readAssetText(path) }.getOrNull()
    }

    private fun readFileTextOrNull(file: File): String? {
        return file.takeIf(File::exists)?.readText()
    }

    private fun parseJsonObject(text: String): JsonObject {
        return json.parseToJsonElement(JsonWithComments.stripLineComments(text)).jsonObject
    }

    private fun resolveText(keyOrText: String?, localeMap: Map<String, String>, fallback: String): String {
        if (keyOrText.isNullOrBlank()) {
            return fallback
        }
        val direct = localeMap[keyOrText]
        return when {
            direct != null -> direct
            keyOrText.startsWith("@") -> localeMap[keyOrText.removePrefix("@")] ?: fallback
            else -> keyOrText
        }
    }

    private fun resolveOptionLabel(
        optionId: String,
        obj: JsonObject,
        localeMap: Map<String, String>,
    ): String {
        return resolveText(
            keyOrText = obj["label"].primitiveContent(),
            localeMap = localeMap,
            fallback = optionId,
        )
    }

    private fun resolveOptionCaseLabel(
        optionId: String,
        obj: JsonObject,
        caseName: String,
        localeMap: Map<String, String>,
    ): String {
        return resolveText(
            keyOrText = obj["label"].primitiveContent(),
            localeMap = localeMap,
            fallback = "$optionId:$caseName",
        )
    }

    private fun JsonElement?.asObjects(): List<JsonObject> {
        return (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    }

    private fun JsonElement?.primitiveContent(): String? {
        return (this as? JsonPrimitive)?.contentOrNull
    }

    private fun stringArray(element: JsonElement?): List<String> {
        return (element as? JsonArray)
            ?.mapNotNull { item -> (item as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
    }

    private fun <K, V> Map<K, JsonElement>.mapValuesNotNull(transform: (Map.Entry<K, JsonElement>) -> V?): Map<K, V> {
        val result = linkedMapOf<K, V>()
        for (entry in entries) {
            val value = transform(entry) ?: continue
            result[entry.key] = value
        }
        return result
    }
}
