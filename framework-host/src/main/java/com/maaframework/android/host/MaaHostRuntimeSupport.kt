package com.maaframework.android.host

import com.maaframework.android.model.MaaLogLevels
import com.maaframework.android.model.RunRequest
import com.maaframework.android.model.TaskDescriptor

object MaaHostRunRequestFactory {
    fun sequence(
        tasks: List<TaskDescriptor>,
        resourceId: String?,
        logLevel: String,
        overridesByTask: Map<String, String> = emptyMap(),
        policy: MaaHostPolicy = MaaHostPolicy(),
        availableTasks: List<TaskDescriptor> = tasks,
    ): RunRequest? {
        val sequence = policy.buildSequence(tasks, availableTasks)
        val first = sequence.firstOrNull() ?: return null
        return RunRequest(
            taskId = first.id,
            sequenceTaskIds = sequence.map { it.id },
            resourceName = resourceId ?: "官服",
            logLevel = MaaLogLevels.normalize(logLevel),
            optionOverridesJson = overridesByTask[first.id],
            optionOverridesByTask = overridesByTask.filterKeys { id -> sequence.any { it.id == id } },
        )
    }
}

enum class MaaHostLogSeverity(val priority: Int) {
    Error(0),
    Warning(1),
    Info(2),
    Debug(3),
}

data class MaaHostLogLine(
    val timestampMillis: Long?,
    val severity: MaaHostLogSeverity,
    val content: String,
)

object MaaHostLogFilter {
    fun parse(raw: String): MaaHostLogLine {
        val trimmed = raw.trim()
        val firstSpace = trimmed.indexOf(' ')
        val timestamp = if (firstSpace > 0) {
            trimmed.substring(0, firstSpace).takeIf { value -> value.all(Char::isDigit) }
        } else {
            null
        }
        val content = if (timestamp != null) trimmed.substring(firstSpace + 1).trim() else trimmed
        return MaaHostLogLine(
            timestampMillis = timestamp?.toLongOrNull(),
            severity = classify(content),
            content = content,
        )
    }

    fun visibleLines(
        lines: List<String>,
        selectedLogLevel: String,
        query: String = "",
    ): List<MaaHostLogLine> {
        val maximumPriority = when (MaaLogLevels.normalize(selectedLogLevel)) {
            MaaLogLevels.ERROR -> MaaHostLogSeverity.Error.priority
            MaaLogLevels.WARN -> MaaHostLogSeverity.Warning.priority
            MaaLogLevels.DEBUG -> MaaHostLogSeverity.Debug.priority
            else -> MaaHostLogSeverity.Info.priority
        }
        return lines.asSequence()
            .map(::parse)
            .filter { it.severity.priority <= maximumPriority }
            .filter { query.isBlank() || it.content.contains(query, ignoreCase = true) }
            .toList()
    }

    fun visibleContents(lines: List<String>, selectedLogLevel: String, query: String = ""): List<String> {
        return visibleLines(lines, selectedLogLevel, query).map { it.content }
    }

    private fun classify(message: String): MaaHostLogSeverity {
        val lower = message.lowercase()
        return when {
            listOf("failed", "error", "exception", "fatal").any(lower::contains) || "失败" in message ->
                MaaHostLogSeverity.Error
            listOf("warning", "warn", "timeout").any(lower::contains) || "提醒" in message ->
                MaaHostLogSeverity.Warning
            listOf("success", "completed", "succeeded", "running", "preparing", "starting", "started")
                .any(lower::contains) || listOf("完成", "成功", "准备", "运行", "同步").any(message::contains) ->
                MaaHostLogSeverity.Info
            else -> MaaHostLogSeverity.Debug
        }
    }
}
