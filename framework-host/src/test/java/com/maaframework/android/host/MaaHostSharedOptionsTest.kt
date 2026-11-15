package com.maaframework.android.host

import com.maaframework.android.catalog.MaaOptionOverrides.mergeOverrideJson
import com.maaframework.android.model.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MaaHostSharedOptionsTest {
    private fun option(field: String) = TaskOptionDescriptor(
        id = "count", type = TaskOptionType.Input, label = "Count",
        inputs = listOf(TaskOptionInput("n", "Count", defaultValue = "1", verifyRegex = "[0-9]+", pipelineType = "int")),
        pipelineOverrideJson = """{"Node":{"$field":"{n}"}}""",
    )

    private fun state() = MaaHostState(
        catalog = CatalogSnapshot(
            globalOptions = listOf(option("global")),
            resources = listOf(
                ResourceDescriptor("a", "A", options = listOf(option("resource"))),
                ResourceDescriptor("b", "B", options = listOf(option("resource"))),
            ),
        ),
        sharedInputValuesByScope = mapOf(
            MaaHostOptionScopes.GLOBAL to mapOf("count" to mapOf("n" to "2")),
            MaaHostOptionScopes.resource("a") to mapOf("count" to mapOf("n" to "3")),
            MaaHostOptionScopes.resource("b") to mapOf("count" to mapOf("n" to "7")),
        ),
    )

    @Test fun `identical option IDs retain global and per-resource values`() {
        val state = state()
        assertJson("""{"Node":{"global":2,"resource":3}}""", MaaHostSharedOptions.buildOverride(state, "a"))
        assertJson("""{"Node":{"global":2,"resource":7}}""", MaaHostSharedOptions.buildOverride(state, "b"))
        assertJson("""{"Node":{"global":2}}""", MaaHostSharedOptions.buildOverride(state, null))
    }

    @Test fun `resource values override global then task values override both`() {
        val state = state().let { it.copy(catalog = it.catalog.copy(globalOptions = listOf(option("resource")))) }
        val shared = MaaHostSharedOptions.buildOverride(state, "a")
        assertJson("""{"Node":{"resource":3}}""", shared)
        assertJson("""{"Node":{"resource":9}}""", mergeOverrideJson(shared, """{"Node":{"resource":9}}"""))
    }

    @Test fun `invalid input blocks only its active scope`() {
        val initial = state()
        val invalid = initial.copy(sharedInputValuesByScope = initial.sharedInputValuesByScope +
            (MaaHostOptionScopes.resource("b") to mapOf("count" to mapOf("n" to "bad"))))
        assertNull(MaaHostSharedOptions.validationError(invalid, "a"))
        assertEquals("资源包配置里有未通过校验的输入项", MaaHostSharedOptions.validationError(invalid, "b"))
        val globalInvalid = invalid.copy(sharedInputValuesByScope = invalid.sharedInputValuesByScope +
            (MaaHostOptionScopes.GLOBAL to mapOf("count" to mapOf("n" to "bad"))))
        assertEquals("全局配置里有未通过校验的输入项", MaaHostSharedOptions.validationError(globalInvalid, "a"))
    }

    private fun assertJson(expected: String, actual: String?) {
        assertNotNull(actual)
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual!!))
    }
}
