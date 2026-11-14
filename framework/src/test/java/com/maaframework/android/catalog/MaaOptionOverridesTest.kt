package com.maaframework.android.catalog

import com.maaframework.android.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MaaOptionOverridesTest {
    @Test fun `nested selected options preserve input types and merge in precedence order`() {
        val input = TaskOptionDescriptor("count", TaskOptionType.Input, "Count",
            inputs = listOf(TaskOptionInput("n", "Count", defaultValue = "2", pipelineType = "int"),
                TaskOptionInput("enabled", "Enabled", defaultValue = "true", pipelineType = "bool")),
            pipelineOverrideJson = """{"Node":{"count":"{n}","enabled":"{enabled}","label":"run {n}"}}""")
        val option = TaskOptionDescriptor("mode", TaskOptionType.Select, "Mode", defaultCaseNames = listOf("auto"),
            cases = listOf(TaskOptionCase("auto", "Auto", pipelineOverrideJson = """{"Node":{"count":1,"keep":true}}""", nestedOptions = listOf(input))))
        val generated = MaaOptionOverrides.buildOptionOverride(listOf(option), emptyMap(), mapOf("count" to mapOf("n" to "7")))
        val merged = MaaOptionOverrides.mergeOverrideJson(generated, """{"Node":{"count":9}}""")!!
        assertEquals(Json.parseToJsonElement("""{"Node":{"count":9,"enabled":true,"label":"run 7","keep":true}}"""), Json.parseToJsonElement(merged))
    }

    @Test fun `separate builds never retain values from another task`() {
        val option = TaskOptionDescriptor("x", TaskOptionType.Input, "X",
            inputs = listOf(TaskOptionInput("x", "X", defaultValue = "default")),
            pipelineOverrideJson = """{"Node":{"text":"{x}"}}""")
        MaaOptionOverrides.buildOptionOverride(listOf(option), emptyMap(), mapOf("x" to mapOf("x" to "custom")))
        assertEquals("""{"Node":{"text":"default"}}""", MaaOptionOverrides.buildOptionOverride(listOf(option), emptyMap(), emptyMap()))
        assertNull(MaaOptionOverrides.mergeOverrideJson(null, "{}"))
    }
}
