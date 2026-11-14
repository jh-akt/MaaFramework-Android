package com.maaframework.android.runtime

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimePayloadStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val root get() = tmp.root.resolve("runtime")
    private fun prepare(version: String, build: (File) -> Unit = { it.resolve("interface.json").writeText(version) }) =
        RuntimePayloadStore(root).prepare(version, {}, build) { it.resolve("interface.json").isFile }

    @Test fun `unchanged generation is reused and user data survives multiple publications`() {
        prepare("one")
        root.resolve("config/player.json").writeText("inventory")
        prepare("one") { error("Should not unpack again") }
        prepare("two")
        prepare("three") // Deletes previous; must never follow its config link.
        assertEquals("inventory", root.resolve("config/player.json").readText())
        assertEquals("inventory", root.resolve("current/config/player.json").readText())
        assertEquals("three", root.resolve("current/interface.json").readText())
    }

    @Test fun `failed or cancelled builds retain the complete previous generation`() {
        prepare("one")
        assertThrows(IllegalStateException::class.java) { prepare("two") { error("Disk full") } }
        assertThrows(CancellationException::class.java) {
            var calls = 0
            RuntimePayloadStore(root).prepare("two", { if (++calls >= 2) throw CancellationException() }, {
                it.resolve("interface.json").writeText("two")
            }) { true }
        }
        assertEquals("one", root.resolve("current/interface.json").readText())
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".payload-staging-") })
    }

    @Test fun `missing required files invalidate an otherwise matching cache key`() {
        prepare("one")
        root.resolve("current/interface.json").delete()
        var copied = false
        prepare("one") { copied = true; it.resolve("interface.json").writeText("repaired") }
        assertTrue(copied)
    }

    @Test fun `payload cannot replace persistent user data`() {
        prepare("one")
        root.resolve("config/player.json").writeText("inventory")
        assertThrows(IllegalStateException::class.java) {
            prepare("two") { it.resolve("config").mkdir(); it.resolve("interface.json").writeText("two") }
        }
        assertEquals("inventory", root.resolve("config/player.json").readText())
    }

    @Test fun `restart restores interrupted publication and preserves external symlink targets`() {
        prepare("one")
        assertTrue(root.resolve("current").renameTo(root.resolve("previous")))
        prepare("one") { error("Recovered cache must be reusable") }
        val outside = tmp.newFolder("outside")
        outside.resolve("keep").writeText("keep")
        val abandoned = root.resolve(".payload-staging-crashed").apply { mkdir() }
        Files.createSymbolicLink(abandoned.resolve("external").toPath(), outside.toPath())
        prepare("two")
        assertEquals("keep", outside.resolve("keep").readText())
    }
}
