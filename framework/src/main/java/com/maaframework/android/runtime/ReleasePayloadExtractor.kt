package com.maaframework.android.runtime

import com.maaframework.android.project.GitHubReleaseConfig
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.serialization.json.*

/** Disk-backed nested ZIP extraction. Memory use does not grow with APK or model size. */
internal class ReleasePayloadExtractor(private val config: GitHubReleaseConfig) {
    fun extract(archive: File, destination: File, work: File) {
        destination.mkdirs(); work.mkdirs()
        val nested = mutableMapOf<List<String>, File>(emptyList<String>() to archive)
        var expanded = 0L
        var entries = 0
        fun copy(input: InputStream, file: File) {
            file.parentFile!!.mkdirs()
            file.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val n = input.read(buffer)
                    if (n < 0) break
                    expanded += n
                    check(expanded <= config.maxExpandedBytes) { "Release exceeds expansion limit" }
                    output.write(buffer, 0, n)
                }
            }
        }
        fun resolve(chain: List<String>): File {
            nested[chain]?.let { return it }
            val parent = resolve(chain.dropLast(1))
            val name = chain.last()
            safePayloadFile(work, name)
            val target = File(work, "nested-${nested.size}.zip")
            ZipFile(parent).use { zip ->
                val entry = requireNotNull(zip.getEntry(name)) { "Release archive missing: $name" }
                zip.getInputStream(entry).use { copy(it, target) }
            }
            nested[chain] = target
            return target
        }
        val outputs = hashSetOf<String>()
        config.mappings.forEach { mapping ->
            val include = Regex(mapping.include)
            val exclude = mapping.exclude?.let(::Regex)
            var matched = 0
            ZipFile(resolve(mapping.archives)).use { zip ->
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name
                    val relative = when {
                        mapping.source.isEmpty() -> name
                        mapping.source.endsWith('/') && name.startsWith(mapping.source) -> name.removePrefix(mapping.source)
                        name == mapping.source -> ""
                        else -> continue
                    }
                    if (!include.matches(relative) || exclude?.matches(relative) == true) continue
                    // Validate the original as well as the mapped name; never normalize traversal away.
                    safePayloadFile(work, name)
                    val path = listOf(mapping.target.trimEnd('/'), relative).filter(String::isNotEmpty).joinToString("/")
                    val target = safePayloadFile(destination, path)
                    check(outputs.add(path)) { "Duplicate release entry: $path" }
                    check(++entries <= 100_000) { "Too many release files" }
                    zip.getInputStream(entry).use { copy(it, target) }
                    matched++
                }
            }
            check(matched > 0) { "Release mapping matched no files: ${mapping.source}" }
        }
        config.checks.forEach { check ->
            var value: JsonElement = Json.parseToJsonElement(safePayloadFile(destination, check.file).readText())
            check.path.forEach { value = value.jsonObject.getValue(it) }
            require(value == check.expected) { "Upstream runtime is incompatible (${check.file}: ${check.path.joinToString(".")}); update the APK first" }
        }
        config.requiredPaths.forEach {
            val file = safePayloadFile(destination, it)
            require(file.isFile && file.length() > 0) { "Release component missing: $it" }
        }
        // External Android storage may reject chmod. This is only the download cache;
        // RuntimeBootstrapper applies executable bits after copying onto the private runtime filesystem.
        config.executablePaths.forEach { require(safePayloadFile(destination, it).isFile) { "Missing executable: $it" } }
    }
}
