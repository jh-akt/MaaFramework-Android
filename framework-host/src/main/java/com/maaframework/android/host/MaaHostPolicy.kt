package com.maaframework.android.host

import com.maaframework.android.model.TaskDescriptor

data class MaaHostPolicy(
    val prependTaskIds: List<String> = emptyList(),
    val pinnedTaskIds: Set<String> = emptySet(),
    val taskLabels: Map<String, String> = emptyMap(),
) {
    fun visibleTasks(tasks: List<TaskDescriptor>, resourceId: String?): List<TaskDescriptor> {
        return tasks.filter { task ->
            task.supportedResources.isEmpty() || resourceId.isNullOrBlank() || resourceId in task.supportedResources
        }
    }

    fun orderedTasks(tasks: List<TaskDescriptor>): List<TaskDescriptor> {
        if (pinnedTaskIds.isEmpty()) return tasks
        val order = pinnedTaskIds.withIndex().associate { it.value to it.index }
        return tasks.sortedWith(
            compareBy<TaskDescriptor> { order[it.id] ?: Int.MAX_VALUE }
                .thenBy { tasks.indexOf(it) },
        )
    }

    fun buildSequence(
        requested: List<TaskDescriptor>,
        available: List<TaskDescriptor> = requested,
    ): List<TaskDescriptor> {
        if (requested.isEmpty()) return emptyList()
        val requestedIds = requested.mapTo(linkedSetOf()) { it.id }
        val prefix = prependTaskIds.mapNotNull { id ->
            requested.firstOrNull { it.id == id } ?: available.firstOrNull { it.id == id }
        }
        return (prefix + requested)
            .distinctBy { it.id }
            .filter { it.id in requestedIds || it.id in prependTaskIds }
    }

    fun taskLabel(task: TaskDescriptor): String {
        return taskLabels[task.id] ?: task.label
            .replace(Regex("[^\\p{L}\\p{N}\\p{IsHan}]"), "")
            .take(4)
            .ifBlank { task.id.take(4) }
    }
}

object MaaHostPolicies {
    val Default = MaaHostPolicy()

    val MaaEnd = MaaHostPolicy(
        prependTaskIds = listOf("AndroidOpenGame"),
        pinnedTaskIds = linkedSetOf(
            "AndroidOpenGame",
            "DijiangRewards",
            "CreditShoppingN2",
            "VisitFriends",
            "SellProduct",
            "DeliveryJobs",
            "DailyRewards",
        ),
        taskLabels = mapOf(
            "AndroidOpenGame" to "打开游戏",
            "DailyRewards" to "日常奖励",
            "DijiangRewards" to "基建任务",
            "CreditShoppingN2" to "信用购物",
            "VisitFriends" to "拜访好友",
            "SellProduct" to "售卖产品",
            "AutoEssence" to "基质刷取",
            "EnvironmentMonitoring" to "环境监测",
            "Crafting" to "工艺制造",
            "WeaponUpgrade" to "武器强化",
            "AutoUseSpMedication" to "体力用药",
            "SimpleProductionBatchStart" to "批量生产",
            "ReceiveProdManual" to "领取手册",
            "BakerEntry" to "会话嘴替",
            "ReadAllWiki" to "阅读图鉴",
            "DeliveryJobs" to "转交委托",
            "GearAssembly" to "装备制造",
        ),
    )
}

object MaaHostOptionScopes {
    const val GLOBAL = "__global__"

    fun resource(resourceId: String): String = "__resource__:$resourceId"
}
