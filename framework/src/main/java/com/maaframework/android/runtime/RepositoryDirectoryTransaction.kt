package com.maaframework.android.runtime

import java.io.File

/** Both directories are siblings so publication never falls back to a partial copy. */
internal class RepositoryDirectoryTransaction(
    private val move: (File, File) -> Boolean = { source, target -> source.renameTo(target) },
) {
    fun recover(current: File, previous: File) {
        if (!current.exists() && previous.exists()) {
            check(move(previous, current)) { "Cannot restore previous resource repository" }
        }
    }

    fun publish(staging: File, current: File, previous: File) {
        recover(current, previous)
        deleteTreeNoFollow(previous)
        val hadCurrent = current.exists()
        if (hadCurrent) {
            check(move(current, previous)) { "Cannot back up current resource repository" }
        }
        if (!move(staging, current)) {
            if (hadCurrent) {
                check(move(previous, current)) { "Publication failed; previous repository retained at ${previous.name}" }
            }
            error("Cannot publish staged resource repository")
        }
        // Keep the last good version until the next publication, including across process death.
    }
}
