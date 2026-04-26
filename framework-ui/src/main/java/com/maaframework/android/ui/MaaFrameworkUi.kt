@file:OptIn(ExperimentalLayoutApi::class)

package com.maaframework.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.maaframework.android.catalog.TaskOptionSupport
import com.maaframework.android.model.TaskDescriptor
import com.maaframework.android.model.TaskOptionDescriptor
import com.maaframework.android.model.TaskOptionType
import com.maaframework.android.preview.DefaultDisplayConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object MaaUiDefaults {
    object Spacing {
        val xs: Dp = 4.dp
        val sm: Dp = 8.dp
        val md: Dp = 12.dp
        val lg: Dp = 16.dp
    }

    object CornerRadius {
        val card: Dp = 8.dp
        val inner: Dp = 6.dp
        val pill: Dp = 999.dp
    }
}

@Composable
fun MaaSectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(MaaUiDefaults.Spacing.sm),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(MaaUiDefaults.CornerRadius.card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .then(if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(MaaUiDefaults.Spacing.sm),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
fun MaaActionTile(
    label: String,
    description: String? = null,
    active: Boolean = false,
    activeLabel: String = "已就绪",
    idleLabel: String = "执行",
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MaterialSurface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MaaUiDefaults.CornerRadius.inner))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(MaaUiDefaults.CornerRadius.inner),
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        border = BorderStroke(
            1.dp,
            if (active) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(MaaUiDefaults.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaUiDefaults.Spacing.sm),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MaaStatusPill(
                text = if (active) activeLabel else idleLabel,
                active = active,
            )
        }
    }
}

@Composable
fun MaaStatusPill(
    text: String,
    active: Boolean,
    accent: Color? = null,
    modifier: Modifier = Modifier,
) {
    val color = accent ?: if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    MaterialSurface(
        modifier = modifier,
        shape = RoundedCornerShape(MaaUiDefaults.CornerRadius.pill),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun MaaOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        modifier = modifier,
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = FilterChipDefaults.filterChipColors(),
    )
}

@Composable
fun MaaTaskListPanel(
    tasks: List<TaskDescriptor>,
    selectedTaskId: String?,
    checkedTaskIds: Set<String>,
    onSelectTask: (String) -> Unit,
    onToggleTaskChecked: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    runningTaskId: String? = null,
    pinnedTaskIds: Set<String> = emptySet(),
    taskLabel: (TaskDescriptor) -> String = { compactTaskLabel(it) },
    emptyTitle: String = "暂无任务",
    emptyDescription: String = "目录加载完成后，任务会显示在这里。",
) {
    val (pinnedTasks, otherTasks) = remember(tasks, pinnedTaskIds) {
        tasks.partition { it.id in pinnedTaskIds }
    }
    Column(
        modifier = modifier
            .width(IntrinsicSize.Max)
            .widthIn(min = 96.dp, max = 112.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (tasks.isEmpty()) {
            MaaEmptyStateBlock(
                title = emptyTitle,
                description = emptyDescription,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    pinnedTasks.forEach { task ->
                        MaaTaskListItem(
                            task = task,
                            label = taskLabel(task),
                            selected = task.id == selectedTaskId,
                            checked = task.id in checkedTaskIds,
                            running = task.id == runningTaskId,
                            onClick = { onSelectTask(task.id) },
                            onCheckedChange = { checked -> onToggleTaskChecked(task.id, checked) },
                        )
                    }
                    if (pinnedTasks.isNotEmpty() && otherTasks.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp)
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                    otherTasks.forEach { task ->
                        MaaTaskListItem(
                            task = task,
                            label = taskLabel(task),
                            selected = task.id == selectedTaskId,
                            checked = task.id in checkedTaskIds,
                            running = task.id == runningTaskId,
                            onClick = { onSelectTask(task.id) },
                            onCheckedChange = { checked -> onToggleTaskChecked(task.id, checked) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MaaTaskDetailPanel(
    task: TaskDescriptor?,
    options: List<TaskOptionDescriptor>,
    selectedCaseNamesByOption: Map<String, Set<String>>,
    inputValuesByOption: Map<String, Map<String, String>>,
    inputErrorsByOption: Map<String, Map<String, String>>,
    onSwitchOption: (String, String, String) -> Unit,
    onToggleCheckboxOption: (String, String, String) -> Unit,
    onInputValueChange: (String, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
    ownerId: String? = null,
    title: String = "任务配置",
    emptyTitle: String = "还没有选中任务",
    emptyDescription: String = "先在左侧点一个任务，再到这里调整执行参数。",
    noOptionsTitle: String = "这个任务暂无额外参数",
    noOptionsDescription: String = "可以直接勾选并开始执行。",
    extraContent: @Composable ColumnScope.() -> Unit = {},
) {
    MaaSectionCard(
        title = title,
        modifier = modifier.fillMaxWidth(),
        fillHeight = true,
        contentPadding = PaddingValues(
            start = 2.dp,
            top = MaaUiDefaults.Spacing.sm,
            end = 4.dp,
            bottom = MaaUiDefaults.Spacing.sm,
        ),
    ) {
        if (task == null) {
            MaaEmptyStateBlock(
                title = emptyTitle,
                description = emptyDescription,
            )
        } else if (options.isEmpty()) {
            MaaEmptyStateBlock(
                title = noOptionsTitle,
                description = noOptionsDescription,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(MaaUiDefaults.Spacing.sm),
                ) {
                    MaaTaskOptionsForm(
                        ownerId = ownerId ?: task.id,
                        title = task.label,
                        description = task.description,
                        options = options,
                        selectedCaseNamesByOption = selectedCaseNamesByOption,
                        inputValuesByOption = inputValuesByOption,
                        inputErrorsByOption = inputErrorsByOption,
                        onSwitchOption = onSwitchOption,
                        onToggleCheckboxOption = onToggleCheckboxOption,
                        onInputValueChange = onInputValueChange,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    extraContent()
                }
            }
        }
    }
}

@Composable
fun MaaTaskOptionsForm(
    ownerId: String,
    title: String,
    description: String,
    options: List<TaskOptionDescriptor>,
    selectedCaseNamesByOption: Map<String, Set<String>>,
    inputValuesByOption: Map<String, Map<String, String>>,
    inputErrorsByOption: Map<String, Map<String, String>>,
    onSwitchOption: (String, String, String) -> Unit,
    onToggleCheckboxOption: (String, String, String) -> Unit,
    onInputValueChange: (String, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
    iconContent: (@Composable () -> Unit)? = null,
    showHeader: Boolean = true,
    hideDescriptions: Boolean = false,
) {
    MaterialSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaaUiDefaults.CornerRadius.inner),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(MaaUiDefaults.Spacing.sm),
        ) {
            if (showHeader) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaaUiDefaults.Spacing.sm),
                ) {
                    iconContent?.invoke()
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (showHeader && !hideDescriptions && description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(MaaUiDefaults.Spacing.xs),
            ) {
                options.forEach { option ->
                    MaaTaskOptionBlock(
                        ownerId = ownerId,
                        option = option,
                        selectedCaseNamesByOption = selectedCaseNamesByOption,
                        inputValuesByOption = inputValuesByOption,
                        inputErrorsByOption = inputErrorsByOption,
                        onSwitchOption = onSwitchOption,
                        onToggleCheckboxOption = onToggleCheckboxOption,
                        onInputValueChange = onInputValueChange,
                        hideDescriptions = hideDescriptions,
                    )
                }
            }
        }
    }
}

@Composable
fun MaaPreviewSurfaceHost(
    modifier: Modifier = Modifier,
    onPreviewSurfaceChanged: (Surface?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        onDispose {
            onPreviewSurfaceChanged(null)
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.aspectRatio(DefaultDisplayConfig.ASPECT_RATIO),
        ) {
            AndroidView(
                factory = {
                    SurfaceView(it).apply {
                        var lastSurface: Surface? = null
                        holder.setFormat(PixelFormat.RGBA_8888)
                        holder.addCallback(
                            object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    scope.launch {
                                        delay(50)
                                        holder.setFixedSize(DefaultDisplayConfig.WIDTH, DefaultDisplayConfig.HEIGHT)
                                    }
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
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun MaaPreviewPanel(
    isFullscreenPreview: Boolean,
    onExpandPreview: () -> Unit,
    modifier: Modifier = Modifier,
    overlayText: String? = null,
    previewContent: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(DefaultDisplayConfig.ASPECT_RATIO)
            .clip(RoundedCornerShape(MaaUiDefaults.CornerRadius.card))
            .background(Color.Black),
    ) {
        if (!isFullscreenPreview) {
            previewContent()
            overlayText?.takeIf { it.isNotBlank() }?.let {
                MaaPreviewOverlayHint(it)
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.35f),
                            ),
                        ),
                    )
                    .clickable(onClick = onExpandPreview),
            )
        } else {
            Spacer(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun MaaFullscreenPreviewOverlay(
    previewContent: @Composable () -> Unit,
    onDismissRequest: () -> Unit,
    onPreviewTouchDown: (Int, Int, Int) -> Boolean,
    onPreviewTouchMove: (Int, Int, Int) -> Boolean,
    onPreviewTouchUp: (Int, Int, Int) -> Boolean,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val fullscreenProgress = remember { Animatable(0f) }
    val activeTouches = remember { mutableMapOf<Int, PreviewPoint>() }
    val contactIdsByPointer = remember { mutableMapOf<Long, Int>() }
    val nextContactId = remember { intArrayOf(0) }

    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let {
            WindowCompat.getInsetsController(it, it.decorView)
        }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(activity) {
        val originalOrientation = activity?.requestedOrientation
        onDispose {
            if (activity != null && originalOrientation != null) {
                activity.requestedOrientation = originalOrientation
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeTouches.toMap().forEach { (contactId, point) ->
                onPreviewTouchUp(contactId, point.x, point.y)
            }
            activeTouches.clear()
            contactIdsByPointer.clear()
        }
    }

    LaunchedEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        fullscreenProgress.snapTo(0f)
        fullscreenProgress.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
    }

    BackHandler(onBack = onDismissRequest)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val changes = event.changes
                        if (changes.isEmpty()) {
                            continue
                        }

                        changes
                            .filter { !it.previousPressed && it.pressed }
                            .forEach { change ->
                                val point = mapViewToVirtualDisplay(
                                    viewX = change.position.x,
                                    viewY = change.position.y,
                                    viewWidth = size.width,
                                    viewHeight = size.height,
                                    bufferWidth = DefaultDisplayConfig.WIDTH,
                                    bufferHeight = DefaultDisplayConfig.HEIGHT,
                                    clampToBounds = false,
                                ) ?: return@forEach
                                val pointerToken = change.id.value
                                val contactId = contactIdsByPointer.getOrPut(pointerToken) { nextContactId[0]++ }
                                if (onPreviewTouchDown(contactId, point.x, point.y)) {
                                    activeTouches[contactId] = point
                                } else {
                                    contactIdsByPointer.remove(pointerToken)
                                }
                            }

                        changes
                            .filter { it.previousPressed && it.pressed && it.position != it.previousPosition }
                            .forEach { change ->
                                val pointerToken = change.id.value
                                val contactId = contactIdsByPointer[pointerToken] ?: return@forEach
                                val point = mapViewToVirtualDisplay(
                                    viewX = change.position.x,
                                    viewY = change.position.y,
                                    viewWidth = size.width,
                                    viewHeight = size.height,
                                    bufferWidth = DefaultDisplayConfig.WIDTH,
                                    bufferHeight = DefaultDisplayConfig.HEIGHT,
                                    clampToBounds = true,
                                ) ?: return@forEach
                                activeTouches[contactId] = point
                                onPreviewTouchMove(contactId, point.x, point.y)
                            }

                        changes
                            .filter { it.previousPressed && !it.pressed }
                            .forEach { change ->
                                val pointerToken = change.id.value
                                val contactId = contactIdsByPointer[pointerToken] ?: return@forEach
                                val point = mapViewToVirtualDisplay(
                                    viewX = change.position.x,
                                    viewY = change.position.y,
                                    viewWidth = size.width,
                                    viewHeight = size.height,
                                    bufferWidth = DefaultDisplayConfig.WIDTH,
                                    bufferHeight = DefaultDisplayConfig.HEIGHT,
                                    clampToBounds = true,
                                ) ?: activeTouches[contactId]
                                if (point != null) {
                                    onPreviewTouchUp(contactId, point.x, point.y)
                                }
                                activeTouches.remove(contactId)
                                contactIdsByPointer.remove(pointerToken)
                            }

                        changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = fullscreenProgress.value
                },
        ) {
            previewContent()
        }

        IconButton(
            onClick = onDismissRequest,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = MaaUiDefaults.Spacing.sm, end = MaaUiDefaults.Spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭预览",
                tint = Color.White.copy(alpha = 0.78f),
            )
        }
    }
}

@Composable
fun MaaRuntimeLogList(
    lines: List<String>,
    modifier: Modifier = Modifier,
    emptyTitle: String = "暂无日志",
    emptyDescription: String = "开始任务后，这里会显示运行日志。",
) {
    if (lines.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            MaaEmptyStateBlock(
                title = emptyTitle,
                description = emptyDescription,
            )
        }
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        listState.animateScrollToItem(lines.lastIndex)
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(lines) { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun MaaTaskListItem(
    task: TaskDescriptor,
    label: String,
    selected: Boolean,
    checked: Boolean,
    running: Boolean,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Checkbox(
                    checked = checked || running,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer(
                            scaleX = 0.72f,
                            scaleY = 0.72f,
                        ),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MaaTaskOptionBlock(
    ownerId: String,
    option: TaskOptionDescriptor,
    selectedCaseNamesByOption: Map<String, Set<String>>,
    inputValuesByOption: Map<String, Map<String, String>>,
    inputErrorsByOption: Map<String, Map<String, String>>,
    onSwitchOption: (String, String, String) -> Unit,
    onToggleCheckboxOption: (String, String, String) -> Unit,
    onInputValueChange: (String, String, String, String) -> Unit,
    nested: Boolean = false,
    hideDescriptions: Boolean = false,
) {
    val selectedCaseNames = selectedCaseNamesByOption[option.id].takeUnless { it.isNullOrEmpty() }
        ?: TaskOptionSupport.defaultSelectionForOption(option)
    val inputValues = inputValuesByOption[option.id].orEmpty()

    MaterialSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaaUiDefaults.CornerRadius.inner),
        color = if (nested) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (nested) 6.dp else 8.dp,
                vertical = if (nested) 5.dp else 7.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(MaaUiDefaults.Spacing.xs),
        ) {
            Text(
                text = option.label,
                style = if (nested) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!hideDescriptions && option.description.isNotBlank()) {
                Text(
                    text = option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (option.type) {
                TaskOptionType.Switch,
                TaskOptionType.Select -> {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        option.cases.forEach { optionCase ->
                            MaaOptionChip(
                                label = optionCase.label,
                                selected = optionCase.name in selectedCaseNames,
                                onClick = { onSwitchOption(ownerId, option.id, optionCase.name) },
                            )
                        }
                    }
                    MaaNestedTaskOptions(
                        ownerId = ownerId,
                        option = option,
                        selectedCaseNames = selectedCaseNames,
                        selectedCaseNamesByOption = selectedCaseNamesByOption,
                        inputValuesByOption = inputValuesByOption,
                        inputErrorsByOption = inputErrorsByOption,
                        onSwitchOption = onSwitchOption,
                        onToggleCheckboxOption = onToggleCheckboxOption,
                        onInputValueChange = onInputValueChange,
                        hideDescriptions = hideDescriptions,
                    )
                }

                TaskOptionType.Checkbox -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        option.cases.forEach { optionCase ->
                            MaaOptionCheckboxRow(
                                label = optionCase.label,
                                checked = optionCase.name in selectedCaseNames,
                                onCheckedChange = { checked ->
                                    onToggleCheckboxOption(ownerId, option.id, optionCase.name)
                                },
                            )
                        }
                    }
                    MaaNestedTaskOptions(
                        ownerId = ownerId,
                        option = option,
                        selectedCaseNames = selectedCaseNames,
                        selectedCaseNamesByOption = selectedCaseNamesByOption,
                        inputValuesByOption = inputValuesByOption,
                        inputErrorsByOption = inputErrorsByOption,
                        onSwitchOption = onSwitchOption,
                        onToggleCheckboxOption = onToggleCheckboxOption,
                        onInputValueChange = onInputValueChange,
                        hideDescriptions = hideDescriptions,
                    )
                }

                TaskOptionType.Input -> {
                    option.inputs.forEach { input ->
                        val value = inputValues[input.name] ?: input.defaultValue
                        val error = inputErrorsByOption[option.id]?.get(input.name)
                        OutlinedTextField(
                            value = value,
                            onValueChange = { newValue ->
                                onInputValueChange(ownerId, option.id, input.name, newValue)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(input.label) },
                            supportingText = {
                                Text(
                                    text = error
                                        ?: input.description.takeIf { !hideDescriptions && it.isNotBlank() }
                                        ?: " ",
                                )
                            },
                            isError = error != null,
                            singleLine = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MaaNestedTaskOptions(
    ownerId: String,
    option: TaskOptionDescriptor,
    selectedCaseNames: Set<String>,
    selectedCaseNamesByOption: Map<String, Set<String>>,
    inputValuesByOption: Map<String, Map<String, String>>,
    inputErrorsByOption: Map<String, Map<String, String>>,
    onSwitchOption: (String, String, String) -> Unit,
    onToggleCheckboxOption: (String, String, String) -> Unit,
    onInputValueChange: (String, String, String, String) -> Unit,
    hideDescriptions: Boolean,
) {
    val nestedOptions = option.cases
        .filter { it.name in selectedCaseNames }
        .flatMap { it.nestedOptions }
    if (nestedOptions.isEmpty()) {
        return
    }
    Column(
        modifier = Modifier.padding(start = 6.dp),
        verticalArrangement = Arrangement.spacedBy(MaaUiDefaults.Spacing.xs),
    ) {
        nestedOptions.forEach { nestedOption ->
            MaaTaskOptionBlock(
                ownerId = ownerId,
                option = nestedOption,
                selectedCaseNamesByOption = selectedCaseNamesByOption,
                inputValuesByOption = inputValuesByOption,
                inputErrorsByOption = inputErrorsByOption,
                onSwitchOption = onSwitchOption,
                onToggleCheckboxOption = onToggleCheckboxOption,
                onInputValueChange = onInputValueChange,
                nested = true,
                hideDescriptions = hideDescriptions,
            )
        }
    }
}

@Composable
private fun MaaOptionCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer(scaleX = 0.8f, scaleY = 0.8f),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MaaEmptyStateBlock(
    title: String,
    description: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MaaUiDefaults.Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaaUiDefaults.Spacing.xs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MaaPreviewOverlayHint(text: String) {
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

private data class PreviewPoint(
    val x: Int,
    val y: Int,
)

private fun mapViewToVirtualDisplay(
    viewX: Float,
    viewY: Float,
    viewWidth: Int,
    viewHeight: Int,
    bufferWidth: Int,
    bufferHeight: Int,
    clampToBounds: Boolean,
): PreviewPoint? {
    val bufferW = bufferWidth.toFloat()
    val bufferH = bufferHeight.toFloat()
    val scale = minOf(viewWidth / bufferW, viewHeight / bufferH)
    val offsetX = (viewWidth - bufferW * scale) / 2f
    val offsetY = (viewHeight - bufferH * scale) / 2f
    var mappedX = (viewX - offsetX) / scale
    var mappedY = (viewY - offsetY) / scale

    if (!clampToBounds && (mappedX < 0f || mappedX >= bufferW || mappedY < 0f || mappedY >= bufferH)) {
        return null
    }

    mappedX = mappedX.coerceIn(0f, bufferW - 1f)
    mappedY = mappedY.coerceIn(0f, bufferH - 1f)
    return PreviewPoint(mappedX.toInt(), mappedY.toInt())
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) {
            return current
        }
        current = current.baseContext
    }
    return null
}

private fun compactTaskLabel(task: TaskDescriptor): String {
    return task.label.takeIf { it.isNotBlank() } ?: task.id
}
