package com.maaframework.android.runtime

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Declarative, app-owned adaptations, applied after upstream extraction and before publication. */
class ProjectLocalOverlay(private val readAsset: (String) -> ByteArray) {
    private val manifestBytes = readAsset("overlay.json")
    private val json = Json { prettyPrint = true }
    private val operations = json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)).jsonObject.let {
        require(it["version"]?.jsonPrimitive?.int == 1) { "Unsupported local overlay version" }
        it.getValue("operations").jsonArray.map(JsonElement::jsonObject)
    }

    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(manifestBytes)
        operations.mapNotNull { it["asset"]?.jsonPrimitive?.content }.distinct().sorted().forEach {
            digest.update(it.toByteArray()); digest.update(readAsset(it))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun apply(root: File) {
        operations.forEach { op ->
            val kind = op.getValue("op").jsonPrimitive.content
            if (kind == "json_nodes") {
                val pattern = Regex(op.getValue("files").jsonPrimitive.content)
                var matchedNodes = 0
                root.walkTopDown().filter { it.isFile && pattern.matches(it.relativeTo(root).invariantSeparatorsPath) }.forEach { file ->
                    val before = json.parseToJsonElement(file.readText().removePrefix("\uFEFF")).jsonObject
                    val patches = op.getValue("value").jsonObject
                    matchedNodes += before.keys.count { it in patches }
                    val after = JsonObject(before.mapValues { (name, node) -> patches[name]?.let { merge(node, it, false) } ?: node })
                    if (after != before) file.writeText(json.encodeToString(JsonElement.serializer(), after) + "\n")
                }
                require(matchedNodes > 0) { "Overlay node selector matched nothing: $pattern" }
                return@forEach
            }
            val file = safePayloadFile(root, op.getValue("file").jsonPrimitive.content)
            file.parentFile!!.mkdirs()
            when (kind) {
                "copy" -> file.writeBytes(readAsset(op.getValue("asset").jsonPrimitive.content))
                "copy_project" -> safePayloadFile(root, op.getValue("source").jsonPrimitive.content).copyTo(file, overwrite = true)
                "text" -> file.writeText(replaceExactly(file.readText(), op))
                "json_set", "json_text", "json_merge" -> {
                    val before = if (file.isFile) json.parseToJsonElement(file.readText().removePrefix("\uFEFF")) else JsonObject(emptyMap())
                    val path = op["path"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                    val after = edit(before, path) { old ->
                        when (kind) {
                            "json_text" -> JsonPrimitive(replaceExactly(requireNotNull(old).jsonPrimitive.content, op))
                            "json_merge" -> merge(old ?: JsonObject(emptyMap()), op.getValue("value"), op["new_keys_only"]?.jsonPrimitive?.boolean == true)
                            else -> op.getValue("value").also { value ->
                                val expected = op["expected"]
                                require(expected == null || old == expected || old == value) { "Upstream JSON changed: ${file.name} $path" }
                            }
                        }
                    }
                    file.writeText(json.encodeToString(JsonElement.serializer(), after) + "\n")
                }
                else -> error("Unknown overlay operation: $kind")
            }
        }
    }

    private fun replaceExactly(text: String, op: JsonObject): String {
        val old = op.getValue("old").jsonPrimitive.content
        val new = op.getValue("new").jsonPrimitive.content
        require(old.isNotEmpty() && new.isNotEmpty() && old != new)
        // Remove already-applied replacements before counting: new may contain old as a prefix.
        val pending = text.replace(new, "")
        val oldCount = Regex(Regex.escape(old)).findAll(pending).count()
        val newCount = Regex(Regex.escape(new)).findAll(text).count()
        require(oldCount + newCount == 1) { "Upstream text changed; local overlay needs review: $old" }
        return if (newCount == 1) text else text.replace(old, new)
    }

    private fun edit(node: JsonElement, path: List<String>, transform: (JsonElement?) -> JsonElement): JsonElement {
        if (path.isEmpty()) return transform(node)
        val head = path.first()
        if (head.startsWith("@")) {
            val (key, value) = head.drop(1).split('=', limit = 2)
            val array = node.jsonArray
            require(array.count { it.jsonObject[key]?.jsonPrimitive?.content == value } == 1) { "Overlay selector no longer matches: $head" }
            return JsonArray(array.map { if (it.jsonObject[key]?.jsonPrimitive?.content == value) edit(it, path.drop(1), transform) else it })
        }
        val obj = node.jsonObject
        val child = obj[head]
        val next = if (path.size == 1) transform(child) else edit(requireNotNull(child) { "Overlay path missing: $head" }, path.drop(1), transform)
        return JsonObject(obj + (head to next))
    }

    private fun merge(old: JsonElement, patch: JsonElement, newKeysOnly: Boolean): JsonElement {
        val result = old.jsonObject.toMutableMap()
        patch.jsonObject.forEach { (key, value) ->
            val previous = result[key]
            require(!newKeysOnly || previous == null || previous == value) { "Upstream now defines local node: $key" }
            result[key] = if (!newKeysOnly && value is JsonObject && previous is JsonObject) merge(previous, value, false) else value
        }
        return JsonObject(result)
    }
}

/** Strict relative paths shared by archive mappings, checks and local overlays. */
internal fun safePayloadFile(root: File, path: String): File {
    require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && ':' !in path &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe payload path: $path" }
    require(path.substringBefore('/') !in setOf("config", "logs", "debug", "diagnostics")) { "Payload must not contain user data: $path" }
    val file = File(root, path)
    require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Payload path escapes root: $path" }
    return file
}
