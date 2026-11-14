package com.maaframework.android.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** No user data lives in disposable generations. Called by the sole runtime worker. */
internal class RuntimePayloadStore(private val base: File) {
    private val current = File(base, "current")
    private val previous = File(base, "previous")
    private val transaction = RepositoryDirectoryTransaction()

    fun prepare(
        identity: String,
        checkCancelled: () -> Unit,
        build: (File) -> Unit,
        validate: (File) -> Boolean,
    ): File {
        check(base.isDirectory || base.mkdirs())
        transaction.recover(current, previous)
        checkCancelled()
        if (File(current, STAMP).takeIf { it.isFile }?.readText() == identity && validate(current)) return current
        // Only abandoned generated directories are removed, without following user-data links.
        base.listFiles().orEmpty().filter { it.name.startsWith(".payload-staging-") }.forEach(::deleteTreeNoFollow)
        val staging = File(base, ".payload-staging-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            build(staging)
            checkCancelled()
            check(validate(staging)) { "Runtime payload is incomplete" }
            for (name in listOf("config", "logs", "debug", "diagnostics")) {
                check(!File(staging, name).exists()) { "Bundled payload must not overwrite user directory: $name" }
                val shared = File(base, name).apply { check(isDirectory || mkdirs()) }
                Files.createSymbolicLink(File(staging, name).toPath(), shared.toPath().toAbsolutePath())
            }
            File(staging, STAMP).writeText(identity)
            checkCancelled()
            transaction.publish(staging, current, previous)
            return current
        } finally {
            deleteTreeNoFollow(staging)
        }
    }

    companion object { private const val STAMP = ".runtime-identity" }
}

internal fun deleteTreeNoFollow(file: File) {
    if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
        override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
            Files.delete(path)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(path: Path, error: java.io.IOException?): FileVisitResult {
            if (error != null) throw error
            Files.delete(path)
            return FileVisitResult.CONTINUE
        }
    })
}
