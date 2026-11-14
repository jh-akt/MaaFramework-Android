package com.maaframework.android.catalog

import com.maaframework.android.model.TaskDescriptor
import com.maaframework.android.model.TaskOptionCase
import com.maaframework.android.model.TaskOptionDescriptor
import com.maaframework.android.model.TaskOptionInput
import com.maaframework.android.model.TaskOptionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskOptionSupportTest {
    @Test
    fun filtersOptionsAndNestedOptionsByResource() {
        val nestedCn = option("nested-cn", supportedResources = listOf("cn"))
        val nestedJp = option("nested-jp", supportedResources = listOf("jp"))
        val parent = option(
            id = "parent",
            supportedResources = listOf("cn"),
            cases = listOf(
                TaskOptionCase(
                    name = "case",
                    label = "case",
                    pipelineOverrideJson = "{}",
                    nestedOptions = listOf(nestedCn, nestedJp),
                ),
            ),
        )
        val hidden = option("hidden", supportedResources = listOf("jp"))

        val filtered = TaskOptionSupport.filterOptionsForResource(listOf(parent, hidden), "cn")

        assertEquals(listOf("parent"), filtered.map { it.id })
        assertEquals(listOf("nested-cn"), filtered.first().cases.first().nestedOptions.map { it.id })
    }

    @Test
    fun defaultSelectionUsesExplicitDefaultsBeforeFirstCase() {
        val descriptor = option(
            id = "switch",
            type = TaskOptionType.Switch,
            defaultCaseNames = listOf("second"),
            cases = listOf(
                case("first"),
                case("second"),
            ),
        )

        assertEquals(setOf("second"), TaskOptionSupport.defaultSelectionForOption(descriptor))
    }

    @Test
    fun validatesSelectedNestedInputsOnly() {
        val option = option(
            id = "mode",
            type = TaskOptionType.Switch,
            cases = listOf(
                TaskOptionCase(
                    name = "enabled",
                    label = "enabled",
                    pipelineOverrideJson = "{}",
                    nestedOptions = listOf(
                        TaskOptionDescriptor(
                            id = "count",
                            type = TaskOptionType.Input,
                            label = "count",
                            inputs = listOf(
                                TaskOptionInput(
                                    name = "value",
                                    label = "value",
                                    defaultValue = "1",
                                    verifyRegex = "\\d+",
                                    patternMessage = "number only",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val errors = TaskOptionSupport.collectInputValidationErrors(
            options = listOf(option),
            selectedByOption = mapOf("mode" to setOf("enabled")),
            inputValuesByOption = mapOf("count" to mapOf("value" to "bad")),
        )

        assertEquals(mapOf("count" to mapOf("value" to "number only")), errors)
    }

    @Test
    fun reportsTaskResourceSupport() {
        val task = TaskDescriptor(
            id = "task",
            label = "Task",
            description = "",
            entry = "Task",
            groups = emptyList(),
            controllers = emptyList(),
            supportedResources = listOf("cn"),
        )

        assertTrue(TaskOptionSupport.taskSupportsResource(task, "cn"))
        assertFalse(TaskOptionSupport.taskSupportsResource(task, "jp"))
        assertTrue(TaskOptionSupport.taskSupportsResource(task, null))
    }

    @Test
    fun compatibilityComesBeforePinsAndPreservesOrderWithinEachGroup() {
        fun task(id: String, supported: Boolean = true) = TaskDescriptor(id, id, "", entry = id,
            groups = emptyList(), controllers = emptyList(), controllerSupported = supported)
        val tasks = listOf(task("pc", false), task("adb1"), task("adb2"), task("pc2", false), task("pinned"))
        assertEquals(listOf("pinned", "adb1", "adb2", "pc", "pc2"),
            TaskOptionSupport.orderTasks(tasks, linkedSetOf("pc", "pinned")).map { it.id })
        assertEquals(listOf("adb1", "adb2", "pinned", "pc", "pc2"),
            TaskOptionSupport.orderTasks(tasks).map { it.id })
        assertTrue(TaskOptionSupport.unsupportedTaskMessage(tasks)!!.contains("pc"))
    }

    @Test
    fun unsupportedTasksRemainVisibleEvenForDesktopOnlyResources() {
        val desktop = TaskDescriptor("pc", "PC", "", entry = "PC", groups = emptyList(),
            controllers = listOf("Win32"), supportedResources = listOf("desktop"), controllerSupported = false)
        val android = desktop.copy(id = "adb", controllerSupported = true, supportedResources = listOf("cn"))
        assertEquals(listOf("adb", "pc"), TaskOptionSupport.visibleTasks(listOf(desktop, android), "cn").map { it.id })
    }

    private fun option(
        id: String,
        type: TaskOptionType = TaskOptionType.Switch,
        supportedResources: List<String> = emptyList(),
        defaultCaseNames: List<String> = emptyList(),
        cases: List<TaskOptionCase> = listOf(case("default")),
    ): TaskOptionDescriptor {
        return TaskOptionDescriptor(
            id = id,
            type = type,
            label = id,
            supportedResources = supportedResources,
            defaultCaseNames = defaultCaseNames,
            cases = cases,
        )
    }

    private fun case(name: String): TaskOptionCase {
        return TaskOptionCase(
            name = name,
            label = name,
            pipelineOverrideJson = "{}",
        )
    }
}
