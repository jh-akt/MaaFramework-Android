@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maaframework.android.sample.bbb

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maaframework.android.model.PresetDescriptor
import com.maaframework.android.model.RunSessionPhase
import com.maaframework.android.model.TaskDescriptor

@Composable
fun MaaBbbSampleScreen(
    viewModel: MainViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val visibleTasks = remember(state.catalog.tasks, state.selectedResourceId) {
        MainViewModel.visibleTasks(state.catalog.tasks, state.selectedResourceId)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                    ),
                ),
            ),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    HeroCard(
                        state = state,
                        onConnect = { viewModel.requestRootAndConnect() },
                        onPrepare = viewModel::prepareRuntime,
                        onRunTask = viewModel::startSelectedTask,
                        onRunPreset = viewModel::startSelectedPreset,
                        onStop = viewModel::stopRun,
                        onExport = viewModel::exportDiagnostics,
                    )
                }

                item {
                    SectionCard(
                        title = "Resource",
                        subtitle = "Select the Android channel/resource bundle exposed by the project",
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.catalog.resources.forEach { resource ->
                                FilterChip(
                                    selected = resource.id == state.selectedResourceId,
                                    onClick = { viewModel.selectResource(resource.id) },
                                    label = { Text(resource.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                )
                            }
                        }
                    }
                }

                item {
                    SectionCard(
                        title = "Preset",
                        subtitle = "Presets reuse the task ordering shipped by the project",
                    ) {
                        if (state.catalog.presets.isEmpty()) {
                            Text(
                                text = "This project does not expose presets for the selected controllers.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                state.catalog.presets.forEach { preset ->
                                    PresetChip(
                                        preset = preset,
                                        selected = preset.id == state.selectedPresetId,
                                        onClick = { viewModel.selectPreset(preset.id) },
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    SectionCard(
                        title = "Override JSON",
                        subtitle = "Optional task override payload passed to MaaFramework for the selected task",
                    ) {
                        OutlinedTextField(
                            value = state.overrideJson,
                            onValueChange = viewModel::updateOverrideJson,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }

                item {
                    Text(
                        text = "Tasks",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }

                items(visibleTasks, key = { task -> task.id }) { task ->
                    TaskCard(
                        task = task,
                        selected = task.id == state.selectedTaskId,
                        running = state.runtimeState.currentTaskId == task.id &&
                            state.runtimeState.phase == RunSessionPhase.Running,
                        onSelect = { viewModel.selectTask(task.id) },
                        onRun = {
                            viewModel.selectTask(task.id)
                            viewModel.startSelectedTask()
                        },
                    )
                }

                item {
                    SectionCard(
                        title = "Runtime Logs",
                        subtitle = state.servicePing.ifBlank { "Waiting for root runtime connection" },
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatusLine(label = "Phase", value = state.runtimeState.phase.name)
                            StatusLine(label = "Current task", value = state.runtimeState.currentTaskId ?: "-")
                            StatusLine(label = "Message", value = state.runtimeState.lastMessage.ifBlank { state.lastMessage })
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 160.dp, max = 260.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                        shape = RoundedCornerShape(18.dp),
                                    )
                                    .padding(12.dp),
                            ) {
                                Text(
                                    text = if (state.displayLogs.isEmpty()) "No logs yet" else state.displayLogs.joinToString("\n"),
                                    modifier = Modifier.verticalScroll(rememberScrollState()),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    state: MainUiState,
    onConnect: () -> Unit,
    onPrepare: () -> Unit,
    onRunTask: () -> Unit,
    onRunPreset: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        ),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = state.manifest.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = state.lastMessage.ifBlank { "Ready" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(
                    "Root",
                    if (state.rootReport.granted) "Granted" else if (state.rootReport.available) "Available" else "Missing",
                )
                StatusPill("Runtime", state.runtimeState.phase.name)
                StatusPill("Connected", if (state.rootConnected) "Yes" else "No")
                StatusPill("Resource", state.selectedResourceId ?: "-")
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = onConnect, enabled = !state.busy) {
                    Text("Connect Root")
                }
                OutlinedButton(onClick = onPrepare, enabled = state.rootConnected && !state.busy) {
                    Text("Prepare Runtime")
                }
                Button(
                    onClick = onRunTask,
                    enabled = state.rootConnected && !state.busy && state.selectedTaskId != null,
                ) {
                    Text("Run Task")
                }
                OutlinedButton(
                    onClick = onRunPreset,
                    enabled = state.rootConnected && !state.busy && state.selectedPresetId != null,
                ) {
                    Text("Run Preset")
                }
                OutlinedButton(
                    onClick = onStop,
                    enabled = state.rootConnected && state.runtimeState.phase in setOf(
                        RunSessionPhase.Preparing,
                        RunSessionPhase.Running,
                        RunSessionPhase.Stopping,
                    ),
                ) {
                    Text("Stop")
                }
                OutlinedButton(onClick = onExport, enabled = state.rootConnected && !state.busy) {
                    Text("Export Diagnostics")
                }
            }

            if (state.rootReport.summary.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(20.dp),
                        )
                        .padding(14.dp),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Host Diagnostics",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = state.rootReport.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.rootReport.binaryProbes
                            .filter { probe -> probe.exists || probe.executableByApp }
                            .ifEmpty { state.rootReport.binaryProbes.take(3) }
                            .take(4)
                            .forEach { probe ->
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
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetChip(
    preset: PresetDescriptor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = "${preset.label} (${preset.taskIds.size})",
                maxLines = 1,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

@Composable
private fun TaskCard(
    task: TaskDescriptor,
    selected: Boolean,
    running: Boolean,
    onSelect: () -> Unit,
    onRun: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
            },
        ),
        shape = RoundedCornerShape(22.dp),
        onClick = onSelect,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = task.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = task.entry,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (running) {
                    StatusPill("Running", "Now")
                }
            }

            if (task.description.isNotBlank()) {
                Text(
                    text = task.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (task.repeatable) {
                    StatusPill("Repeat", task.repeatCount.toString())
                }
                task.supportedResources.take(4).forEach { resourceId ->
                    StatusPill("Resource", resourceId)
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = onSelect) {
                    Text(if (selected) "Selected" else "Select")
                }
                Button(onClick = onRun) {
                    Text("Run This Task")
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        ),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StatusPill(
    label: String,
    value: String,
) {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "$label · $value",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
