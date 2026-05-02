package com.maaframework.android.model

enum class RunSessionPhaseText {
    English,
    Chinese,
}

object MaaLogLevels {
    const val ERROR = "error"
    const val WARN = "warn"
    const val INFO = "info"
    const val DEBUG = "debug"

    val choices: List<String> = listOf(ERROR, WARN, INFO, DEBUG)

    fun normalize(value: String?): String {
        return value?.lowercase()?.takeIf { it in choices } ?: INFO
    }
}

fun RunSessionPhase.displayName(text: RunSessionPhaseText = RunSessionPhaseText.English): String {
    return when (text) {
        RunSessionPhaseText.English -> when (this) {
            RunSessionPhase.Idle -> "Idle"
            RunSessionPhase.Preparing -> "Preparing"
            RunSessionPhase.Running -> "Running"
            RunSessionPhase.Stopping -> "Stopping"
            RunSessionPhase.Completed -> "Completed"
            RunSessionPhase.Failed -> "Failed"
        }

        RunSessionPhaseText.Chinese -> when (this) {
            RunSessionPhase.Idle -> "空闲"
            RunSessionPhase.Preparing -> "准备中"
            RunSessionPhase.Running -> "运行中"
            RunSessionPhase.Stopping -> "停止中"
            RunSessionPhase.Completed -> "已完成"
            RunSessionPhase.Failed -> "失败"
        }
    }
}

fun RuntimeStateSnapshot.canStopRun(): Boolean {
    return phase in setOf(
        RunSessionPhase.Preparing,
        RunSessionPhase.Running,
        RunSessionPhase.Stopping,
    )
}
