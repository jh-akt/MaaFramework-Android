package com.maaframework.android.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStateSnapshotTest {
    @Test
    fun `display power toggle is available whenever runtime is connected`() {
        listOf(
            RunSessionPhase.Idle,
            RunSessionPhase.Completed,
            RunSessionPhase.Failed,
        ).forEach { phase ->
            val state = RuntimeStateSnapshot(phase = phase)

            assertTrue(state.canToggleDisplayPower(runtimeConnected = true))
        }
    }

    @Test
    fun `display power toggle is disabled before runtime connects`() {
        RunSessionPhase.values().forEach { phase ->
            val state = RuntimeStateSnapshot(
                phase = phase,
                displayPowerOffActive = phase == RunSessionPhase.Idle,
            )

            assertFalse(state.canToggleDisplayPower(runtimeConnected = false))
        }
    }

    @Test
    fun `running phases should keep host screen awake`() {
        listOf(
            RunSessionPhase.Preparing,
            RunSessionPhase.Running,
            RunSessionPhase.Stopping,
        ).forEach { phase ->
            val state = RuntimeStateSnapshot(phase = phase)

            assertTrue(state.shouldKeepScreenOn())
        }
    }

    @Test
    fun `display power off state should keep host screen awake`() {
        val state = RuntimeStateSnapshot(
            phase = RunSessionPhase.Idle,
            displayPowerOffActive = true,
        )

        assertTrue(state.shouldKeepScreenOn())
    }

    @Test
    fun `idle visible screen does not need keep screen on flag`() {
        val state = RuntimeStateSnapshot(
            phase = RunSessionPhase.Idle,
            displayPowerOffActive = false,
        )

        assertFalse(state.shouldKeepScreenOn())
    }
}
