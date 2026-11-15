package com.maaframework.android.maa

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentLibraryPathTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `Android wheel dependencies are visible before inherited libraries`() {
        val root = temporary.root
        val maa = File(root, "maafw").apply { mkdirs() }
        val python = File(root, "python")
        val lib = File(python, "lib").apply { mkdirs() }
        val wheels = File(lib, "python3.13/site-packages/chaquopy/lib").apply { mkdirs() }
        File(wheels, "libjpeg_chaquopy.so").writeText("Pillow dependency")
        val inherited = temporary.newFolder("inherited").absolutePath

        assertEquals(
            listOf(maa.absolutePath, lib.absolutePath, wheels.absolutePath, inherited).joinToString(File.pathSeparator),
            MaaFrameworkBridge.buildAgentLibraryPath(root, python, inherited),
        )
    }

    @Test fun `plain Python and non Python agents keep their existing search paths`() {
        val root = temporary.root
        val maa = File(root, "maafw").apply { mkdirs() }
        val python = File(root, "python")
        val lib = File(python, "lib").apply { mkdirs() }
        assertEquals(
            listOf(maa.absolutePath, lib.absolutePath).joinToString(File.pathSeparator),
            MaaFrameworkBridge.buildAgentLibraryPath(root, python, null),
        )
        assertEquals(maa.absolutePath, MaaFrameworkBridge.buildAgentLibraryPath(root, null, " "))
    }
}
