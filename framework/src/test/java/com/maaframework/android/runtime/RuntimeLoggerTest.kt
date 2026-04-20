package com.maaframework.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RuntimeLoggerTest {
    @Test
    fun `readChunk appends in-memory live logs while file still writes`() {
        val runtimeRoot = Files.createTempDirectory("runtime-logger-test").toFile()
        try {
            val logger = RuntimeLogger(runtimeRoot)
            logger.log("first line")
            logger.log("second line")

            val firstChunk = logger.readChunk(offsetBytes = 0L, maxBytes = 10)

            assertEquals(2, firstChunk.lines.size)
            assertTrue(firstChunk.lines.first().endsWith("first line"))
            assertTrue(firstChunk.lines.last().endsWith("second line"))
            assertTrue(logger.file().readText().contains("first line"))

            // Truncate the persisted file to prove UI-facing reads come from memory, not disk.
            logger.file().writeText("")
            logger.log("latest line")

            val next = logger.readChunk(offsetBytes = firstChunk.nextOffsetBytes, maxBytes = 10)

            assertEquals(1, next.lines.size)
            assertTrue(next.lines.single().endsWith("latest line"))
            assertTrue(logger.tail().last().endsWith("latest line"))
        } finally {
            runtimeRoot.deleteRecursively()
        }
    }
}
