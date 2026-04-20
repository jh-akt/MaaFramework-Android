package com.maaframework.android.sample.bbb

import android.app.Application
import android.util.Log
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
import com.maaframework.android.project.MaaProjectManifest
import com.maaframework.android.project.MaaProjectManifestLoader
import com.maaframework.android.session.MaaFrameworkSession
import com.maaframework.android.session.MaaRuntimeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val manifest: MaaProjectManifest = MaaProjectManifest(),
    val catalog: CatalogSnapshot = CatalogSnapshot(),
    val rootReport: RootEnvironmentReport = RootEnvironmentReport(),
    val rootConnected: Boolean = false,
    val servicePing: String = "",
    val runtimeState: RuntimeStateSnapshot = RuntimeStateSnapshot(),
    val selectedResourceId: String? = null,
    val selectedTaskId: String? = null,
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

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var runtimeClient: MaaRuntimeClient? = null
    private var pollJob: Job? = null
    private var connectJob: Job? = null
    private var logCursor = 0L

    init {
        val catalog = runCatching { catalogLoader.load() }
            .getOrElse { error ->
                Log.e(TAG, "Failed to load interface catalog", error)
                CatalogSnapshot()
            }
        _uiState.value = buildInitialState(catalog)
        if (_uiState.value.rootReport.available) {
            requestRootAndConnect(silent = true)
        }
    }

    fun selectResource(resourceId: String) {
        val visibleTasks = visibleTasks(_uiState.value.catalog.tasks, resourceId)
        val selectedTaskId = _uiState.value.selectedTaskId
            ?.takeIf { taskId -> visibleTasks.any { it.id == taskId } }
            ?: visibleTasks.firstOrNull()?.id
        _uiState.value = _uiState.value.copy(
            selectedResourceId = resourceId,
            selectedTaskId = selectedTaskId,
        )
    }

    fun selectTask(taskId: String) {
        _uiState.value = _uiState.value.copy(selectedTaskId = taskId)
    }

    fun selectPreset(presetId: String) {
        _uiState.value = _uiState.value.copy(selectedPresetId = presetId)
    }

    fun updateOverrideJson(value: String) {
        _uiState.value = _uiState.value.copy(overrideJson = value)
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

    fun startSelectedTask() {
        val state = _uiState.value
        val taskId = state.selectedTaskId
        if (taskId == null) {
            _uiState.value = state.copy(lastMessage = "Select a task first")
            return
        }

        val request = RunRequest(
            taskId = taskId,
            sequenceTaskIds = listOf(taskId),
            resourceName = state.selectedResourceId ?: manifest.defaultResourceId.orEmpty(),
            optionOverridesByTask = buildOverrideMap(taskId, state.overrideJson),
        )
        startRun(request, "Starting task $taskId")
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

    private fun buildInitialState(catalog: CatalogSnapshot): MainUiState {
        val rootReport = session.rootDiagnostics()
        val selectedResourceId = manifest.defaultResourceId ?: catalog.resources.firstOrNull()?.id
        val selectedTaskId = manifest.defaultTaskId
            ?.takeIf { taskId -> visibleTasks(catalog.tasks, selectedResourceId).any { it.id == taskId } }
            ?: visibleTasks(catalog.tasks, selectedResourceId).firstOrNull()?.id
        val selectedPresetId = manifest.defaultPresetId
            ?.takeIf { presetId -> catalog.presets.any { it.id == presetId } }
            ?: catalog.presets.firstOrNull()?.id

        return MainUiState(
            manifest = manifest,
            catalog = catalog,
            rootReport = rootReport,
            selectedResourceId = selectedResourceId,
            selectedTaskId = selectedTaskId,
            selectedPresetId = selectedPresetId,
            lastMessage = if (catalog.tasks.isEmpty()) {
                "Project assets were not found or do not expose supported controllers"
            } else if (rootReport.available) {
                "Ready to connect root runtime"
            } else {
                rootReport.summary
            },
        )
    }

    private fun buildOverrideMap(taskId: String, overrideJson: String): Map<String, String> {
        val trimmed = overrideJson.trim()
        return if (trimmed.isBlank() || trimmed == "{}") {
            emptyMap()
        } else {
            mapOf(taskId to trimmed)
        }
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

        fun visibleTasks(tasks: List<TaskDescriptor>, resourceId: String?): List<TaskDescriptor> {
            return tasks.filter { task ->
                task.supportedResources.isEmpty() || resourceId == null || resourceId in task.supportedResources
            }
        }
    }

    private data class PollResult(
        val ping: String,
        val snapshot: RuntimeStateSnapshot,
        val logChunk: RuntimeLogChunk,
    )
}
