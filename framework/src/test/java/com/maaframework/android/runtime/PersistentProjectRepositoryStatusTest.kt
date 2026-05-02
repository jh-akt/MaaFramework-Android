package com.maaframework.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class PersistentProjectRepositoryStatusTest {
    @Test
    fun `summary uses repository branch and ready state`() {
        val status = PersistentProjectRepositoryStatus(
            available = true,
            owner = "owner",
            repo = "repo",
            branch = "main",
        )

        assertEquals("owner/repo / main / 已就绪", status.summaryText())
    }

    @Test
    fun `summary reports updating before availability`() {
        val status = PersistentProjectRepositoryStatus(
            available = true,
            owner = "owner",
            repo = "repo",
            branch = "main",
        )

        assertEquals("owner/repo / main / 同步中", status.summaryText(updating = true))
    }

    @Test
    fun `summary distinguishes missing and failed repositories`() {
        val missing = PersistentProjectRepositoryStatus(
            available = false,
            owner = "owner",
            repo = "repo",
            branch = "main",
        )
        val failed = missing.copy(lastError = "network")

        assertEquals("owner/repo / main / 尚未下载", missing.summaryText())
        assertEquals("owner/repo / main / 更新失败", failed.summaryText())
    }

    @Test
    fun `summary accepts host labels`() {
        val status = PersistentProjectRepositoryStatus(
            available = true,
            source = "github",
            branch = "v2",
        )

        assertEquals(
            "GitHub / v2 / Ready",
            status.summaryText(repositoryLabel = "GitHub", readyText = "Ready"),
        )
    }
}
