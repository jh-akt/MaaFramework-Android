package com.maaframework.android.host

import com.maaframework.android.model.TaskDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test

class MaaHostRuntimeSupportTest {
    @Test
    fun `info hides uncategorized detail lines`() {
        val lines = listOf(
            "1710000000000 Loaded MaaFramework 1.2.3",
            "1710000000100 Run started: DailyRewards",
            "1710000000200 Warning: slow response",
            "1710000000300 Run completed",
        )

        assertEquals(
            listOf("Run started: DailyRewards", "Warning: slow response", "Run completed"),
            MaaHostLogFilter.visibleContents(lines, "info"),
        )
    }

    @Test
    fun `warn keeps warnings and errors and malformed lines stay safe`() {
        val lines = listOf(
            "}",
            "1710000000100 timeout waiting for controller response",
            "1710000000200 Failed to bind tasker",
        )

        assertEquals(
            listOf("timeout waiting for controller response", "Failed to bind tasker"),
            MaaHostLogFilter.visibleContents(lines, "warn"),
        )
        assertEquals(3, MaaHostLogFilter.visibleContents(lines, "debug").size)
    }

    @Test
    fun `policy prepends open game exactly once`() {
        val open = task("AndroidOpenGame")
        val daily = task("DailyRewards")
        assertEquals(
            listOf("AndroidOpenGame", "DailyRewards"),
            MaaHostPolicies.MaaEnd.buildSequence(listOf(daily), listOf(open, daily)).map { it.id },
        )
    }

    @Test
    fun `host retains AndroidViewModel factory constructor`() {
        org.junit.Assert.assertNotNull(MaaHostViewModel::class.java.getConstructor(android.app.Application::class.java))
    }

    private fun task(id: String) = TaskDescriptor(
        id = id,
        label = id,
        description = "",
        entry = id,
        groups = emptyList(),
        controllers = emptyList(),
    )
}
