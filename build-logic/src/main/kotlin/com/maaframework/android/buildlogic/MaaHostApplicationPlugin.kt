package com.maaframework.android.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register

abstract class MaaRuntimeExtension @Inject constructor(objects: ObjectFactory) {
    val localDirectory: Property<String> = objects.property(String::class.java)
        .convention("MaaFramework-Android/runtime")
    val releaseRepo: Property<String> = objects.property(String::class.java)
        .convention("jh-akt/MaaFramework-Android")
    val releaseTag: Property<String> = objects.property(String::class.java)
        .convention("android-runtime-v1")
    val releaseAsset: Property<String> = objects.property(String::class.java)
        .convention("maaframework-android-runtime-arm64-v8a.zip")
    val requireMaaFramework: Property<Boolean> = objects.property(Boolean::class.java)
        .convention(true)
    val verifyChecksum: Property<Boolean> = objects.property(Boolean::class.java)
        .convention(true)
}

class MaaHostApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        val extension = extensions.create<MaaRuntimeExtension>("maaRuntime")
        plugins.withId("com.android.application") {
            configureRuntimeBundling(extension)
        }
    }
}

private fun Project.configureRuntimeBundling(extension: MaaRuntimeExtension) {
    val localProperties = Properties().apply {
        rootProject.file("local.properties").takeIf(File::isFile)?.inputStream()?.use(::load)
    }
    fun property(name: String, fallback: String? = null): String? {
        return providers.gradleProperty(name).orNull ?: localProperties.getProperty(name) ?: fallback
    }

    val resolvedRuntimeDir = layout.buildDirectory.dir("generated/resolvedRuntime")
    val downloadedRuntimeDir = layout.buildDirectory.dir("generated/downloadedRuntime")
    val generatedAssetsDir = layout.buildDirectory.dir("generated/runtimeAssets")
    val generatedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")

    val resolveTask = tasks.register("resolveAndroidRuntime") {
        inputs.property("maafwRuntimeDir", providers.gradleProperty("maafwRuntimeDir").orElse(""))
        inputs.property("maafwRuntimeUrl", providers.gradleProperty("maafwRuntimeUrl").orElse(""))
        inputs.property("maafwRuntimeRefresh", providers.gradleProperty("maafwRuntimeRefresh").orElse("false"))
        inputs.property("localRuntimeDirectory", extension.localDirectory)
        inputs.property("runtimeReleaseRepo", extension.releaseRepo)
        inputs.property("runtimeReleaseTag", extension.releaseTag)
        inputs.property("runtimeReleaseAsset", extension.releaseAsset)
        inputs.property("requireMaaFramework", extension.requireMaaFramework)
        inputs.property("verifyChecksum", extension.verifyChecksum)
        outputs.dir(resolvedRuntimeDir)
        outputs.upToDateWhen { false }

        doLast {
            val requireMaaFramework = extension.requireMaaFramework.get()
            val runtimeDirOverride = property("maafwRuntimeDir")
            val localRuntime = resolvePath(extension.localDirectory.get())
            val releaseRepo = property("maafwRuntimeRepo", extension.releaseRepo.get())!!
            val releaseTag = property("maafwRuntimeTag", extension.releaseTag.get())!!
            val releaseAsset = property("maafwRuntimeAsset", extension.releaseAsset.get())!!
            val defaultUrl = "https://github.com/$releaseRepo/releases/download/$releaseTag/$releaseAsset"
            val runtimeUrl = property("maafwRuntimeUrl", defaultUrl)!!
            val refresh = property("maafwRuntimeRefresh", "false").toBoolean()
            val archive = layout.buildDirectory.file("downloads/$releaseAsset").get().asFile
            val resolved = resolvedRuntimeDir.get().asFile
            delete(resolved)

            val source = when {
                !runtimeDirOverride.isNullOrBlank() -> resolvePath(runtimeDirOverride).also {
                    requireRuntime(it, requireMaaFramework)
                }
                localRuntime.hasRuntimeArtifacts(requireMaaFramework) -> localRuntime
                else -> {
                    if (!archive.isFile || refresh) {
                        logger.lifecycle("Downloading MaaFramework Android runtime from $runtimeUrl")
                        download(runtimeUrl, archive)
                    }
                    if (extension.verifyChecksum.get()) {
                        verifyArchiveChecksum(
                            archive = archive,
                            expected = property("maafwRuntimeSha256"),
                            checksumUrl = property("maafwRuntimeChecksumUrl", "$runtimeUrl.sha256")!!,
                        )
                    }
                    val unpacked = downloadedRuntimeDir.get().asFile
                    delete(unpacked)
                    copy {
                        from(zipTree(archive))
                        into(unpacked)
                        includeEmptyDirs = true
                    }
                    unpacked.also { requireRuntime(it, requireMaaFramework) }
                }
            }

            copy {
                from(source)
                into(resolved)
                includeEmptyDirs = true
            }
            logger.lifecycle("Resolved MaaFramework Android runtime from ${source.absolutePath}")
        }
    }

    val prepareAssets = tasks.register<Sync>("prepareBundledRuntimeAssets") {
        dependsOn(resolveTask)
        from(resolvedRuntimeDir)
        into(generatedAssetsDir.map { it.dir("bundled_runtime") })
        includeEmptyDirs = true
    }
    val prepareJni = tasks.register<Sync>("prepareBundledRuntimeJniLibs") {
        dependsOn(resolveTask)
        from(resolvedRuntimeDir.map { it.dir("maafw") })
        into(generatedJniLibsDir.map { it.dir("arm64-v8a") })
        include("*.so")
        includeEmptyDirs = false
    }

    extensions.configure<ApplicationExtension> {
        sourceSets.getByName("main").assets.srcDir(generatedAssetsDir)
        sourceSets.getByName("main").jniLibs.srcDir(generatedJniLibsDir)
    }
    tasks.named("preBuild").configure {
        dependsOn(prepareAssets, prepareJni)
    }
}

private fun Project.resolvePath(value: String): File {
    val candidate = File(value)
    return if (candidate.isAbsolute) candidate else rootProject.file(value)
}

private fun File.hasRuntimeArtifacts(requireMaaFramework: Boolean): Boolean {
    val agentReady = resolve("agent").let { it.isDirectory && it.walkTopDown().any(File::isFile) }
    val maafwReady = resolve("maafw").let { it.isDirectory && it.walkTopDown().any { file -> file.isFile && file.extension == "so" } }
    return agentReady && (!requireMaaFramework || maafwReady)
}

private fun requireRuntime(runtimeDir: File, requireMaaFramework: Boolean) {
    if (runtimeDir.hasRuntimeArtifacts(requireMaaFramework)) return
    throw GradleException(
        "Android runtime is missing or incomplete at ${runtimeDir.absolutePath}. " +
            "Set maafwRuntimeDir/maafwRuntimeUrl or configure maaRuntime release coordinates.",
    )
}

private fun download(url: String, target: File) {
    target.parentFile.mkdirs()
    val temporary = File(target.parentFile, "${target.name}.part")
    val connection = URI(url).toURL().openConnection().apply {
        connectTimeout = 30_000
        readTimeout = 180_000
        System.getenv("GITHUB_TOKEN")?.takeIf(String::isNotBlank)?.let { token ->
            setRequestProperty("Authorization", "Bearer $token")
        }
    }
    if (connection is HttpURLConnection) {
        connection.requestMethod = "GET"
        if (connection.responseCode !in 200..299) {
            throw GradleException("Runtime download failed: HTTP ${connection.responseCode} from $url")
        }
    }
    connection.getInputStream().use { input -> temporary.outputStream().use(input::copyTo) }
    if (target.exists() && !target.delete()) throw GradleException("Cannot replace ${target.absolutePath}")
    if (!temporary.renameTo(target)) {
        temporary.copyTo(target, overwrite = true)
        temporary.delete()
    }
}

private fun verifyArchiveChecksum(archive: File, expected: String?, checksumUrl: String) {
    val checksum = expected?.trim()?.takeIf(String::isNotBlank) ?: run {
        val connection = URI(checksumUrl).toURL().openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        connection.getInputStream().bufferedReader().use { it.readText() }
            .trim()
            .substringBefore(' ')
    }
    require(checksum.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid SHA-256 checksum for ${archive.name}" }
    val digest = MessageDigest.getInstance("SHA-256")
    archive.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    if (!actual.equals(checksum, ignoreCase = true)) {
        throw GradleException("Runtime checksum mismatch for ${archive.name}: expected $checksum, got $actual")
    }
}
