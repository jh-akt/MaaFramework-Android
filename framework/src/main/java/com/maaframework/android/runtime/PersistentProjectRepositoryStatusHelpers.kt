package com.maaframework.android.runtime

fun PersistentProjectRepositoryStatus.summaryText(
    repositoryLabel: String? = null,
    branchLabel: String? = branch,
    updating: Boolean = false,
    syncingText: String = "同步中",
    readyText: String = "已就绪",
    notDownloadedText: String = "尚未下载",
    failedText: String = "更新失败",
    unconfiguredText: String = "未配置",
): String {
    val repoText = repositoryLabel?.takeIf { it.isNotBlank() }
        ?: owner?.takeIf { it.isNotBlank() }?.let { ownerName ->
            repo?.takeIf { it.isNotBlank() }?.let { repoName -> "$ownerName/$repoName" }
        }
        ?: source.takeIf { it.isNotBlank() }
        ?: unconfiguredText
    val branchText = branchLabel?.takeIf { it.isNotBlank() } ?: branch ?: "main"
    val stateText = when {
        updating -> syncingText
        available -> readyText
        lastError.isNullOrBlank() -> notDownloadedText
        else -> failedText
    }
    return "$repoText / $branchText / $stateText"
}
