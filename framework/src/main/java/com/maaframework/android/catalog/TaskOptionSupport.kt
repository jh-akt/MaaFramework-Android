package com.maaframework.android.catalog

import com.maaframework.android.model.TaskDescriptor
import com.maaframework.android.model.TaskOptionDescriptor
import com.maaframework.android.model.TaskOptionInput
import com.maaframework.android.model.TaskOptionType

object TaskOptionSupport {
    /** Unsupported controller tasks remain visible at the end, even for a desktop-only resource. */
    fun visibleTasks(tasks: List<TaskDescriptor>, resourceId: String?): List<TaskDescriptor> =
        orderTasks(tasks.filter { !it.controllerSupported || taskSupportsResource(it, resourceId) })

    /** Stable compatibility partition, with product pins applied only inside each partition. */
    fun orderTasks(tasks: List<TaskDescriptor>, pinnedTaskIds: Set<String> = emptySet()): List<TaskDescriptor> {
        val pins = pinnedTaskIds.withIndex().associate { it.value to it.index }
        return tasks.sortedWith(compareBy<TaskDescriptor> { !it.controllerSupported }
            .thenBy { pins[it.id] ?: Int.MAX_VALUE })
    }

    fun unsupportedTaskMessage(tasks: List<TaskDescriptor>): String? =
        tasks.firstOrNull { !it.controllerSupported }?.let { "任务「${it.label}」不支持当前 Android 控制器，无法执行" }

    fun taskSupportsResource(
        task: TaskDescriptor,
        resourceId: String?,
    ): Boolean {
        return supportsResource(task.supportedResources, resourceId)
    }

    fun filterOptionsForResource(
        options: List<TaskOptionDescriptor>,
        resourceId: String?,
    ): List<TaskOptionDescriptor> {
        return options.mapNotNull { option ->
            if (!supportsResource(option.supportedResources, resourceId)) {
                return@mapNotNull null
            }
            option.copy(
                cases = option.cases.map { optionCase ->
                    optionCase.copy(
                        nestedOptions = filterOptionsForResource(optionCase.nestedOptions, resourceId),
                    )
                },
            )
        }
    }

    fun defaultSelectionForOption(option: TaskOptionDescriptor): Set<String> {
        val defaults = option.defaultCaseNames.toSet()
        if (defaults.isNotEmpty()) {
            return defaults
        }
        return when (option.type) {
            TaskOptionType.Switch,
            TaskOptionType.Select -> option.cases.firstOrNull()?.name?.let(::setOf).orEmpty()
            TaskOptionType.Checkbox,
            TaskOptionType.Input -> emptySet()
        }
    }

    fun collectInputValidationErrors(
        options: List<TaskOptionDescriptor>,
        selectedByOption: Map<String, Set<String>>,
        inputValuesByOption: Map<String, Map<String, String>>,
    ): Map<String, Map<String, String>> {
        val errors = linkedMapOf<String, MutableMap<String, String>>()
        collectInputValidationErrorsRecursive(
            options = options,
            selectedByOption = selectedByOption,
            inputValuesByOption = inputValuesByOption,
            into = errors,
        )
        return errors
    }

    private fun collectInputValidationErrorsRecursive(
        options: List<TaskOptionDescriptor>,
        selectedByOption: Map<String, Set<String>>,
        inputValuesByOption: Map<String, Map<String, String>>,
        into: MutableMap<String, MutableMap<String, String>>,
    ) {
        options.forEach { option ->
            when (option.type) {
                TaskOptionType.Input -> {
                    val values = inputValuesByOption[option.id].orEmpty()
                    option.inputs.forEach { input ->
                        validateInput(input, values[input.name] ?: input.defaultValue)?.let { error ->
                            into.getOrPut(option.id) { linkedMapOf() }[input.name] = error
                        }
                    }
                }

                TaskOptionType.Switch,
                TaskOptionType.Select,
                TaskOptionType.Checkbox -> {
                    val selected = selectedByOption[option.id].takeUnless { it.isNullOrEmpty() }
                        ?: defaultSelectionForOption(option)
                    option.cases
                        .filter { it.name in selected }
                        .forEach { optionCase ->
                            collectInputValidationErrorsRecursive(
                                options = optionCase.nestedOptions,
                                selectedByOption = selectedByOption,
                                inputValuesByOption = inputValuesByOption,
                                into = into,
                            )
                        }
                }
            }
        }
    }

    private fun validateInput(
        input: TaskOptionInput,
        value: String,
    ): String? {
        val pattern = input.verifyRegex.takeIf { it.isNotBlank() } ?: return null
        val regex = runCatching { Regex(pattern) }.getOrNull() ?: return null
        if (regex.matches(value)) {
            return null
        }
        return input.patternMessage.takeIf { it.isNotBlank() } ?: "输入格式不正确"
    }

    private fun supportsResource(
        supportedResources: List<String>,
        resourceId: String?,
    ): Boolean {
        return supportedResources.isEmpty() || resourceId.isNullOrBlank() || resourceId in supportedResources
    }
}
