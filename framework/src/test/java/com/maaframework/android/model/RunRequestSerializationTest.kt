package com.maaframework.android.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RunRequestSerializationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `run request keeps task sequence during serialization`() {
        val request = RunRequest(
            presetId = "QuickDaily",
            sequenceTaskIds = listOf("AndroidOpenGame", "DailyRewards"),
            logLevel = "debug",
        )

        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString(RunRequest.serializer(), encoded)

        assertEquals("QuickDaily", decoded.presetId)
        assertEquals(listOf("AndroidOpenGame", "DailyRewards"), decoded.sequenceTaskIds)
        assertEquals("debug", decoded.logLevel)
    }
}
