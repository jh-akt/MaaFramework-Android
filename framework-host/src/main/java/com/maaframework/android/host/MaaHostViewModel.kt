package com.maaframework.android.host

import android.app.Application
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maaframework.android.catalog.InterfaceCatalogLoader
import com.maaframework.android.catalog.TaskOptionSupport
import com.maaframework.android.model.CatalogSnapshot
import com.maaframework.android.model.MaaLogLevels
import com.maaframework.android.model.PresetDescriptor
import com.maaframework.android.model.RootEnvironmentReport
import com.maaframework.android.model.RunRequest
import com.maaframework.android.model.RuntimeLogChunk
import com.maaframework.android.model.RuntimeStateSnapshot
import com.maaframework.android.model.TaskDescriptor
import com.maaframework.android.model.TaskOptionDescriptor
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
import kotlinx.coroutines.flow.update
import com.maaframework.android.catalog.MaaOptionOverrides.buildOptionOverride
import com.maaframework.android.catalog.MaaOptionOverrides.mergeOverrideJson
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class MaaHostViewModel @JvmOverloads constructor(
    application: Application,
    preferencesName: String? = null,
    displayPowerPolicy: MaaDisplayPowerPolicy = MaaDisplayPowerPolicy(),
) : AndroidViewModel(application) {
    private val manifest = MaaProjectManifestLoader.loadOrDefault(application.assets)
    private val catalogLoader = InterfaceCatalogLoader(application.assets, manifest.supportedControllers, manifest.bundledResourcePath.orEmpty())
    private val session = MaaFrameworkSession(application)
    private val settingsRepository = MaaHostSettingsRepository(application, preferencesName ?: "${manifest.projectId}_android_settings", manifest.projectId)


    private val _uiState = MutableStateFlow(MaaHostState())
    val uiState: StateFlow<MaaHostState> = _uiState.asStateFlow()

    private var runtimeClient: MaaRuntimeClient? = null
    private var pollJob: Job? = null
    private var connectJob: Job? = null
    private var previewSurface: Surface? = null
    private var logCursor = 0L
    private val displayPowerRecovery = MaaDisplayPowerRecovery(application, viewModelScope, displayPowerPolicy) {
        _uiState.update { it.copy(lastMessage = "检测到亮屏，已退出息屏挂机") }
    }

    init {
        val appSettings = settingsRepository.load()
        val resourceRepository = PersistentProjectRepositoryManager.loadStatus(application, manifest)
        var initialMessage = resourceRepository.lastError
            ?.takeIf { it.isNotBlank() }
            ?: if (resourceRepository.available) "项目资源已就绪" else "GitHub 资源仓库未就绪"
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
        _uiState.update { current -> current.copy(
            selectedResourceId = resourceId,
            selectedTaskId = selectedTaskId,
            checkedTaskIds = checkedTaskIds,
        ) }
        settingsRepository.saveSelectedResourceId(resourceId)
        settingsRepository.saveSelectedTaskId(selectedTaskId)
        settingsRepository.saveCheckedTaskIds(checkedTaskIds)
    }

    fun selectTab(tab: MaaHostTab) {
        _uiState.update { current -> current.copy(activeTab = tab) }
    }

    fun selectTask(taskId: String) {
        _uiState.update { current -> current.copy(selectedTaskId = taskId) }
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
        _uiState.update { current -> current.copy(checkedTaskIds = updated) }
        settingsRepository.saveCheckedTaskIds(updated)
    }

    fun selectPreset(presetId: String) {
        _uiState.update { current -> current.copy(selectedPresetId = presetId) }
        settingsRepository.saveSelectedPresetId(presetId)
    }

    fun updateOverrideJson(value: String) {
        _uiState.update { current -> current.copy(overrideJson = value) }
        settingsRepository.saveOverrideJson(value)
    }

    fun updateLogLevel(value: String) {
        val normalized = MaaLogLevels.normalize(value)
        _uiState.update { current -> current.copy(logLevel = normalized) }
        settingsRepository.saveLogLevel(normalized)
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
        _uiState.update { current -> current.copy(
            taskInputValuesByTask = nextValues,
        ) }
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

    fun requestClearResourceRepositoryConfirmation() {
        if (_uiState.value.resourceRepositoryUpdating) {
            return
        }
        _uiState.update { current -> current.copy(resourceRepositoryClearConfirmVisible = true) }
    }

    fun dismissClearResourceRepositoryConfirmation() {
        _uiState.update { current -> current.copy(resourceRepositoryClearConfirmVisible = false) }
    }

    fun clearResourceRepository() {
        viewModelScope.launch {
            clearPersistentResourceRepository()
        }
    }

    fun requestRootAndConnect(silent: Boolean = false) {
        if (connectJob?.isActive == true) {
            return
        }

        connectJob = viewModelScope.launch {
            val rootReport = session.rootDiagnostics()
            _uiState.update { current -> current.copy(
                rootReport = rootReport,
                busy = true,
                lastMessage = if (silent) current.lastMessage else "Checking root runtime",
            ) }
            if (!rootReport.available) {
                _uiState.update { current -> current.copy(
                    busy = false,
                    lastMessage = rootReport.summary,
                ) }
                return@launch
            }

            if (!rootReport.granted) {
                val granted = session.requestRootPermission()
                val refreshedReport = session.rootDiagnostics()
                _uiState.update { current -> current.copy(rootReport = refreshedReport) }
                if (!granted) {
                    _uiState.update { current -> current.copy(
                        busy = false,
                        lastMessage = refreshedReport.summary.ifBlank { "Root permission was not granted" },
                    ) }
                    return@launch
                }
            }

            val result = session.connectClient()
            result.onSuccess { client ->
                bindService(client)
            }.onFailure { error ->
                Log.e(TAG, "Failed to connect root runtime", error)
                _uiState.update { current -> current.copy(
                    busy = false,
                    rootConnected = false,
                    lastMessage = "Failed to connect root runtime: ${error.message}",
                ) }
            }
        }
    }

    fun prepareRuntime() {
        runServiceAction(
            actionName = "Preparing runtime",
            action = { it.prepareRuntime() },
            onSuccess = { prepared ->
                _uiState.update { current -> current.copy(
                    lastMessage = if (prepared) "Runtime preparation queued" else "Runtime prepare request failed",
                ) }
            },
        )
    }

    fun startWindowedGame() {
        val resourceId = _uiState.value.selectedResourceId
        runServiceAction(
            actionName = "Opening game",
            action = { it.startWindowedGame(resourceId) },
            onSuccess = { opened ->
                _uiState.update { current -> current.copy(
                    lastMessage = if (opened) {
                        "Windowed game launch queued"
                    } else {
                        "Windowed game launch failed"
                    },
                ) }
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
                if (changed) {
                    val state = _uiState.value.runtimeState.copy(displayPowerOffActive = !restoreScreen)
                    displayPowerRecovery.observe(runtimeClient, state)
                    _uiState.update { it.copy(runtimeState = state) }
                }
                _uiState.update { current -> current.copy(
                    lastMessage = if (changed) {
                        if (restoreScreen) "Screen power restored" else "Screen turned off for background run"
                    } else {
                        "Display power change failed"
                    },
                ) }
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
            _uiState.update { current -> current.copy(lastMessage = "Select a task first") }
            return
        }
        val requestedTasks = requestedTaskIds.mapNotNull { taskId ->
            visibleTasks.firstOrNull { it.id == taskId }
        }
        val validationError = validateTaskOptionSelections(requestedTasks, state.selectedResourceId)
        if (validationError != null) {
            _uiState.update { current -> current.copy(lastMessage = validationError) }
            return
        }

        val request = RunRequest(
            taskId = requestedTaskIds.first(),
            sequenceTaskIds = requestedTaskIds,
            resourceName = state.selectedResourceId ?: manifest.defaultResourceId.orEmpty(),
            logLevel = state.logLevel,
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
            _uiState.update { current -> current.copy(lastMessage = "Select a preset first") }
            return
        }

        val visibleTasks = visibleTasks(state.catalog.tasks, state.selectedResourceId)
        val requestedTasks = preset.taskIds.mapNotNull { taskId ->
            visibleTasks.firstOrNull { it.id == taskId }
        }
        if (requestedTasks.isEmpty()) {
            _uiState.update { current -> current.copy(lastMessage = "Preset ${preset.label} has no tasks for the selected resource") }
            return
        }
        val validationError = validateTaskOptionSelections(requestedTasks, state.selectedResourceId)
        if (validationError != null) {
            _uiState.update { current -> current.copy(lastMessage = validationError) }
            return
        }

        val request = RunRequest(
            taskId = requestedTasks.first().id,
            presetId = preset.id,
            sequenceTaskIds = requestedTasks.map { it.id },
            resourceName = state.selectedResourceId ?: manifest.defaultResourceId.orEmpty(),
            logLevel = state.logLevel,
            optionOverridesByTask = buildOverrideMap(
                tasks = requestedTasks,
                resourceId = state.selectedResourceId,
                selectedTaskId = state.selectedTaskId,
                overrideJson = state.overrideJson,
            ),
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
                _uiState.update { current -> current.copy(lastMessage = "Stop requested") }
            },
        )
    }

    fun exportDiagnostics() {
        runServiceAction(
            actionName = "Exporting diagnostics",
            action = { it.exportDiagnostics() },
            onSuccess = { path ->
                _uiState.update { current -> current.copy(lastMessage = "Diagnostics exported: $path") }
            },
        )
    }

    fun exportConfig(outputStream: OutputStream) {
        runCatching {
            persistCurrentSettings()
            settingsRepository.exportTo(outputStream)
        }.onSuccess {
            _uiState.update { current -> current.copy(lastMessage = "配置已导出") }
        }.onFailure { error ->
            Log.e(TAG, "Failed to export config", error)
            _uiState.update { current -> current.copy(
                lastMessage = "配置导出失败：${error.message ?: error::class.java.simpleName}",
            ) }
        }
    }

    fun importConfig(inputStream: InputStream) {
        runCatching {
            val imported = settingsRepository.importFrom(inputStream)
            applyImportedSettings(imported)
        }.onSuccess {
            _uiState.update { current -> current.copy(lastMessage = "配置已导入") }
        }.onFailure { error ->
            Log.e(TAG, "Failed to import config", error)
            _uiState.update { current -> current.copy(
                lastMessage = "配置导入失败：${error.message ?: error::class.java.simpleName}",
            ) }
        }
    }

    private fun startRun(request: RunRequest, label: String) {
        runServiceAction(
            actionName = label,
            action = { client -> client.startRun(request) },
            onSuccess = { started ->
                _uiState.update { current -> current.copy(
                    lastMessage = if (started) label else "Run request was rejected",
                ) }
            },
        )
    }

    private fun bindService(client: MaaRuntimeClient) {
        runtimeClient = client
        logCursor = 0L
        runCatching { client.setMonitorSurface(previewSurface) }
        _uiState.update { current -> current.copy(
            busy = false,
            rootConnected = true,
            rootReport = session.rootDiagnostics(),
            lastMessage = "Root runtime connected",
        ) }
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

                if (runtimeClient !== client) break
                result.onSuccess { poll ->
                    displayPowerRecovery.observe(client, poll.snapshot)
                    logCursor = poll.logChunk.nextOffsetBytes
                    _uiState.update { current -> current.copy(
                        servicePing = poll.ping,
                        runtimeState = poll.snapshot,
                        displayLogs = mergeLogs(current.displayLogs, poll.logChunk),
                        lastMessage = poll.snapshot.lastMessage.ifBlank { current.lastMessage },
                        rootConnected = true,
                        busy = false,
                    ) }
                }.onFailure { error ->
                    Log.e(TAG, "Polling root runtime failed", error)
                    val rootReport = session.rootDiagnostics()
                    _uiState.update { current -> current.copy(
                        rootReport = rootReport,
                        rootConnected = false,
                        busy = false,
                        lastMessage = "Root runtime disconnected: ${error.message ?: rootReport.summary}",
                    ) }
                    session.disconnect(client)
                    runtimeClient = null
                    displayPowerRecovery.observe(null, RuntimeStateSnapshot())
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
        _uiState.update { current -> current.copy(
            resourceRepositoryUpdating = true,
            resourceRepositoryProgress = PersistentProjectRepositorySyncProgress(
                fraction = 0f,
                label = if (force) "正在同步 GitHub 资源" else "正在检查 GitHub 资源",
            ),
            lastMessage = if (silent) current.lastMessage else "正在同步 GitHub 资源",
        ) }
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
        _uiState.update { current -> current.copy(
            resourceRepository = status,
            resourceRepositoryUpdating = false,
            resourceRepositoryProgress = null,
            lastMessage = nextMessage,
        ) }
    }

    private fun updateResourceRepositoryProgress(progress: PersistentProjectRepositorySyncProgress) {
        _uiState.update { current -> current.copy(resourceRepositoryProgress = progress) }
    }

    private suspend fun clearPersistentResourceRepository() {
        if (!manifest.hasGitHubResourceRepository() || _uiState.value.resourceRepositoryUpdating) {
            return
        }
        _uiState.update { current -> current.copy(
            resourceRepositoryClearConfirmVisible = false,
            resourceRepositoryUpdating = true,
            resourceRepositoryProgress = PersistentProjectRepositorySyncProgress(
                fraction = 0f,
                label = "正在清空 GitHub 资源缓存",
            ),
            lastMessage = "正在清空 GitHub 资源缓存",
        ) }
        val application = getApplication<Application>()
        val status = runCatching {
            withContext(Dispatchers.IO) {
                PersistentProjectRepositoryManager.clearLocalCache(application, manifest)
            }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to clear GitHub resource repository cache", error)
            PersistentProjectRepositoryManager.loadStatus(application, manifest).copy(
                lastError = error.message ?: error::class.java.simpleName,
            )
        }
        refreshCatalogSnapshot(status)
        _uiState.update { current -> current.copy(
            resourceRepository = status,
            resourceRepositoryUpdating = false,
            resourceRepositoryProgress = null,
            lastMessage = if (status.lastError.isNullOrBlank()) {
                "GitHub 资源缓存已清空，后续更新会重新下载"
            } else {
                "清空 GitHub 资源失败：${status.lastError}"
            },
        ) }
    }

    private fun loadCatalogSnapshot(resourceRepository: PersistentProjectRepositoryStatus): CatalogSnapshot {
        val application = getApplication<Application>()
        return if (resourceRepository.available && manifest.hasGitHubResourceRepository()) {
            catalogLoader.loadFromDirectory(PersistentProjectRepositoryManager.currentRoot(application, manifest))
        } else if (manifest.bundledResourcePath != null) {
            catalogLoader.load()
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
        _uiState.update { current -> current.copy(
            catalog = catalog,
            selectedResourceId = selectedResourceId,
            selectedTaskId = selectedTaskId,
            checkedTaskIds = checkedTaskIds,
            taskOptionSelectionsByTask = mergeTaskOptionSelections(catalog.tasks, optionSelections),
            taskInputValuesByTask = mergeTaskInputValues(catalog.tasks, inputValues),
            selectedPresetId = selectedPresetId,
        ) }
    }

    private fun buildInitialState(
        catalog: CatalogSnapshot,
        resourceRepository: PersistentProjectRepositoryStatus,
        initialMessage: String,
        settings: MaaHostSettings,
    ): MaaHostState {
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

        return MaaHostState(
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
            logLevel = settings.logLevel,
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

        val catalog = _uiState.value.catalog
        val sharedDefaults = buildOptionOverride(
            options = catalog.globalOptions + catalog.resources.firstOrNull { it.id == resourceId }?.options.orEmpty(),
            selectedByOption = emptyMap(),
            inputValuesByOption = emptyMap(),
        )
        return tasks.mapNotNull { task ->
            val taskOverride = mergeOverrideJson(sharedDefaults, buildTaskOptionOverride(task, resourceId))
            val merged = mergeOverrideJson(
                taskOverride,
                manualOverride.takeIf { task.id == manualOverrideTarget },
            )
            merged?.let { task.id to it }
        }.toMap()
    }

    private fun buildTaskOptionOverride(task: TaskDescriptor, resourceId: String?): String? {
        return buildOptionOverride(
            options = TaskOptionSupport.filterOptionsForResource(task.options, resourceId),
            selectedByOption = _uiState.value.taskOptionSelectionsByTask[task.id].orEmpty(),
            inputValuesByOption = _uiState.value.taskInputValuesByTask[task.id].orEmpty(),
        )
    }

    private fun validateTaskOptionSelections(tasks: List<TaskDescriptor>, resourceId: String?): String? {
        tasks.forEach { task ->
            val errors = TaskOptionSupport.collectInputValidationErrors(
                options = TaskOptionSupport.filterOptionsForResource(task.options, resourceId),
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
            val defaults = TaskOptionSupport.defaultSelectionForOption(option)
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
            val defaults = TaskOptionSupport.defaultSelectionForOption(option)
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
        _uiState.update { current -> current.copy(
            taskOptionSelectionsByTask = nextSelections,
        ) }
        settingsRepository.saveTaskOptionSelectionsByTask(nextSelections)
    }

    private fun applyImportedSettings(settings: MaaHostSettings) {
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
        _uiState.update { current -> current.copy(
            selectedResourceId = selectedResourceId,
            selectedTaskId = selectedTaskId,
            checkedTaskIds = checkedTaskIds,
            selectedPresetId = selectedPresetId,
            taskOptionSelectionsByTask = mergeTaskOptionSelections(state.catalog.tasks, settings.taskOptionSelectionsByTask),
            taskInputValuesByTask = mergeTaskInputValues(state.catalog.tasks, settings.taskInputValuesByTask),
            overrideJson = normalizeOverrideJson(settings.overrideJson),
            logLevel = settings.logLevel,
        ) }
    }

    private fun persistCurrentSettings() {
        settingsRepository.replaceAll(settingsFromState(_uiState.value))
    }

    private fun settingsFromState(state: MaaHostState): MaaHostSettings {
        return MaaHostSettings(
            selectedResourceId = state.selectedResourceId,
            selectedTaskId = state.selectedTaskId,
            selectedPresetId = state.selectedPresetId,
            checkedTaskIds = state.checkedTaskIds,
            taskOptionSelectionsByTask = state.taskOptionSelectionsByTask,
            taskInputValuesByTask = state.taskInputValuesByTask,
            overrideJson = normalizeOverrideJson(state.overrideJson),
            logLevel = state.logLevel,
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
            _uiState.update { current -> current.copy(lastMessage = "Root runtime is not connected") }
            return
        }

        viewModelScope.launch {
            _uiState.update { current -> current.copy(busy = true, lastMessage = actionName) }
            val result = withContext(Dispatchers.IO) {
                runCatching { action(client) }
            }
            if (runtimeClient !== client) return@launch
            result.onSuccess { value ->
                onSuccess(value)
                _uiState.update { current -> current.copy(busy = false) }
            }.onFailure { error ->
                Log.e(TAG, "Service action failed: $actionName", error)
                _uiState.update { current -> current.copy(
                    busy = false,
                    lastMessage = "$actionName failed: ${error.message}",
                ) }
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        displayPowerRecovery.close()
        runCatching { runtimeClient?.setMonitorSurface(null) }
        session.disconnect(runtimeClient)
        runtimeClient = null
        super.onCleared()
    }

    private fun MaaHostState.selectedPresetOrNull(): PresetDescriptor? {
        return catalog.presets.firstOrNull { it.id == selectedPresetId }
    }

    companion object {
        private const val TAG = "MaaHostViewModel"
        private const val POLL_INTERVAL_MS = 1_000L
        private const val MAX_LOG_LINES = 300

        fun visibleTasks(tasks: List<TaskDescriptor>, resourceId: String?): List<TaskDescriptor> {
            return tasks.filter { task ->
                TaskOptionSupport.taskSupportsResource(task, resourceId)
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
}

fun MaaHostViewModel.hostActions() = MaaHostActions(
    selectTab = ::selectTab,
    selectResource = ::selectResource,
    selectTask = ::selectTask,
    toggleTaskChecked = ::toggleTaskChecked,
    selectPreset = ::selectPreset,
    updateOverrideJson = ::updateOverrideJson,
    updateLogLevel = ::updateLogLevel,
    updateTaskSwitchOption = ::updateTaskSwitchOption,
    toggleTaskCheckboxOption = ::toggleTaskCheckboxOption,
    updateTaskInputValue = ::updateTaskInputValue,
    refreshResourceRepository = ::refreshResourceRepository,
    requestClearResourceRepositoryConfirmation = ::requestClearResourceRepositoryConfirmation,
    dismissClearResourceRepositoryConfirmation = ::dismissClearResourceRepositoryConfirmation,
    clearResourceRepository = ::clearResourceRepository,
    requestRootAndConnect = { requestRootAndConnect() },
    prepareRuntime = ::prepareRuntime,
    startWindowedGame = ::startWindowedGame,
    startSelectedTask = ::startSelectedTask,
    startSelectedPreset = ::startSelectedPreset,
    stopRun = ::stopRun,
    toggleDisplayPower = ::toggleDisplayPower,
    exportDiagnostics = ::exportDiagnostics,
    exportConfig = ::exportConfig,
    importConfig = ::importConfig,
    setPreviewSurface = ::setPreviewSurface,
    onPreviewTouchDown = ::onPreviewTouchDown,
    onPreviewTouchMove = ::onPreviewTouchMove,
    onPreviewTouchUp = ::onPreviewTouchUp,
)

