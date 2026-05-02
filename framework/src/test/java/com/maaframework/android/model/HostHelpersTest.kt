package com.maaframework.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostHelpersTest {
    @Test
    fun `runtime state reports stoppable phases`() {
        assertTrue(RuntimeStateSnapshot(phase = RunSessionPhase.Preparing).canStopRun())
        assertTrue(RuntimeStateSnapshot(phase = RunSessionPhase.Running).canStopRun())
        assertTrue(RuntimeStateSnapshot(phase = RunSessionPhase.Stopping).canStopRun())
        assertFalse(RuntimeStateSnapshot(phase = RunSessionPhase.Idle).canStopRun())
        assertFalse(RuntimeStateSnapshot(phase = RunSessionPhase.Completed).canStopRun())
        assertFalse(RuntimeStateSnapshot(phase = RunSessionPhase.Failed).canStopRun())
    }

    @Test
    fun `runtime phase display supports english and chinese`() {
        assertEquals("Running", RunSessionPhase.Running.displayName())
        assertEquals("运行中", RunSessionPhase.Running.displayName(RunSessionPhaseText.Chinese))
        assertEquals("Failed", RunSessionPhase.Failed.displayName())
        assertEquals("失败", RunSessionPhase.Failed.displayName(RunSessionPhaseText.Chinese))
    }

    @Test
    fun `log level normalization accepts known levels only`() {
        assertEquals("debug", MaaLogLevels.normalize("DEBUG"))
        assertEquals("warn", MaaLogLevels.normalize("warn"))
        assertEquals("info", MaaLogLevels.normalize("verbose"))
        assertEquals("info", MaaLogLevels.normalize(null))
        assertEquals(listOf("error", "warn", "info", "debug"), MaaLogLevels.choices)
    }
}
