@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maaframework.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maaframework.android.catalog.TaskOptionSupport
import com.maaframework.android.host.MaaHostActions
import com.maaframework.android.host.MaaHostLogFilter
import com.maaframework.android.host.MaaHostOptionScopes
import com.maaframework.android.host.MaaHostState
import com.maaframework.android.host.MaaHostTab
import com.maaframework.android.host.MaaHostUiConfig
import com.maaframework.android.model.MaaLogLevels
import com.maaframework.android.model.RunSessionPhaseText
import com.maaframework.android.model.TaskDescriptor
import com.maaframework.android.model.TaskOptionDescriptor
import com.maaframework.android.model.canStopRun
import com.maaframework.android.model.canToggleDisplayPower
import com.maaframework.android.model.displayName

/** Complete reusable four-tab Android host shell used by framework consumers. */
@Composable
fun MaaHostApp(
    state: MaaHostState,
    actions: MaaHostActions,
    config: MaaHostUiConfig,
    modifier: Modifier = Modifier,
) {
    val visibleTasks = remember(state.catalog.tasks, state.selectedResourceId, config.policy) {
        config.policy.orderedTasks(config.policy.visibleTasks(state.catalog.tasks, state.selectedResourceId))
    }
    val selectedTask = visibleTasks.firstOrNull { it.id == state.selectedTaskId }
    var fullscreenPreview by rememberSaveable { mutableStateOf(false) }
    val previewContent = remember(actions.setPreviewSurface) {
        movableContentOf {
            MaaPreviewSurfaceHost(
                modifier = Modifier.fillMaxSize(),
                onPreviewSurfaceChanged = actions.setPreviewSurface,
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                MaaHostBottomBar(
                    activeTab = state.activeTab,
                    onTabSelected = actions.selectTab,
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = MaaUiDefaults.Spacing.sm, vertical = MaaUiDefaults.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(MaaUiDefaults.Spacing.sm),
            ) {
                if (state.activeTab != MaaHostTab.Tasks) {
                    MaaHostHeader(
                        appName = config.appName,
                        title = state.activeTab.title(),
                        subtitle = state.activeTab.subtitle(state, visibleTasks),
                        connected = state.rootConnected,
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    when (state.activeTab) {
                        MaaHostTab.Home -> MaaHostHomeScreen(state, visibleTasks, actions, config)
                        MaaHostTab.Tasks -> MaaHostTasksScreen(
                            state = state,
                            tasks = visibleTasks,
                            selectedTask = selectedTask,
                            actions = actions,
                            config = config,
                            fullscreenPreview = fullscreenPreview,
                            onExpandPreview = { fullscreenPreview = true },
                            previewContent = previewContent,
                        )
                        MaaHostTab.Settings -> MaaHostSettingsScreen(state, actions, config)
                        MaaHostTab.Logs -> MaaHostLogsScreen(state)
                    }
                }
            }
        }

        if (fullscreenPreview) {
            MaaFullscreenPreviewOverlay(
                previewContent = previewContent,
                onDismissRequest = { fullscreenPreview = false },
                onPreviewTouchDown = actions.onPreviewTouchDown,
                onPreviewTouchMove = actions.onPreviewTouchMove,
                onPreviewTouchUp = actions.onPreviewTouchUp,
            )
        }

        if (state.resourceRepositoryClearConfirmVisible) {
            AlertDialog(
                onDismissRequest = actions.dismissClearResourceRepositoryConfirmation,
                title = { Text("清除本地资源？") },
                text = { Text("下次使用前需要重新从 GitHub 同步项目资源。") },
                confirmButton = {
                    TextButton(onClick = actions.clearResourceRepository) { Text("清除") }
                },
                dismissButton = {
                    TextButton(onClick = actions.dismissClearResourceRepositoryConfirmation) { Text("取消") }
                },
            )
        }
    }
}

@Composable
private fun MaaHostHeader(
    appName: String,
    title: String,
    subtitle: String,
    connected: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaaUiDefaults.Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            MaaStatusPill(text = if (connected) "Runtime 已连接" else "等待连接", active = connected)
        }
        if (subtitle.isNotBlank()) {
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MaaHostHomeScreen(
    state: MaaHostState,
    visibleTasks: List<TaskDescriptor>,
    actions: MaaHostActions,
    config: MaaHostUiConfig,
) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val repositoryReady = state.resourceRepository.available
    MaaHomePanel(
        overview = listOf(
            MaaHomeInfo("屏幕分辨率", "${metrics.widthPixels} × ${metrics.heightPixels}"),
            MaaHomeInfo(
                "接口资源",
                "${visibleTasks.size} 个任务 / ${state.catalog.presets.size} 个预设",
                if (visibleTasks.isEmpty()) MaaHomeTone.Error else MaaHomeTone.Neutral,
            ),
            MaaHomeInfo("运行阶段", state.runtimeState.phase.displayName(RunSessionPhaseText.Chinese)),
        ),
        service = MaaHomeService(
            label = "Runtime 服务",
            value = when {
                state.rootConnected -> "已连接"
                state.busy -> "连接中"
                state.rootReport.granted -> "已授权，待握手"
                state.rootReport.available -> "等待授权"
                else -> "未检测到 Root"
            },
            tone = when {
                state.rootConnected -> MaaHomeTone.Positive
                state.rootReport.available -> MaaHomeTone.Warning
                else -> MaaHomeTone.Error
            },
            loading = state.busy && !state.rootConnected,
        ),
        statuses = listOf(
            MaaHomeStatus("Root 可用", state.rootReport.available),
            MaaHomeStatus("授权通过", state.rootReport.granted),
            MaaHomeStatus("服务在线", state.rootConnected),
        ),
        actions = buildList {
            add(MaaHomeAction("准备运行时", if (state.runtimeState.runtimePrepared) "已就绪" else "执行", state.rootConnected && repositoryReady && !state.busy, onClick = actions.prepareRuntime))
            add(MaaHomeAction("打开游戏", "打开", state.rootConnected && !state.busy, onClick = actions.startWindowedGame))
            if (config.showPresetRun && state.catalog.presets.isNotEmpty()) {
                add(MaaHomeAction("运行所选预设", "运行", state.rootConnected && state.selectedPresetId != null && !state.busy, onClick = actions.startSelectedPreset))
            }
            add(MaaHomeAction(if (state.rootConnected) "重新连接 Runtime" else "连接 Root / Runtime", "连接", !state.busy, onClick = actions.requestRootAndConnect))
        },
        resourceContent = {
            MaaHomeRepositoryPanel(
                summary = repositorySummary(state),
                rootPath = state.resourceRepository.rootPath,
                error = state.resourceRepository.lastError,
                progress = state.resourceRepositoryProgress?.let { MaaHomeProgress(it.fraction, it.label) },
                action = MaaHomeAction(
                    title = "同步项目资源",
                    actionLabel = if (state.resourceRepositoryUpdating) "同步中" else "同步",
                    enabled = state.manifest.hasGitHubResourceRepository() && !state.resourceRepositoryUpdating,
                    onClick = actions.refreshResourceRepository,
                ),
                clearAction = MaaHomeAction(
                    title = "清除本地资源",
                    actionLabel = "清除",
                    enabled = state.manifest.hasGitHubResourceRepository() && !state.resourceRepositoryUpdating,
                    onClick = actions.requestClearResourceRepositoryConfirmation,
                ),
            ) {
                MaaHomeDivider()
                MaaHomeResourcePresetPanel(
                    resources = state.catalog.resources,
                    selectedResourceId = state.selectedResourceId,
                    onSelectResource = actions.selectResource,
                    presets = state.catalog.presets,
                    selectedPresetId = state.selectedPresetId,
                    onSelectPreset = actions.selectPreset,
                )
                if (config.showSharedOptions) {
                    MaaHostSharedOptions(state, actions)
                }
            }
        },
    )
}

@Composable
private fun MaaHostSharedOptions(state: MaaHostState, actions: MaaHostActions) {
    val resource = state.catalog.resources.firstOrNull { it.id == state.selectedResourceId }
    val globalOptions = TaskOptionSupport.filterOptionsForResource(state.catalog.globalOptions, state.selectedResourceId)
    val resourceOptions = TaskOptionSupport.filterOptionsForResource(resource?.options.orEmpty(), state.selectedResourceId)
    MaaHostSharedOptionScope(
        title = "全局选项",
        scopeId = MaaHostOptionScopes.GLOBAL,
        options = globalOptions,
        state = state,
        actions = actions,
    )
    resource?.let {
        MaaHostSharedOptionScope(
            title = "${it.label} 资源选项",
            scopeId = MaaHostOptionScopes.resource(it.id),
            options = resourceOptions,
            state = state,
            actions = actions,
        )
    }
}

@Composable
private fun MaaHostSharedOptionScope(
    title: String,
    scopeId: String,
    options: List<TaskOptionDescriptor>,
    state: MaaHostState,
    actions: MaaHostActions,
) {
    if (options.isEmpty()) return
    val selected = state.sharedOptionSelectionsByScope[scopeId].orEmpty()
    val inputs = state.sharedInputValuesByScope[scopeId].orEmpty()
    MaaHomeDivider()
    MaaTaskOptionsForm(
        ownerId = scopeId,
        title = title,
        description = "",
        options = options,
        selectedCaseNamesByOption = selected,
        inputValuesByOption = inputs,
        inputErrorsByOption = TaskOptionSupport.collectInputValidationErrors(options, selected, inputs),
        onSwitchOption = actions.updateSharedSwitchOption,
        onToggleCheckboxOption = actions.toggleSharedCheckboxOption,
        onInputValueChange = actions.updateSharedInputValue,
    )
}

@Composable
private fun MaaHostTasksScreen(
    state: MaaHostState,
    tasks: List<TaskDescriptor>,
    selectedTask: TaskDescriptor?,
    actions: MaaHostActions,
    config: MaaHostUiConfig,
    fullscreenPreview: Boolean,
    onExpandPreview: () -> Unit,
    previewContent: @Composable () -> Unit,
) {
    val options = TaskOptionSupport.filterOptionsForResource(selectedTask?.options.orEmpty(), state.selectedResourceId)
    val selections = selectedTask?.id?.let { state.taskOptionSelectionsByTask[it] }.orEmpty()
    val inputs = selectedTask?.id?.let { state.taskInputValuesByTask[it] }.orEmpty()
    val errors = TaskOptionSupport.collectInputValidationErrors(options, selections, inputs)

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(MaaUiDefaults.Spacing.sm),
    ) {
        MaaTaskListPanel(
            tasks = tasks,
            selectedTaskId = state.selectedTaskId,
            checkedTaskIds = state.checkedTaskIds,
            onSelectTask = actions.selectTask,
            onToggleTaskChecked = actions.toggleTaskChecked,
            runningTaskId = state.runtimeState.currentTaskId,
            pinnedTaskIds = config.policy.pinnedTaskIds,
            taskLabel = config.policy::taskLabel,
            modifier = Modifier.fillMaxHeight(),
        )
        Column(
            modifier = Modifier.fillMaxHeight().weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaaUiDefaults.Spacing.sm),
        ) {
            MaaPreviewPanel(
                isFullscreenPreview = fullscreenPreview,
                onExpandPreview = onExpandPreview,
                overlayText = state.runtimeState.currentTaskId,
                previewContent = previewContent,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaaUiDefaults.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(MaaUiDefaults.Spacing.xs),
            ) {
                Button(
                    onClick = actions.startSelectedTask,
                    enabled = state.rootConnected && !state.busy && tasks.isNotEmpty() && errors.isEmpty(),
                ) { Text("运行已勾选任务") }
                OutlinedButton(
                    onClick = actions.stopRun,
                    enabled = state.runtimeState.canStopRun(),
                ) { Text("停止") }
                OutlinedButton(
                    onClick = actions.toggleDisplayPower,
                    enabled = state.runtimeState.canToggleDisplayPower(state.rootConnected),
                ) { Text(if (state.runtimeState.displayPowerOffActive) "点亮屏幕" else "关闭屏幕") }
            }
            MaaTaskDetailPanel(
                task = selectedTask,
                options = options,
                selectedCaseNamesByOption = selections,
                inputValuesByOption = inputs,
                inputErrorsByOption = errors,
                onSwitchOption = actions.updateTaskSwitchOption,
                onToggleCheckboxOption = actions.toggleTaskCheckboxOption,
                onInputValueChange = actions.updateTaskInputValue,
                modifier = Modifier.weight(1f),
                extraContent = {
                    if (config.showManualOverride) {
                        OutlinedTextField(
                            value = state.overrideJson,
                            onValueChange = actions.updateOverrideJson,
                            label = { Text("手动 Pipeline Override JSON") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun MaaHostSettingsScreen(
    state: MaaHostState,
    actions: MaaHostActions,
    config: MaaHostUiConfig,
) {
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.use(actions.exportConfig) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { context.contentResolver.openInputStream(it)?.use(actions.importConfig) }
    }
    MaaSettingsPanel {
        MaaSettingsSection("运行设置") {
            MaaSettingsChoiceRow(
                title = "日志级别",
                options = listOf(
                    MaaSettingsChoice(MaaLogLevels.ERROR, "错误"),
                    MaaSettingsChoice(MaaLogLevels.WARN, "警告"),
                    MaaSettingsChoice(MaaLogLevels.INFO, "信息"),
                    MaaSettingsChoice(MaaLogLevels.DEBUG, "调试"),
                ),
                selected = state.logLevel,
                onSelected = actions.updateLogLevel,
            )
        }
        MaaSettingsSection("资源与 Runtime") {
            MaaHomeInfoRow("资源状态", repositorySummary(state))
            state.servicePing.takeIf { it.isNotBlank() }?.let {
                MaaHomeDivider()
                MaaHomeInfoRow("服务响应", it)
            }
            MaaHomeDivider()
            MaaHomeActionRow(MaaHomeAction("同步项目资源", "同步", state.manifest.hasGitHubResourceRepository() && !state.resourceRepositoryUpdating, onClick = actions.refreshResourceRepository))
            MaaHomeDivider()
            MaaHomeActionRow(MaaHomeAction("导出 Runtime 诊断", "导出", state.rootConnected, onClick = actions.exportDiagnostics))
        }
        MaaSettingsSection("配置备份") {
            MaaHomeActionRow(MaaHomeAction("导出配置 v2", "导出", onClick = { exportLauncher.launch("${state.manifest.projectId}-settings-v2.json") }))
            MaaHomeDivider()
            MaaHomeActionRow(MaaHomeAction("导入配置", "导入", onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }))
        }
        MaaSettingsSection("关于") {
            MaaHomeInfoRow("应用", config.appName)
            if (config.versionName.isNotBlank()) {
                MaaHomeDivider()
                MaaHomeInfoRow("版本", config.versionName)
            }
            MaaHomeDivider()
            MaaHomeInfoRow("项目 ID", state.manifest.projectId)
        }
    }
}

@Composable
private fun MaaHostLogsScreen(state: MaaHostState) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(state.displayLogs, state.logLevel, query) {
        MaaHostLogFilter.visibleContents(state.displayLogs, state.logLevel, query)
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(MaaUiDefaults.Spacing.sm),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("筛选日志") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        MaaRuntimeLogsPanel(
            lines = visible,
            subtitle = state.lastMessage,
            metrics = listOf(
                MaaLogMetric("可见", visible.size.toString()),
                MaaLogMetric("全部", state.displayLogs.size.toString()),
                MaaLogMetric("阶段", state.runtimeState.phase.displayName(RunSessionPhaseText.Chinese)),
            ),
            detailLines = listOfNotNull(
                state.runtimeState.currentTaskId?.let { "当前任务：$it" },
                state.runtimeState.lastMessage.takeIf { it.isNotBlank() },
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MaaHostBottomBar(activeTab: MaaHostTab, onTabSelected: (MaaHostTab) -> Unit) {
    NavigationBar {
        MaaHostTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == activeTab,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        imageVector = when (tab) {
                            MaaHostTab.Home -> Icons.Default.Home
                            MaaHostTab.Tasks -> Icons.AutoMirrored.Filled.ViewList
                            MaaHostTab.Settings -> Icons.Default.Settings
                            MaaHostTab.Logs -> Icons.AutoMirrored.Filled.Article
                        },
                        contentDescription = tab.label(),
                    )
                },
                label = { Text(tab.label(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

private fun MaaHostTab.label(): String = when (this) {
    MaaHostTab.Home -> "主页"
    MaaHostTab.Tasks -> "任务"
    MaaHostTab.Settings -> "设置"
    MaaHostTab.Logs -> "日志"
}

private fun MaaHostTab.title(): String = label()

private fun MaaHostTab.subtitle(state: MaaHostState, visibleTasks: List<TaskDescriptor>): String = when (this) {
    MaaHostTab.Home -> state.lastMessage.ifBlank { "Root-only Android 宿主已就绪。" }
    MaaHostTab.Tasks -> "${visibleTasks.size} 个可用任务，勾选与当前焦点相互独立。"
    MaaHostTab.Settings -> "资源更新、日志级别与配置导入导出。"
    MaaHostTab.Logs -> "当前共 ${state.displayLogs.size} 条原始日志。"
}

private fun repositorySummary(state: MaaHostState): String = when {
    state.resourceRepositoryUpdating -> state.resourceRepositoryProgress?.label ?: "正在同步"
    state.resourceRepository.source == "bundled" && state.resourceRepository.available -> "APK 内置资源（随应用更新）"
    state.resourceRepository.available -> "已就绪"
    !state.resourceRepository.lastError.isNullOrBlank() -> "同步失败"
    else -> "尚未下载"
}
