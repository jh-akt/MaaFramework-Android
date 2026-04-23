@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maaframework.android.sample.bbb

import android.graphics.PixelFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maaframework.android.model.RootBinaryProbe
import com.maaframework.android.model.RunSessionPhase
import com.maaframework.android.model.TaskDescriptor
import com.maaframework.android.preview.DefaultDisplayConfig

@Composable
fun MaaBbbSampleScreen(
    viewModel: MainViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val visibleTasks = remember(state.catalog.tasks, state.selectedResourceId) {
        MainViewModel.visibleTasks(state.catalog.tasks, state.selectedResourceId)
    }
    val selectedTask = remember(visibleTasks, state.selectedTaskId) {
        visibleTasks.firstOrNull { it.id == state.selectedTaskId }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            ),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                AppBottomBar(
                    activeTab = state.activeTab,
                    onTabSelected = viewModel::selectTab,
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(
                        horizontal = MaaBbbDesignTokens.Spacing.sm,
                        vertical = MaaBbbDesignTokens.Spacing.sm,
                    ),
                verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
            ) {
                AppHeader(
                    projectName = state.manifest.displayName,
                    title = state.activeTab.title(),
                    subtitle = state.activeTab.subtitle(state, visibleTasks),
                    connected = state.rootConnected,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    when (state.activeTab) {
                        MaaBbbTab.Home -> HomeScreen(
                            state = state,
                            visibleTasks = visibleTasks,
                            selectedTask = selectedTask,
                            onSelectResource = viewModel::selectResource,
                            onSelectPreset = viewModel::selectPreset,
                            onConnect = viewModel::requestRootAndConnect,
                            onPrepare = viewModel::prepareRuntime,
                            onOpenGame = viewModel::startWindowedGame,
                            onRunTask = viewModel::startSelectedTask,
                            onRunPreset = viewModel::startSelectedPreset,
                            onStop = viewModel::stopRun,
                            onExport = viewModel::exportDiagnostics,
                            onToggleDisplayPower = viewModel::toggleDisplayPower,
                        )

                        MaaBbbTab.Tasks -> TasksScreen(
                            state = state,
                            tasks = visibleTasks,
                            selectedTask = selectedTask,
                            onSelectTask = viewModel::selectTask,
                            onRunTask = viewModel::startSelectedTask,
                            onOpenGame = viewModel::startWindowedGame,
                            onStop = viewModel::stopRun,
                            onToggleDisplayPower = viewModel::toggleDisplayPower,
                            onOverrideJsonChange = viewModel::updateOverrideJson,
                            onPreviewSurfaceChanged = viewModel::setPreviewSurface,
                        )

                        MaaBbbTab.Logs -> LogsScreen(state = state)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(
    projectName: String,
    title: String,
    subtitle: String,
    connected: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = projectName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            StatusPill(
                text = if (connected) "Runtime 已连接" else "等待连接",
                active = connected,
            )
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeScreen(
    state: MainUiState,
    visibleTasks: List<TaskDescriptor>,
    selectedTask: TaskDescriptor?,
    onSelectResource: (String) -> Unit,
    onSelectPreset: (String) -> Unit,
    onConnect: () -> Unit,
    onPrepare: () -> Unit,
    onOpenGame: () -> Unit,
    onRunTask: () -> Unit,
    onRunPreset: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
    onToggleDisplayPower: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = MaaBbbDesignTokens.Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
    ) {
        item {
            StatusOverviewCard(
                state = state,
                visibleTasks = visibleTasks,
            )
        }

        item {
            QuickActionsCard(
                state = state,
                onConnect = onConnect,
                onPrepare = onPrepare,
                onOpenGame = onOpenGame,
                onRunTask = onRunTask,
                onRunPreset = onRunPreset,
                onStop = onStop,
                onExport = onExport,
                onToggleDisplayPower = onToggleDisplayPower,
            )
        }

        item {
            ResourcePresetCard(
                state = state,
                onSelectResource = onSelectResource,
                onSelectPreset = onSelectPreset,
            )
        }

        selectedTask?.let { task ->
            item {
                SelectedTaskCard(task = task)
            }
        }

        item {
            HostDiagnosticsCard(state = state)
        }
    }
}

@Composable
private fun StatusOverviewCard(
    state: MainUiState,
    visibleTasks: List<TaskDescriptor>,
) {
    SectionCard(
        title = "运行状态",
        subtitle = state.lastMessage.ifBlank { "Root runtime ready" },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
        ) {
            StatusPill("Root 可用", state.rootReport.available)
            StatusPill("授权通过", state.rootReport.granted)
            StatusPill("服务在线", state.rootConnected)
            if (state.busy) {
                StatusPill(
                    text = "处理中",
                    active = true,
                    accent = MaterialTheme.colorScheme.secondaryContainer,
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
        ) {
            MetricTile(label = "任务数", value = visibleTasks.size.toString())
            MetricTile(label = "预设数", value = state.catalog.presets.size.toString())
            MetricTile(label = "阶段", value = state.runtimeState.phase.displayName())
            MetricTile(label = "资源", value = state.selectedResourceId ?: "-")
        }

        if (state.servicePing.isNotBlank()) {
            Text(
                text = state.servicePing,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickActionsCard(
    state: MainUiState,
    onConnect: () -> Unit,
    onPrepare: () -> Unit,
    onOpenGame: () -> Unit,
    onRunTask: () -> Unit,
    onRunPreset: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
    onToggleDisplayPower: () -> Unit,
) {
    SectionCard(
        title = "快捷操作",
        subtitle = "页面结构参考 MaaEnd，但动作仍然走当前 MaaFrameworkSession 运行链路",
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
        ) {
            ActionButton(
                label = if (state.rootConnected) "重新连接 Runtime" else "连接 Root / Runtime",
                enabled = !state.busy,
                onClick = onConnect,
            )
            ActionButton(
                label = "准备运行时",
                enabled = state.rootConnected && !state.busy,
                outlined = true,
                onClick = onPrepare,
            )
            ActionButton(
                label = "窗口打开游戏",
                enabled = state.rootConnected && !state.busy,
                onClick = onOpenGame,
            )
            ActionButton(
                label = "开始任务",
                enabled = state.rootConnected && !state.busy && state.selectedTaskId != null,
                onClick = onRunTask,
            )
            ActionButton(
                label = "运行预设",
                enabled = state.rootConnected && !state.busy && state.selectedPresetId != null,
                outlined = true,
                onClick = onRunPreset,
            )
            ActionButton(
                label = if (state.runtimeState.displayPowerOffActive) "恢复亮屏" else "息屏挂机",
                enabled = state.rootConnected && (
                    state.runtimeState.phase in setOf(
                        RunSessionPhase.Preparing,
                        RunSessionPhase.Running,
                        RunSessionPhase.Stopping,
                    ) || state.runtimeState.displayPowerOffActive
                ),
                outlined = true,
                onClick = onToggleDisplayPower,
            )
            ActionButton(
                label = "停止任务",
                enabled = state.rootConnected && canStopRun(state.runtimeState),
                outlined = true,
                onClick = onStop,
            )
            ActionButton(
                label = "导出诊断包",
                enabled = state.rootConnected && !state.busy,
                outlined = true,
                onClick = onExport,
            )
        }
    }
}

@Composable
private fun ResourcePresetCard(
    state: MainUiState,
    onSelectResource: (String) -> Unit,
    onSelectPreset: (String) -> Unit,
) {
    SectionCard(
        title = "资源与预设",
        subtitle = "保留样例宿主职责，同时把 MaaEnd 的总览分区形式同步过来",
    ) {
        Text(
            text = "资源包",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
        ) {
            state.catalog.resources.forEach { resource ->
                FilterChip(
                    selected = resource.id == state.selectedResourceId,
                    onClick = { onSelectResource(resource.id) },
                    label = { Text(resource.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }

        Text(
            text = "预设",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.catalog.presets.isEmpty()) {
            Text(
                text = "当前项目没有暴露可用预设。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
            ) {
                state.catalog.presets.forEach { preset ->
                    FilterChip(
                        selected = preset.id == state.selectedPresetId,
                        onClick = { onSelectPreset(preset.id) },
                        label = {
                            Text(
                                text = "${preset.label} (${preset.taskIds.size})",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedTaskCard(task: TaskDescriptor) {
    SectionCard(
        title = "当前选择",
        subtitle = task.entry,
    ) {
        Text(
            text = task.label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        if (task.description.isNotBlank()) {
            Text(
                text = task.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
        ) {
            if (task.repeatable) {
                StatusPill(text = "可重复 ${task.repeatCount} 次", active = true)
            }
            task.supportedResources.take(4).forEach { resourceId ->
                StatusPill(text = resourceId, active = false)
            }
        }
    }
}

@Composable
private fun HostDiagnosticsCard(
    state: MainUiState,
) {
    SectionCard(
        title = "Host Diagnostics",
        subtitle = state.rootReport.summary.ifBlank { "No host diagnostics" },
    ) {
        if (state.rootReport.binaryProbes.isEmpty()) {
            Text(
                text = "No root binary probes were reported.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.xs),
            ) {
                state.rootReport.binaryProbes
                    .filter { probe -> probe.exists || probe.executableByApp }
                    .ifEmpty { state.rootReport.binaryProbes.take(3) }
                    .take(4)
                    .forEach { probe ->
                        RootBinaryProbeLine(probe = probe)
                    }
            }
        }
    }
}

@Composable
private fun RootBinaryProbeLine(probe: RootBinaryProbe) {
    val status = when {
        probe.executableByApp -> "app can execute"
        probe.exists -> "exists but app cannot execute"
        else -> "not found"
    }
    Text(
        text = "${probe.path} · $status",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TasksScreen(
    state: MainUiState,
    tasks: List<TaskDescriptor>,
    selectedTask: TaskDescriptor?,
    onSelectTask: (String) -> Unit,
    onRunTask: () -> Unit,
    onOpenGame: () -> Unit,
    onStop: () -> Unit,
    onToggleDisplayPower: () -> Unit,
    onOverrideJsonChange: (String) -> Unit,
    onPreviewSurfaceChanged: (Surface?) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
    ) {
        PreviewCard(
            state = state,
            onOpenGame = onOpenGame,
            onPreviewSurfaceChanged = onPreviewSurfaceChanged,
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (maxWidth < 900.dp) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
                ) {
                    TaskListPanel(
                        tasks = tasks,
                        selectedTaskId = state.selectedTaskId,
                        runningTaskId = state.runtimeState.currentTaskId,
                        modifier = Modifier.weight(1f),
                        onSelectTask = onSelectTask,
                    )
                    TaskDetailPanel(
                        state = state,
                        selectedTask = selectedTask,
                        modifier = Modifier.weight(1f),
                        onRunTask = onRunTask,
                        onStop = onStop,
                        onToggleDisplayPower = onToggleDisplayPower,
                        onOverrideJsonChange = onOverrideJsonChange,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
                    verticalAlignment = Alignment.Top,
                ) {
                    TaskListPanel(
                        tasks = tasks,
                        selectedTaskId = state.selectedTaskId,
                        runningTaskId = state.runtimeState.currentTaskId,
                        modifier = Modifier
                            .width(340.dp)
                            .fillMaxHeight(),
                        onSelectTask = onSelectTask,
                    )
                    TaskDetailPanel(
                        state = state,
                        selectedTask = selectedTask,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onRunTask = onRunTask,
                        onStop = onStop,
                        onToggleDisplayPower = onToggleDisplayPower,
                        onOverrideJsonChange = onOverrideJsonChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(
    state: MainUiState,
    onOpenGame: () -> Unit,
    onPreviewSurfaceChanged: (Surface?) -> Unit,
) {
    SectionCard(
        title = "虚拟显示预览",
        subtitle = when {
            !state.rootConnected -> "先连接 root runtime，预览 surface 才能绑定到后台服务。"
            state.runtimeState.phase == RunSessionPhase.Running -> "任务运行中，预览会展示虚拟显示当前画面。"
            else -> "点击“窗口打开游戏”或直接执行任务，预览会接管虚拟显示输出。"
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(MaaBbbDesignTokens.CornerRadius.inner),
                )
                .padding(MaaBbbDesignTokens.Spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(DefaultDisplayConfig.ASPECT_RATIO),
            ) {
                PreviewSurfaceHost(
                    modifier = Modifier.fillMaxSize(),
                    onPreviewSurfaceChanged = onPreviewSurfaceChanged,
                )
                if (!state.rootConnected) {
                    PreviewOverlayHint("等待 Root / Runtime 连接")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
        ) {
            Button(
                onClick = onOpenGame,
                enabled = state.rootConnected && !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Text("窗口打开游戏")
            }
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = if (state.runtimeState.phase == RunSessionPhase.Running) {
                        "任务运行中"
                    } else {
                        "等待画面"
                    },
                )
            }
        }
    }
}

@Composable
private fun PreviewSurfaceHost(
    modifier: Modifier = Modifier,
    onPreviewSurfaceChanged: (Surface?) -> Unit,
) {
    DisposableEffect(Unit) {
        onDispose {
            onPreviewSurfaceChanged(null)
        }
    }

    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                var lastSurface: Surface? = null
                holder.setFormat(PixelFormat.RGBA_8888)
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            holder.setFixedSize(DefaultDisplayConfig.WIDTH, DefaultDisplayConfig.HEIGHT)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            if (
                                width == DefaultDisplayConfig.WIDTH &&
                                height == DefaultDisplayConfig.HEIGHT &&
                                lastSurface !== holder.surface
                            ) {
                                lastSurface = holder.surface
                                onPreviewSurfaceChanged(holder.surface)
                            }
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            if (lastSurface != null) {
                                lastSurface = null
                                onPreviewSurfaceChanged(null)
                            }
                        }
                    },
                )
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun PreviewOverlayHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TaskListPanel(
    tasks: List<TaskDescriptor>,
    selectedTaskId: String?,
    runningTaskId: String?,
    modifier: Modifier = Modifier,
    onSelectTask: (String) -> Unit,
) {
    SectionCard(
        title = "任务列表",
        subtitle = if (tasks.isEmpty()) "当前资源没有可执行任务。" else "选择任务后，可在右侧查看详情和执行参数。",
        modifier = modifier,
        fillHeight = true,
    ) {
        if (tasks.isEmpty()) {
            Text(
                text = "No tasks available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskListItem(
                        task = task,
                        selected = task.id == selectedTaskId,
                        running = task.id == runningTaskId,
                        onClick = { onSelectTask(task.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskListItem(
    task: TaskDescriptor,
    selected: Boolean,
    running: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(MaaBbbDesignTokens.CornerRadius.inner),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
            },
        ),
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(MaaBbbDesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = task.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (running) {
                    StatusPill(text = "Running", active = true)
                }
            }
            Text(
                text = task.entry,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (task.description.isNotBlank()) {
                Text(
                    text = task.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TaskDetailPanel(
    state: MainUiState,
    selectedTask: TaskDescriptor?,
    modifier: Modifier = Modifier,
    onRunTask: () -> Unit,
    onStop: () -> Unit,
    onToggleDisplayPower: () -> Unit,
    onOverrideJsonChange: (String) -> Unit,
) {
    SectionCard(
        title = "任务详情",
        subtitle = selectedTask?.entry ?: "先在左侧选择任务。",
        modifier = modifier,
        fillHeight = true,
    ) {
        if (selectedTask == null) {
            Text(
                text = "还没有选中任务。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.md),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.xs),
            ) {
                Text(
                    text = selectedTask.label,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (selectedTask.description.isNotBlank()) {
                    Text(
                        text = selectedTask.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
            ) {
                if (selectedTask.repeatable) {
                    StatusPill(text = "Repeat ${selectedTask.repeatCount}", active = true)
                }
                selectedTask.controllers.take(3).forEach { controller ->
                    StatusPill(text = controller, active = false)
                }
                selectedTask.supportedResources.take(4).forEach { resourceId ->
                    StatusPill(text = resourceId, active = false)
                }
            }

            OutlinedTextField(
                value = state.overrideJson,
                onValueChange = onOverrideJsonChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = { Text("Override JSON") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
            ) {
                Button(
                    onClick = onRunTask,
                    enabled = state.rootConnected && !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("开始任务")
                }
                OutlinedButton(
                    onClick = onStop,
                    enabled = state.rootConnected && canStopRun(state.runtimeState),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("停止")
                }
                OutlinedButton(
                    onClick = onToggleDisplayPower,
                    enabled = state.rootConnected && (
                        state.runtimeState.phase in setOf(
                            RunSessionPhase.Preparing,
                            RunSessionPhase.Running,
                            RunSessionPhase.Stopping,
                        ) || state.runtimeState.displayPowerOffActive
                    ),
                    modifier = Modifier.widthIn(min = 108.dp),
                ) {
                    Text(
                        text = if (state.runtimeState.displayPowerOffActive) "恢复亮屏" else "息屏挂机",
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogsScreen(
    state: MainUiState,
) {
    SectionCard(
        title = "运行日志",
        subtitle = state.runtimeState.lastMessage.ifBlank { state.lastMessage },
        modifier = Modifier.fillMaxSize(),
        fillHeight = true,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.md),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.sm),
            ) {
                MetricTile(label = "阶段", value = state.runtimeState.phase.displayName())
                MetricTile(label = "当前任务", value = state.runtimeState.currentTaskId ?: "-")
                MetricTile(label = "连接", value = if (state.rootConnected) "Yes" else "No")
            }

            if (!state.runtimeState.lastDiagnosticsPath.isNullOrBlank()) {
                Text(
                    text = "Diagnostics: ${state.runtimeState.lastDiagnosticsPath}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.runtimeState.lastFailure?.screenshotPath?.takeIf { it.isNotBlank() }?.let { screenshotPath ->
                Text(
                    text = "Last failure screenshot: $screenshotPath",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(MaaBbbDesignTokens.CornerRadius.inner),
                    )
                    .padding(MaaBbbDesignTokens.Spacing.md),
            ) {
                Text(
                    text = if (state.displayLogs.isEmpty()) "No logs yet" else state.displayLogs.joinToString("\n"),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun AppBottomBar(
    activeTab: MaaBbbTab,
    onTabSelected: (MaaBbbTab) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(
                horizontal = MaaBbbDesignTokens.Spacing.sm,
                vertical = MaaBbbDesignTokens.Spacing.xs,
            ),
        shape = RoundedCornerShape(MaaBbbDesignTokens.CornerRadius.card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaaBbbDesignTokens.Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.xs),
        ) {
            MaaBbbTab.entries.forEach { tab ->
                val selected = tab == activeTab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(MaaBbbDesignTokens.CornerRadius.inner),
                        )
                        .clickable { onTabSelected(tab) }
                        .padding(vertical = MaaBbbDesignTokens.Spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tab.title(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = if (fillHeight) {
            modifier.fillMaxSize()
        } else {
            modifier.fillMaxWidth()
        },
        shape = RoundedCornerShape(MaaBbbDesignTokens.CornerRadius.card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
    ) {
        Column(
            modifier = if (fillHeight) {
                Modifier
                    .fillMaxSize()
                    .padding(MaaBbbDesignTokens.Spacing.lg)
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(MaaBbbDesignTokens.Spacing.lg)
            },
            verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.md),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(MaaBbbDesignTokens.Spacing.xs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    active: Boolean,
    accent: Color = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
    },
) {
    Box(
        modifier = Modifier
            .background(
                color = accent,
                shape = RoundedCornerShape(MaaBbbDesignTokens.CornerRadius.pill),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
) {
    Card(
        shape = RoundedCornerShape(MaaBbbDesignTokens.CornerRadius.inner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    enabled: Boolean,
    outlined: Boolean = false,
    onClick: () -> Unit,
) {
    if (outlined) {
        OutlinedButton(onClick = onClick, enabled = enabled) {
            Text(label)
        }
    } else {
        Button(onClick = onClick, enabled = enabled) {
            Text(label)
        }
    }
}

private fun MaaBbbTab.title(): String {
    return when (this) {
        MaaBbbTab.Home -> "Home"
        MaaBbbTab.Tasks -> "Tasks"
        MaaBbbTab.Logs -> "Logs"
    }
}

private fun MaaBbbTab.subtitle(
    state: MainUiState,
    visibleTasks: List<TaskDescriptor>,
): String {
    return when (this) {
        MaaBbbTab.Home -> state.lastMessage.ifBlank {
            "资源 ${state.selectedResourceId ?: "-"} · ${visibleTasks.size} 个可见任务"
        }
        MaaBbbTab.Tasks -> selectedTaskSubtitle(state)
        MaaBbbTab.Logs -> state.servicePing.ifBlank { "等待 root runtime 连接后开始拉取日志" }
    }
}

private fun selectedTaskSubtitle(state: MainUiState): String {
    return when {
        state.selectedTaskId != null -> "当前任务 ${state.selectedTaskId} · ${state.runtimeState.phase.displayName()}"
        else -> "选择任务后，可在这里查看预览、说明和高级 override。"
    }
}

private fun RunSessionPhase.displayName(): String {
    return when (this) {
        RunSessionPhase.Idle -> "Idle"
        RunSessionPhase.Preparing -> "Preparing"
        RunSessionPhase.Running -> "Running"
        RunSessionPhase.Stopping -> "Stopping"
        RunSessionPhase.Completed -> "Completed"
        RunSessionPhase.Failed -> "Failed"
    }
}

private fun canStopRun(runtimeState: com.maaframework.android.model.RuntimeStateSnapshot): Boolean {
    return runtimeState.phase in setOf(
        RunSessionPhase.Preparing,
        RunSessionPhase.Running,
        RunSessionPhase.Stopping,
    )
}
