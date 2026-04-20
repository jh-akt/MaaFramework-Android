package com.maaframework.android.model

import kotlinx.serialization.Serializable

@Serializable
enum class TaskOptionType {
    Switch,
    Checkbox,
    Select,
    Input,
}

@Serializable
data class TaskOptionInput(
    val name: String,
    val label: String,
    val description: String = "",
    val defaultValue: String = "",
    val verifyRegex: String = "",
    val patternMessage: String = "",
    val pipelineType: String = "",
)

@Serializable
data class TaskOptionCase(
    val name: String,
    val label: String,
    val description: String = "",
    val iconPath: String = "",
    val pipelineOverrideJson: String,
    val nestedOptions: List<TaskOptionDescriptor> = emptyList(),
)

@Serializable
data class TaskOptionDescriptor(
    val id: String,
    val type: TaskOptionType,
    val label: String,
    val description: String = "",
    val iconPath: String = "",
    val supportedResources: List<String> = emptyList(),
    val defaultCaseNames: List<String> = emptyList(),
    val cases: List<TaskOptionCase> = emptyList(),
    val inputs: List<TaskOptionInput> = emptyList(),
    val pipelineOverrideJson: String = "{}",
)

@Serializable
data class TaskDescriptor(
    val id: String,
    val label: String,
    val description: String,
    val iconPath: String = "",
    val entry: String,
    val groups: List<String>,
    val controllers: List<String>,
    val supportedResources: List<String> = emptyList(),
    val defaultChecked: Boolean = false,
    val repeatable: Boolean = false,
    val repeatCount: Int = 1,
    val options: List<TaskOptionDescriptor> = emptyList(),
)

@Serializable
data class PresetDescriptor(
    val id: String,
    val label: String,
    val description: String,
    val taskIds: List<String>,
)

@Serializable
data class ResourceDescriptor(
    val id: String,
    val label: String,
    val description: String = "",
    val iconPath: String = "",
    val paths: List<String> = emptyList(),
    val controllers: List<String> = emptyList(),
    val options: List<TaskOptionDescriptor> = emptyList(),
)

@Serializable
data class CatalogSnapshot(
    val tasks: List<TaskDescriptor> = emptyList(),
    val presets: List<PresetDescriptor> = emptyList(),
    val resources: List<ResourceDescriptor> = emptyList(),
    val globalOptions: List<TaskOptionDescriptor> = emptyList(),
)

@Serializable
data class RunRequest(
    val taskId: String? = null,
    val presetId: String? = null,
    val sequenceTaskIds: List<String> = emptyList(),
    val resourceName: String = "官服",
    val logLevel: String = "info",
    val optionOverridesJson: String? = null,
    val optionOverridesByTask: Map<String, String> = emptyMap(),
)

@Serializable
enum class RunSessionPhase {
    Idle,
    Preparing,
    Running,
    Stopping,
    Completed,
    Failed,
}

@Serializable
data class FailureArtifact(
    val taskId: String? = null,
    val screenshotPath: String? = null,
    val diagnosticsPath: String? = null,
    val occurredAt: Long = 0L,
)

@Serializable
data class RuntimeCapabilities(
    val hasBundledGoService: Boolean = false,
    val hasBundledMaaFramework: Boolean = false,
    val canLaunchWindowedGame: Boolean = true,
)

@Serializable
data class RuntimeStateSnapshot(
    val phase: RunSessionPhase = RunSessionPhase.Idle,
    val runtimePrepared: Boolean = false,
    val runtimeRoot: String? = null,
    val displayPowerOffActive: Boolean = false,
    val currentTaskId: String? = null,
    val lastMessage: String = "",
    val lastPing: String = "",
    val recentLogs: List<String> = emptyList(),
    val capabilities: RuntimeCapabilities = RuntimeCapabilities(),
    val lastFailure: FailureArtifact? = null,
    val lastDiagnosticsPath: String? = null,
)

@Serializable
data class RuntimeLogChunk(
    val nextOffsetBytes: Long = 0L,
    val reset: Boolean = false,
    val lines: List<String> = emptyList(),
)
