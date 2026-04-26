package com.maaframework.android.sample.bbb

import android.app.Application
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maaframework.android.catalog.InterfaceCatalogLoader
import com.maaframework.android.model.CatalogSnapshot
import com.maaframework.android.model.PresetDescriptor
import com.maaframework.android.model.RootEnvironmentReport
import com.maaframework.android.model.RunRequest
import com.maaframework.android.model.RunSessionPhase
import com.maaframework.android.model.RuntimeLogChunk
import com.maaframework.android.model.RuntimeStateSnapshot
import com.maaframework.android.model.TaskDescriptor
import com.maaframework.android.model.TaskOptionDescriptor
import com.maaframework.android.model.TaskOptionType
import com.maaframework.android.project.MaaProjectManifest
import com.maaframework.android.project.MaaProjectManifestLoader
import com.maaframework.android.runtime.PersistentProjectRepositoryManager
import com.maaframework.android.runtime.PersistentProjectRepositoryStatus
import com.maaframework.android.runtime.PersistentProjectRepositorySyncProgress
import com.maaframework.android.session.MaaFrameworkSession
import com.maaframework.android.session.MaaRuntimeClient
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

enum class MaaBbbTab {
    Home,
    Tasks,
    Settings,
    Logs,
}

data class MainUiState(
    val activeTab: MaaBbbTab = MaaBbbTab.Home,
    val manifest: MaaProjectManifest = MaaProjectManifest(),
    val catalog: CatalogSnapshot = CatalogSnapshot(),
    val resourceRepository: PersistentProjectRepositoryStatus = PersistentProjectRepositoryStatus(),
    val resourceRepositoryUpdating: Boolean = false,
    val resourceRepositoryProgress: PersistentProjectRepositorySyncProgress? = null,
    val rootReport: RootEnvironmentReport = RootEnvironmentReport(),
    val rootConnected: Boolean = false,
    val servicePing: String = "",
    val runtimeState: RuntimeStateSnapshot = RuntimeStateSnapshot(),
    val selectedResourceId: String? = null,
    val selectedTaskId: String? = null,
    val checkedTaskIds: Set<String> = emptySet(),
    val taskOptionSelectionsByTask: Map<String, Map<String, Set<String>>> = emptyMap(),
    val taskInputValuesByTask: Map<String, Map<String, Map<String, String>>> = emptyMap(),
    val selectedPresetId: String? = null,
    val overrideJson: String = "{}",
    val displayLogs: List<String> = emptyList(),
    val lastMessage: String = "",
    val busy: Boolean = false,
)

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val manifest = MaaProjectManifestLoader.loadOrDefault(application.assets)
    private val catalogLoader = InterfaceCatalogLoader(application.assets, manifest.supportedControllers)
    private val session = MaaFrameworkSession(application)
    private val settingsRepository = AppSettingsRepository(application)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var runtimeClient: MaaRuntimeClient? = null
    private var pollJob: Job? = null
    private var connectJob: Job? = null
    private var previewSurface: Surface? = null
    private var logCursor = 0L

    init {
        val appSettings = settingsRepository.load()
        val resourceRepository = PersistentProjectRepositoryManager.loadStatus(application, manifest)
        var initialMessage = resourceRepository.lastError
            ?.takeIf { it.isNotBlank() }
            ?: "GitHub 资源仓库未就绪"
        val catalog = runCatching { loadCatalogSnapshot(resourceRepository) }
            .getOrElse { error ->
                Log.e(TAG, "Failed to load interface catalog", error)
                initialMessage = "GitHub 资源缺失或格式异常：${error.message ?: error::class.java.simpleName}"
                CatalogSnapshot()
            }
        if (!resourceRepository.available && manifest.hasGitHubResourceRepository() && initialMessage == "GitHub 资源仓库未就绪") {
            initialMessage = "首次启动需要同步 GitHub 资源，正在后台下载"
        }
        _uiState.value = buildInitialState(catalog, resourceRepository, initialMessage, appSettings)
        if (!resourceRepository.available && manifest.hasGitHubResourceRepository()) {
            viewModelScope.launch {
                syncResourceRepository(force = false, silent = true)
            }
        }
        if (_uiState.value.rootReport.available) {
            requestRootAndConnect(silent = true)
        }
    }

    fun selectResource(resourceId: String) {
        val visibleTasks = visibleTasks(_uiState.value.catalog.tasks, resourceId)
        val selectedTaskId = _uiState.value.selectedTaskId
            ?.takeIf { taskId -> visibleTasks.any { it.id == taskId } }
            ?: visibleTasks.firstOrNull()?.id
        val checkedTaskIds = _uiState.value.checkedTaskIds
            .filterTo(linkedSetOf()) { taskId -> visibleTasks.any { it.id == taskId } }
        _uiState.value = _uiState.value.copy(
            selectedResourceId = resourceId,
            selectedTaskId = selectedTaskId,
            checkedTaskIds = checkedTaskIds,
        )
        settingsRepository.saveSelectedResourceId(resourceId)
        settingsRepository.saveSelectedTaskId(selectedTaskId)
        settingsRepository.saveCheckedTaskIds(checkedTaskIds)
    }

    fun selectTab(tab: MaaBbbTab) {
        _uiState.value = _uiState.value.copy(activeTab = tab)
    }

    fun selectTask(taskId: String) {
        _uiState.value = _uiState.value.copy(selectedTaskId = taskId)
        settingsRepository.saveSelectedTaskId(taskId)
    }

    fun toggleTaskChecked(taskId: String, checked: Boolean) {
        val updated = _uiState.value.checkedTaskIds.toMutableSet().apply {
            if (checked) {
                add(taskId)
            } else {
                remove(taskId)
            }
        }
        _uiState.value = _uiState.value.copy(checkedTaskIds = updated)
        settingsRepository.saveCheckedTaskIds(updated)
    }

    fun selectPreset(presetId: String) {
        _uiState.value = _uiState.value.copy(selectedPresetId = presetId)
        settingsRepository.saveSelectedPresetId(presetId)
    }

    fun updateOverrideJson(value: String) {
        _uiState.value = _uiState.value.copy(overrideJson = value)
        settingsRepository.saveOverrideJson(value)
    }

    fun updateTaskSwitchOption(taskId: String, optionId: String, caseName: String) {
        updateTaskOptionSelections(taskId) { current ->
            current + (optionId to setOf(caseName))
        }
    }

    fun toggleTaskCheckboxOption(taskId: String, optionId: String, caseName: String) {
        updateTaskOptionSelections(taskId) { current ->
            val selected = current[optionId].orEmpty()
            val updated = if (caseName in selected) {
                selected - caseName
            } else {
                selected + caseName
            }
            current + (optionId to updated)
        }
    }

    fun updateTaskInputValue(taskId: String, optionId: String, inputName: String, value: String) {
        val currentTaskValues = _uiState.value.taskInputValuesByTask[taskId].orEmpty()
        val currentOptionValues = currentTaskValues[optionId].orEmpty()
        val updatedTaskValues = currentTaskValues + (optionId to (currentOptionValues + (inputName to value)))
        val nextValues = _uiState.value.taskInputValuesByTask + (taskId to updatedTaskValues)
        _uiState.value = _uiState.value.copy(
            taskInputValuesByTask = nextValues,
        )
        settingsRepository.saveTaskInputValuesByTask(nextValues)
    }

    fun refreshResourceRepository() {
        if (_uiState.value.resourceRepositoryUpdating) {
            return
        }
        viewModelScope.launch {
            syncResourceRepository(force = true, silent = false)
        }
    }

    fun requestRootAndConnect(silent: Boolean = false) {
        if (connectJob?.isActive == true) {
            return
        }

        connectJob = viewModelScope.launch {
            val rootReport = session.rootDiagnostics()
            _uiState.value = _uiState.value.copy(
                rootReport = rootReport,
                busy = true,
                lastMessage = if (silent) _uiState.value.lastMessage else "Checking root runtime",
            )
            if (!rootReport.available) {
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    lastMessage = rootReport.summary,
                )
                return@launch
            }

            if (!rootReport.granted) {
                val granted = session.requestRootPermission()
                val refreshedReport = session.rootDiagnostics()
                _uiState.value = _uiState.value.copy(rootReport = refreshedReport)
                if (!granted) {
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        lastMessage = refreshedReport.summary.ifBlank { "Root permission was not granted" },
                    )
                    return@launch
                }
            }

            val result = session.connectClient()
            result.onSuccess { client ->
                bindService(client)
            }.onFailure { error ->
                Log.e(TAG, "Failed to connect root runtime", error)
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    rootConnected = false,
                    lastMessage = "Failed to connect root runtime: ${error.message}",
                )
            }
        }
    }

    fun prepareRuntime() {
        runServiceAction(
            actionName = "Preparing runtime",
            action = { it.prepareRuntime() },
            onSuccess = { prepared ->
                _uiState.value = _uiState.value.copy(
                    lastMessage = if (prepared) "Runtime prepared" else "Runtime prepare request failed",
                )
            },
        )
    }

    fun startWindowedGame() {
        val resourceId = _uiState.value.selectedResourceId
        runServiceAction(
            actionName = "Opening game",
            action = { it.startWindowedGame(resourceId) },
            onSuccess = { opened ->
                _uiState.value = _uiState.value.copy(
                    lastMessage = if (opened) {
                        "Windowed game launched"
                    } else {
                        "Windowed game launch failed"
                    },
                )
            },
        )
    }

    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        runtimeClient?.setMonitorSurface(surface)
    }

    fun onPreviewTouchDown(contactId: Int, x: Int, y: Int): Boolean {
        return runCatching { runtimeClient?.touchDown(contactId, x, y) ?: false }.getOrDefault(false)
    }

    fun onPreviewTouchMove(contactId: Int, x: Int, y: Int): Boolean {
        return runCatching { runtimeClient?.touchMove(contactId, x, y) ?: false }.getOrDefault(false)
    }

    fun onPreviewTouchUp(contactId: Int, x: Int, y: Int): Boolean {
        return runCatching { runtimeClient?.touchUp(contactId, x, y) ?: false }.getOrDefault(false)
    }

    fun toggleDisplayPower() {
        val restoreScreen = _uiState.value.runtimeState.displayPowerOffActive
        runServiceAction(
            actionName = if (restoreScreen) "Restoring screen power" else "Turning screen off",
            action = { it.setDisplayPower(restoreScreen) },
            onSuccess = { changed ->
                _uiState.value = _uiState.value.copy(
                    lastMessage = if (changed) {
                        if (restoreScreen) "Screen power restored" else "Screen turned off for background run"
                    } else {
                        "Display power change failed"
                    },
                )
            },
        )
    }

    fun startSelectedTask() {
        val state = _uiState.value
        val visibleTasks = visibleTasks(state.catalog.tasks, state.selectedResourceId)
        val requestedTaskIds = visibleTasks
            .map { it.id }
            .filterTo(mutableListOf()) { it in state.checkedTaskIds }
            .ifEmpty {
                state.selectedTaskId
                    ?.takeIf { taskId -> visibleTasks.any { it.id == taskId } }
                    ?.let(::listOf)
                    .orEmpty()
            }
        if (requestedTaskIds.isEmpty()) {
            _uiState.value = state.copy(lastMessage = "Select a task first")
            return
        }
        val requestedTasks = requestedTaskIds.mapNotNull { taskId ->
            visibleTasks.firstOrNull { it.id == taskId }
        }
        val validationError = validateTaskOptionSelections(requestedTasks, state.selectedResourceId)
        if (validationError != null) {
            _uiState.value = state.copy(lastMessage = validationError)
            return
        }

        val request = RunRequest(
            taskId = requestedTaskIds.first(),
            sequenceTaskIds = requestedTaskIds,
            resourceName = state.selectedResourceId ?: manifest.defaultResourceId.orEmpty(),
            optionOverridesByTask = buildOverrideMap(
                tasks = requestedTasks,
                resourceId = state.selectedResourceId,
                selectedTaskId = state.selectedTaskId,
                overrideJson = state.overrideJson,
            ),
        )
        val label = if (requestedTaskIds.size == 1) {
            "Starting task ${requestedTaskIds.first()}"
        } else {
            "Starting ${requestedTaskIds.size} tasks"
        }
        startRun(request, label)
    }

    fun startSelectedPreset() {
        val state = _uiState.value
        val preset = state.selectedPresetOrNull()
        if (preset == null) {
            _uiState.value = state.copy(lastMessage = "Select a preset first")
            return
        }

        val request = RunRequest(
            presetId = preset.id,
            sequenceTaskIds = preset.taskIds,
            resourceName = state.selectedResourceId ?: manifest.defaultResourceId.orEmpty(),
        )
        startRun(request, "Starting preset ${preset.label}")
    }

    fun stopRun() {
        runServiceAction(
            actionName = "Stopping run",
            action = {
                it.stopRun()
                true
            },
            onSuccess = {
                _uiState.value = _uiState.value.copy(lastMessage = "Stop requested")
            },
        )
    }

    fun exportDiagnostics() {
        runServiceAction(
            actionName = "Exporting diagnostics",
            action = { it.exportDiagnostics() },
            onSuccess = { path ->
                _uiState.value = _uiState.value.copy(lastMessage = "Diagnostics exported: $path")
            },
        )
    }

    fun exportConfig(outputStream: OutputStream) {
        runCatching {
            persistCurrentSettings()
            settingsRepository.exportTo(outputStream)
        }.onSuccess {
            _uiState.value = _uiState.value.copy(lastMessage = "配置已导出")
        }.onFailure { error ->
            Log.e(TAG, "Failed to export config", error)
            _uiState.value = _uiState.value.copy(
                lastMessage = "配置导出失败：${error.message ?: error::class.java.simpleName}",
            )
        }
    }

    fun importConfig(inputStream: InputStream) {
        runCatching {
            val imported = settingsRepository.importFrom(inputStream)
            applyImportedSettings(imported)
        }.onSuccess {
            _uiState.value = _uiState.value.copy(lastMessage = "配置已导入")
        }.onFailure { error ->
            Log.e(TAG, "Failed to import config", error)
            _uiState.value = _uiState.value.copy(
                lastMessage = "配置导入失败：${error.message ?: error::class.java.simpleName}",
            )
        }
    }

    private fun startRun(request: RunRequest, label: String) {
        runServiceAction(
            actionName = label,
            action = { client -> client.startRun(request) },
            onSuccess = { started ->
                _uiState.value = _uiState.value.copy(
                    lastMessage = if (started) label else "Run request was rejected",
                )
            },
        )
    }

    private fun bindService(client: MaaRuntimeClient) {
        runtimeClient = client
        logCursor = 0L
        runCatching { client.setMonitorSurface(previewSurface) }
        _uiState.value = _uiState.value.copy(
            busy = false,
            rootConnected = true,
            rootReport = session.rootDiagnostics(),
            lastMessage = "Root runtime connected",
        )
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                val client = runtimeClient ?: break
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val ping = client.ping()
                        val snapshot = client.getState()
                        val chunk = client.readLogChunk(logCursor, 256)
                        PollResult(
                            ping = ping,
                            snapshot = snapshot,
                            logChunk = chunk,
                        )
                    }
                }

                result.onSuccess { poll ->
                    logCursor = poll.logChunk.nextOffsetBytes
                    _uiState.value = _uiState.value.copy(
                        servicePing = poll.ping,
                        runtimeState = poll.snapshot,
                        displayLogs = mergeLogs(_uiState.value.displayLogs, poll.logChunk),
                        lastMessage = poll.snapshot.lastMessage.ifBlank { _uiState.value.lastMessage },
                        rootConnected = true,
                        busy = false,
                    )
                }.onFailure { error ->
                    Log.e(TAG, "Polling root runtime failed", error)
                    val rootReport = session.rootDiagnostics()
                    _uiState.value = _uiState.value.copy(
                        rootReport = rootReport,
                        rootConnected = false,
                        busy = false,
                        lastMessage = "Root runtime disconnected: ${error.message ?: rootReport.summary}",
                    )
                    runtimeClient = null
                    break
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun mergeLogs(existing: List<String>, chunk: RuntimeLogChunk): List<String> {
        val base = if (chunk.reset) {
            emptyList()
        } else {
            existing
        }
        return (base + chunk.lines).takeLast(MAX_LOG_LINES)
    }

    private suspend fun syncResourceRepository(force: Boolean, silent: Boolean) {
        if (!manifest.hasGitHubResourceRepository() || _uiState.value.resourceRepositoryUpdating) {
            return
        }
        _uiState.value = _uiState.value.copy(
            resourceRepositoryUpdating = true,
            resourceRepositoryProgress = PersistentProjectRepositorySyncProgress(
                fraction = 0f,
                label = if (force) "正在同步 GitHub 资源" else "正在检查 GitHub 资源",
            ),
            lastMessage = if (silent) _uiState.value.lastMessage else "正在同步 GitHub 资源",
        )
        val application = getApplication<Application>()
        val status = runCatching {
            withContext(Dispatchers.IO) {
                if (force) {
                    PersistentProjectRepositoryManager.updateFromGithub(
                        context = application,
                        manifest = manifest,
                        progress = ::updateResourceRepositoryProgress,
                    )
                } else {
                    PersistentProjectRepositoryManager.ensureAvailable(
                        context = application,
                        manifest = manifest,
                        progress = ::updateResourceRepositoryProgress,
                    )
                }
            }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to sync GitHub resource repository", error)
            PersistentProjectRepositoryManager.loadStatus(application, manifest).copy(
                lastError = error.message ?: error::class.java.simpleName,
            )
        }

        refreshCatalogSnapshot(status)
        val nextMessage = when {
            status.available && force -> "GitHub 资源已更新"
            status.available -> "GitHub 资源仓库已就绪"
            else -> "GitHub 资源同步失败：${status.lastError ?: "未知错误"}"
        }
        _uiState.value = _uiState.value.copy(
            resourceRepository = status,
            resourceRepositoryUpdating = false,
            resourceRepositoryProgress = null,
            lastMessage = nextMessage,
        )
    }

    private fun updateResourceRepositoryProgress(progress: PersistentProjectRepositorySyncProgress) {
        _uiState.value = _uiState.value.copy(resourceRepositoryProgress = progress)
    }

    private fun loadCatalogSnapshot(resourceRepository: PersistentProjectRepositoryStatus): CatalogSnapshot {
        val application = getApplication<Application>()
        return if (resourceRepository.available && manifest.hasGitHubResourceRepository()) {
            catalogLoader.loadFromDirectory(PersistentProjectRepositoryManager.currentRoot(application, manifest))
        } else {
            CatalogSnapshot()
        }
    }

    private fun refreshCatalogSnapshot(resourceRepository: PersistentProjectRepositoryStatus) {
        val catalog = runCatching { loadCatalogSnapshot(resourceRepository) }
            .getOrElse { error ->
                Log.e(TAG, "Failed to reload catalog from GitHub resource source", error)
                return
            }
        val state = _uiState.value
        val storedSettings = settingsRepository.load()
        val selectedResourceId = state.selectedResourceId
            ?.takeIf { resourceId -> catalog.resources.any { it.id == resourceId } }
            ?: storedSettings.selectedResourceId?.takeIf { resourceId -> catalog.resources.any { it.id == resourceId } }
            ?: manifest.defaultResourceId?.takeIf { resourceId -> catalog.resources.any { it.id == resourceId } }
            ?: catalog.resources.firstOrNull()?.id
        val visibleTasks = visibleTasks(catalog.tasks, selectedResourceId)
        val selectedTaskId = state.selectedTaskId
            ?.takeIf { taskId -> visibleTasks.any { it.id == taskId } }
            ?: storedSettings.selectedTaskId?.takeIf { taskId -> visibleTasks.any { it.id == taskId } }
            ?: manifest.defaultTaskId?.takeIf { taskId -> visibleTasks.any { it.id == taskId } }
            ?: visibleTasks.firstOrNull()?.id
        val checkedTaskIds = state.checkedTaskIds
            .ifEmpty { storedSettings.checkedTaskIds }
            .filterTo(linkedSetOf()) { taskId -> visibleTasks.any { it.id == taskId } }
            .ifEmpty {
                defaultCheckedTaskIds(visibleTasks, selectedTaskId)
            }
        val selectedPresetId = state.selectedPresetId
            ?.takeIf { presetId -> catalog.presets.any { it.id == presetId } }
            ?: storedSettings.selectedPresetId?.takeIf { presetId -> catalog.presets.any { it.id == presetId } }
            ?: manifest.defaultPresetId?.takeIf { presetId -> catalog.presets.any { it.id == presetId } }
            ?: catalog.presets.firstOrNull()?.id
        val optionSelections = state.taskOptionSelectionsByTask.ifEmpty {
            storedSettings.taskOptionSelectionsByTask
        }
        val inputValues = state.taskInputValuesByTask.ifEmpty {
            storedSettings.taskInputValuesByTask
        }
        _uiState.value = _uiState.value.copy(
            catalog = catalog,
            selectedResourceId = selectedResourceId,
            selectedTaskId = selectedTaskId,
            checkedTaskIds = checkedTaskIds,
            taskOptionSelectionsByTask = mergeTaskOptionSelections(catalog.tasks, optionSelections),
            taskInputValuesByTask = mergeTaskInputValues(catalog.tasks, inputValues),
            selectedPresetId = selectedPresetId,
        )
    }

    private fun buildInitialState(
        catalog: CatalogSnapshot,
        resourceRepository: PersistentProjectRepositoryStatus,
        initialMessage: String,
        settings: AppSettings,
    ): MainUiState {
        val rootReport = session.rootDiagnostics()
        val selectedResourceId = settings.selectedResourceId
            ?.takeIf { resourceId -> catalog.resources.any { it.id == resourceId } }
            ?: manifest.defaultResourceId?.takeIf { resourceId -> catalog.resources.any { it.id == resourceId } }
            ?: catalog.resources.firstOrNull()?.id
        val visibleTasks = visibleTasks(catalog.tasks, selectedResourceId)
        val selectedTaskId = settings.selectedTaskId
            ?.takeIf { taskId -> visibleTasks.any { it.id == taskId } }
            ?: manifest.defaultTaskId?.takeIf { taskId -> visibleTasks.any { it.id == taskId } }
            ?: visibleTasks.firstOrNull()?.id
        val checkedTaskIds = settings.checkedTaskIds
            .filterTo(linkedSetOf()) { taskId -> visibleTasks.any { it.id == taskId } }
            .ifEmpty { defaultCheckedTaskIds(visibleTasks, selectedTaskId) }
        val taskOptionSelectionsByTask = mergeTaskOptionSelections(catalog.tasks, settings.taskOptionSelectionsByTask)
        val taskInputValuesByTask = mergeTaskInputValues(catalog.tasks, settings.taskInputValuesByTask)
        val selectedPresetId = settings.selectedPresetId
            ?.takeIf { presetId -> catalog.presets.any { it.id == presetId } }
            ?: manifest.defaultPresetId?.takeIf { presetId -> catalog.presets.any { it.id == presetId } }
            ?: catalog.presets.firstOrNull()?.id

        return MainUiState(
            manifest = manifest,
            catalog = catalog,
            resourceRepository = resourceRepository,
            rootReport = rootReport,
            selectedResourceId = selectedResourceId,
            selectedTaskId = selectedTaskId,
            checkedTaskIds = checkedTaskIds,
            taskOptionSelectionsByTask = taskOptionSelectionsByTask,
            taskInputValuesByTask = taskInputValuesByTask,
            selectedPresetId = selectedPresetId,
            overrideJson = normalizeOverrideJson(settings.overrideJson),
            lastMessage = if (initialMessage.isNotBlank()) {
                initialMessage
            } else if (catalog.tasks.isEmpty()) {
                "GitHub 资源仓库已同步，但目录里没有可用任务"
            } else if (rootReport.available) {
                "Ready to connect root runtime"
            } else {
                rootReport.summary
            },
        )
    }

    private fun buildOverrideMap(
        tasks: List<TaskDescriptor>,
        resourceId: String?,
        selectedTaskId: String?,
        overrideJson: String,
    ): Map<String, String> {
        val manualOverride = overrideJson.trim().takeUnless { it.isBlank() || it == "{}" }
        val manualOverrideTarget = selectedTaskId?.takeIf { taskId -> tasks.any { it.id == taskId } }
            ?: tasks.firstOrNull()?.id

        return tasks.mapNotNull { task ->
            val taskOverride = buildTaskOptionOverride(task, resourceId)
            val merged = mergeOverrideJson(
                taskOverride,
                manualOverride.takeIf { task.id == manualOverrideTarget },
            )
            merged?.let { task.id to it }
        }.toMap()
    }

    private fun buildTaskOptionOverride(task: TaskDescriptor, resourceId: String?): String? {
        return buildOptionOverride(
            options = ProjectInterfaceSupport.filterOptionsForResource(task.options, resourceId),
            selectedByOption = _uiState.value.taskOptionSelectionsByTask[task.id].orEmpty(),
            inputValuesByOption = _uiState.value.taskInputValuesByTask[task.id].orEmpty(),
        )
    }

    private fun buildOptionOverride(
        options: List<TaskOptionDescriptor>,
        selectedByOption: Map<String, Set<String>>,
        inputValuesByOption: Map<String, Map<String, String>>,
    ): String? {
        if (options.isEmpty()) {
            return null
        }

        var merged = JsonObject(emptyMap())
        var hasOverride = false
        applyOptionOverrides(
            options = options,
            selectedByOption = selectedByOption,
            inputValuesByOption = inputValuesByOption,
            onMerge = { overrideJson ->
                val overrideObject = parseOverrideObject(overrideJson) ?: return@applyOptionOverrides
                merged = mergeJsonObjects(merged, overrideObject)
                hasOverride = true
            },
        )
        return if (hasOverride) merged.toString() else null
    }

    private fun applyOptionOverrides(
        options: List<TaskOptionDescriptor>,
        selectedByOption: Map<String, Set<String>>,
        inputValuesByOption: Map<String, Map<String, String>>,
        onMerge: (String) -> Unit,
    ) {
        options.forEach { option ->
            when (option.type) {
                TaskOptionType.Switch,
                TaskOptionType.Select,
                TaskOptionType.Checkbox -> {
                    val selectedCaseNames = selectedByOption[option.id].takeUnless { it.isNullOrEmpty() }
                        ?: ProjectInterfaceSupport.defaultSelectionForOption(option)
                    option.cases
                        .filter { it.name in selectedCaseNames }
                        .forEach { optionCase ->
                            onMerge(optionCase.pipelineOverrideJson)
                            applyOptionOverrides(
                                options = optionCase.nestedOptions,
                                selectedByOption = selectedByOption,
                                inputValuesByOption = inputValuesByOption,
                                onMerge = onMerge,
                            )
                        }
                }

                TaskOptionType.Input -> {
                    val values = buildInputValues(option, inputValuesByOption[option.id].orEmpty())
                    onMerge(applyInputPlaceholders(option.pipelineOverrideJson, values))
                }
            }
        }
    }

    private fun buildInputValues(
        option: TaskOptionDescriptor,
        currentValues: Map<String, String>,
    ): Map<String, PipelineInputValue> {
        return option.inputs.associate { input ->
            input.name to PipelineInputValue(
                value = currentValues[input.name] ?: input.defaultValue,
                pipelineType = input.pipelineType,
            )
        }
    }

    private fun applyInputPlaceholders(
        rawJson: String,
        values: Map<String, PipelineInputValue>,
    ): String {
        if (values.isEmpty() || rawJson.isBlank()) {
            return rawJson
        }
        val element = runCatching { json.parseToJsonElement(rawJson) }.getOrNull()
            ?: return replacePlaceholdersInText(rawJson, values)
        return replacePlaceholders(element, values).toString()
    }

    private fun replacePlaceholders(
        element: JsonElement,
        values: Map<String, PipelineInputValue>,
    ): JsonElement {
        return when (element) {
            is JsonObject -> buildJsonObject {
                element.forEach { (key, value) ->
                    put(key, replacePlaceholders(value, values))
                }
            }

            is JsonArray -> buildJsonArray {
                element.forEach { item ->
                    add(replacePlaceholders(item, values))
                }
            }

            is JsonPrimitive -> {
                if (!element.isString) {
                    element
                } else {
                    replaceStringPlaceholder(element.content, values)
                }
            }
        }
    }

    private fun replaceStringPlaceholder(
        rawValue: String,
        values: Map<String, PipelineInputValue>,
    ): JsonElement {
        val exactMatch = inputPlaceholderRegex.matchEntire(rawValue)
        if (exactMatch != null) {
            val key = exactMatch.groupValues[1]
            values[key]?.let(::toJsonPrimitive)?.let { return it }
        }
        return JsonPrimitive(replacePlaceholdersInText(rawValue, values))
    }

    private fun replacePlaceholdersInText(
        rawValue: String,
        values: Map<String, PipelineInputValue>,
    ): String {
        return inputPlaceholderRegex.replace(rawValue) { match ->
            values[match.groupValues[1]]?.value ?: match.value
        }
    }

    private fun toJsonPrimitive(value: PipelineInputValue): JsonPrimitive {
        return when (value.pipelineType.lowercase()) {
            "int" -> value.value.toLongOrNull()?.let(::JsonPrimitive)
                ?: value.value.toDoubleOrNull()?.let(::JsonPrimitive)
                ?: JsonPrimitive(value.value)

            "bool" -> when (value.value.lowercase()) {
                "true" -> JsonPrimitive(true)
                "false" -> JsonPrimitive(false)
                else -> JsonPrimitive(value.value)
            }

            else -> JsonPrimitive(value.value)
        }
    }

    private fun mergeOverrideJson(baseJson: String?, overlayJson: String?): String? {
        val base = parseOverrideObject(baseJson)
        val overlay = parseOverrideObject(overlayJson)
        return when {
            base == null && overlay == null -> null
            base == null -> overlay?.toString()
            overlay == null -> base.toString()
            else -> mergeJsonObjects(base, overlay).toString()
        }
    }

    private fun parseOverrideObject(rawJson: String?): JsonObject? {
        if (rawJson.isNullOrBlank() || rawJson == "{}") {
            return null
        }
        return runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
    }

    private fun mergeJsonObjects(base: JsonObject, overlay: JsonObject): JsonObject {
        return buildJsonObject {
            val keys = base.keys + overlay.keys
            keys.forEach { key ->
                val baseValue = base[key]
                val overlayValue = overlay[key]
                when {
                    baseValue is JsonObject && overlayValue is JsonObject -> put(key, mergeJsonObjects(baseValue, overlayValue))
                    overlayValue != null -> put(key, overlayValue)
                    baseValue != null -> put(key, baseValue)
                }
            }
        }
    }

    private fun validateTaskOptionSelections(tasks: List<TaskDescriptor>, resourceId: String?): String? {
        tasks.forEach { task ->
            val errors = ProjectInterfaceSupport.collectInputValidationErrors(
                options = ProjectInterfaceSupport.filterOptionsForResource(task.options, resourceId),
                selectedByOption = _uiState.value.taskOptionSelectionsByTask[task.id].orEmpty(),
                inputValuesByOption = _uiState.value.taskInputValuesByTask[task.id].orEmpty(),
            )
            if (errors.isNotEmpty()) {
                return "任务「${task.label}」里有未通过校验的输入项"
            }
        }
        return null
    }

    private fun mergeTaskOptionSelections(
        tasks: List<TaskDescriptor>,
        stored: Map<String, Map<String, Set<String>>>,
    ): Map<String, Map<String, Set<String>>> {
        return tasks.mapNotNull { task ->
            val defaults = buildDefaultSelectionsForOptions(task.options)
            val combined = defaults + stored[task.id].orEmpty()
            combined.takeIf { it.isNotEmpty() }?.let { task.id to it }
        }.toMap()
    }

    private fun buildDefaultSelectionsForOptions(options: List<TaskOptionDescriptor>): Map<String, Set<String>> {
        val defaults = mutableMapOf<String, Set<String>>()
        collectDefaultSelections(options, defaults)
        return defaults
    }

    private fun collectDefaultSelections(
        options: List<TaskOptionDescriptor>,
        into: MutableMap<String, Set<String>>,
    ) {
        options.forEach { option ->
            val defaults = ProjectInterfaceSupport.defaultSelectionForOption(option)
            if (defaults.isNotEmpty()) {
                into[option.id] = defaults
            }
            option.cases
                .filter { it.name in defaults }
                .forEach { optionCase ->
                    collectDefaultSelections(optionCase.nestedOptions, into)
                }
        }
    }

    private fun mergeTaskInputValues(
        tasks: List<TaskDescriptor>,
        stored: Map<String, Map<String, Map<String, String>>>,
    ): Map<String, Map<String, Map<String, String>>> {
        return tasks.mapNotNull { task ->
            val defaults = buildDefaultInputValuesForOptions(task.options).toMutableMap()
            stored[task.id].orEmpty().forEach { (optionId, values) ->
                defaults[optionId] = defaults[optionId].orEmpty() + values
            }
            defaults.takeIf { it.isNotEmpty() }?.let { task.id to it }
        }.toMap()
    }

    private fun buildDefaultInputValuesForOptions(options: List<TaskOptionDescriptor>): Map<String, Map<String, String>> {
        val defaults = mutableMapOf<String, Map<String, String>>()
        collectDefaultInputs(options, defaults)
        return defaults
    }

    private fun collectDefaultInputs(
        options: List<TaskOptionDescriptor>,
        into: MutableMap<String, Map<String, String>>,
    ) {
        options.forEach { option ->
            if (option.inputs.isNotEmpty()) {
                into[option.id] = option.inputs.associate { input -> input.name to input.defaultValue }
            }
            val defaults = ProjectInterfaceSupport.defaultSelectionForOption(option)
            option.cases
                .filter { it.name in defaults }
                .forEach { optionCase ->
                    collectDefaultInputs(optionCase.nestedOptions, into)
                }
        }
    }

    private fun updateTaskOptionSelections(
        taskId: String,
        transform: (Map<String, Set<String>>) -> Map<String, Set<String>>,
    ) {
        val task = _uiState.value.catalog.tasks.firstOrNull { it.id == taskId }
        val current = _uiState.value.taskOptionSelectionsByTask[taskId]
            ?: task?.let { buildDefaultSelectionsForOptions(it.options) }
            ?: emptyMap()
        val updated = transform(current).filterValues { it.isNotEmpty() }
        val nextSelections = _uiState.value.taskOptionSelectionsByTask + (taskId to updated)
        _uiState.value = _uiState.value.copy(
            taskOptionSelectionsByTask = nextSelections,
        )
        settingsRepository.saveTaskOptionSelectionsByTask(nextSelections)
    }

    private fun applyImportedSettings(settings: AppSettings) {
        val state = _uiState.value
        val selectedResourceId = settings.selectedResourceId
            ?.takeIf { resourceId -> state.catalog.resources.any { it.id == resourceId } }
            ?: state.selectedResourceId
                ?.takeIf { resourceId -> state.catalog.resources.any { it.id == resourceId } }
            ?: manifest.defaultResourceId
                ?.takeIf { resourceId -> state.catalog.resources.any { it.id == resourceId } }
            ?: state.catalog.resources.firstOrNull()?.id
        val visibleTasks = visibleTasks(state.catalog.tasks, selectedResourceId)
        val selectedTaskId = settings.selectedTaskId
            ?.takeIf { taskId -> visibleTasks.any { it.id == taskId } }
            ?: state.selectedTaskId
                ?.takeIf { taskId -> visibleTasks.any { it.id == taskId } }
            ?: manifest.defaultTaskId
                ?.takeIf { taskId -> visibleTasks.any { it.id == taskId } }
            ?: visibleTasks.firstOrNull()?.id
        val checkedTaskIds = settings.checkedTaskIds
            .filterTo(linkedSetOf()) { taskId -> visibleTasks.any { it.id == taskId } }
            .ifEmpty { defaultCheckedTaskIds(visibleTasks, selectedTaskId) }
        val selectedPresetId = settings.selectedPresetId
            ?.takeIf { presetId -> state.catalog.presets.any { it.id == presetId } }
            ?: state.selectedPresetId
                ?.takeIf { presetId -> state.catalog.presets.any { it.id == presetId } }
            ?: manifest.defaultPresetId
                ?.takeIf { presetId -> state.catalog.presets.any { it.id == presetId } }
            ?: state.catalog.presets.firstOrNull()?.id
        _uiState.value = state.copy(
            selectedResourceId = selectedResourceId,
            selectedTaskId = selectedTaskId,
            checkedTaskIds = checkedTaskIds,
            selectedPresetId = selectedPresetId,
            taskOptionSelectionsByTask = mergeTaskOptionSelections(state.catalog.tasks, settings.taskOptionSelectionsByTask),
            taskInputValuesByTask = mergeTaskInputValues(state.catalog.tasks, settings.taskInputValuesByTask),
            overrideJson = normalizeOverrideJson(settings.overrideJson),
        )
    }

    private fun persistCurrentSettings() {
        settingsRepository.replaceAll(settingsFromState(_uiState.value))
    }

    private fun settingsFromState(state: MainUiState): AppSettings {
        return AppSettings(
            selectedResourceId = state.selectedResourceId,
            selectedTaskId = state.selectedTaskId,
            selectedPresetId = state.selectedPresetId,
            checkedTaskIds = state.checkedTaskIds,
            taskOptionSelectionsByTask = state.taskOptionSelectionsByTask,
            taskInputValuesByTask = state.taskInputValuesByTask,
            overrideJson = normalizeOverrideJson(state.overrideJson),
        )
    }

    private fun normalizeOverrideJson(value: String): String {
        return value.takeUnless { it.isBlank() } ?: "{}"
    }

    private fun <T> runServiceAction(
        actionName: String,
        action: suspend (MaaRuntimeClient) -> T,
        onSuccess: (T) -> Unit,
    ) {
        val client = runtimeClient
        if (client == null) {
            _uiState.value = _uiState.value.copy(lastMessage = "Root runtime is not connected")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, lastMessage = actionName)
            val result = withContext(Dispatchers.IO) {
                runCatching { action(client) }
            }
            result.onSuccess { value ->
                onSuccess(value)
                _uiState.value = _uiState.value.copy(busy = false)
            }.onFailure { error ->
                Log.e(TAG, "Service action failed: $actionName", error)
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    lastMessage = "$actionName failed: ${error.message}",
                )
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        runCatching { runtimeClient?.setMonitorSurface(null) }
        session.disconnect(runtimeClient)
        runtimeClient = null
        super.onCleared()
    }

    private fun MainUiState.selectedPresetOrNull(): PresetDescriptor? {
        return catalog.presets.firstOrNull { it.id == selectedPresetId }
    }

    companion object {
        private const val TAG = "MainViewModel"
        private const val POLL_INTERVAL_MS = 1_000L
        private const val MAX_LOG_LINES = 300
        private val inputPlaceholderRegex = Regex("\\{\\s*([A-Za-z0-9_.-]+)\\s*\\}")

        fun visibleTasks(tasks: List<TaskDescriptor>, resourceId: String?): List<TaskDescriptor> {
            return tasks.filter { task ->
                ProjectInterfaceSupport.taskSupportsResource(task, resourceId)
            }
        }

        fun defaultCheckedTaskIds(tasks: List<TaskDescriptor>, selectedTaskId: String?): Set<String> {
            return tasks
                .filter { it.defaultChecked }
                .mapTo(linkedSetOf()) { it.id }
                .ifEmpty { setOfNotNull(selectedTaskId) }
        }
    }

    private data class PollResult(
        val ping: String,
        val snapshot: RuntimeStateSnapshot,
        val logChunk: RuntimeLogChunk,
    )

    private data class PipelineInputValue(
        val value: String,
        val pipelineType: String,
    )
}
