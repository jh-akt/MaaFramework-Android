package com.maaframework.android.runtime

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RepositoryDirectoryTransactionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun directory(name: String, content: String): File = temporary.newFolder(name).apply {
        resolve("interface.json").writeText(content)
    }

    @Test
    fun `successful publication preserves previous version and removes stale files`() {
        val current = directory("current", "old")
        current.resolve("removed.json").writeText("old-only")
        val staging = directory("staging", "new")
        val previous = File(temporary.root, "previous")
        RepositoryDirectoryTransaction().publish(staging, current, previous)
        assertEquals("new", current.resolve("interface.json").readText())
        assertEquals("old", previous.resolve("interface.json").readText())
        assertFalse(current.resolve("removed.json").exists())
    }

    @Test
    fun `failed backup never overwrites current repository`() {
        val current = directory("current", "old")
        val staging = directory("staging", "new")
        val result = runCatching {
            RepositoryDirectoryTransaction { _, _ -> false }.publish(staging, current, File(temporary.root, "previous"))
        }
        assertTrue(result.isFailure)
        assertEquals("old", current.resolve("interface.json").readText())
        assertTrue(staging.exists())
    }

    @Test
    fun `failed publication restores old repository without partial copies`() {
        val current = directory("current", "old")
        val staging = directory("staging", "new")
        val transaction = RepositoryDirectoryTransaction { source, target ->
            source != staging && source.renameTo(target)
        }
        assertTrue(runCatching { transaction.publish(staging, current, File(temporary.root, "previous")) }.isFailure)
        assertEquals("old", current.resolve("interface.json").readText())
        assertTrue(staging.exists())
    }

    @Test
    fun `restart recovers backup after interrupted directory swap`() {
        val previous = directory("previous", "old")
        val current = File(temporary.root, "current")
        RepositoryDirectoryTransaction().recover(current, previous)
        assertEquals("old", current.resolve("interface.json").readText())
        assertFalse(previous.exists())
    }

    @Test
    fun `failed rollback retains backup for recovery on next attempt`() {
        val current = directory("current", "old")
        val staging = directory("staging", "new")
        val previous = File(temporary.root, "previous")
        val transaction = RepositoryDirectoryTransaction { source, target ->
            source == current && source.renameTo(target)
        }
        assertTrue(runCatching { transaction.publish(staging, current, previous) }.isFailure)
        assertEquals("old", previous.resolve("interface.json").readText())
        RepositoryDirectoryTransaction().recover(current, previous)
        assertEquals("old", current.resolve("interface.json").readText())
    }
}
