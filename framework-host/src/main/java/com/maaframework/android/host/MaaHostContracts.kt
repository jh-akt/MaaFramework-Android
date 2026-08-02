package com.maaframework.android.host

import android.view.Surface
import com.maaframework.android.model.CatalogSnapshot
import com.maaframework.android.model.RootEnvironmentReport
import com.maaframework.android.model.RuntimeStateSnapshot
import com.maaframework.android.project.MaaProjectManifest
import com.maaframework.android.runtime.PersistentProjectRepositoryStatus
import com.maaframework.android.runtime.PersistentProjectRepositorySyncProgress
import java.io.InputStream
import java.io.OutputStream

enum class MaaHostTab {
    Home,
    Tasks,
    Settings,
    Logs,
}

data class MaaHostState(
    val activeTab: MaaHostTab = MaaHostTab.Home,
    val manifest: MaaProjectManifest = MaaProjectManifest(),
    val catalog: CatalogSnapshot = CatalogSnapshot(),
    val resourceRepository: PersistentProjectRepositoryStatus = PersistentProjectRepositoryStatus(),
    val resourceRepositoryUpdating: Boolean = false,
    val resourceRepositoryProgress: PersistentProjectRepositorySyncProgress? = null,
    val resourceRepositoryClearConfirmVisible: Boolean = false,
    val rootReport: RootEnvironmentReport = RootEnvironmentReport(),
    val rootConnected: Boolean = false,
    val servicePing: String = "",
    val runtimeState: RuntimeStateSnapshot = RuntimeStateSnapshot(),
    val selectedResourceId: String? = null,
    val selectedTaskId: String? = null,
    val checkedTaskIds: Set<String> = emptySet(),
    val taskOptionSelectionsByTask: Map<String, Map<String, Set<String>>> = emptyMap(),
    val taskInputValuesByTask: Map<String, Map<String, Map<String, String>>> = emptyMap(),
    val sharedOptionSelectionsByScope: Map<String, Map<String, Set<String>>> = emptyMap(),
    val sharedInputValuesByScope: Map<String, Map<String, Map<String, String>>> = emptyMap(),
    val selectedPresetId: String? = null,
    val overrideJson: String = "{}",
    val logLevel: String = "info",
    val displayLogs: List<String> = emptyList(),
    val lastMessage: String = "",
    val busy: Boolean = false,
)

data class MaaHostActions(
    val selectTab: (MaaHostTab) -> Unit,
    val selectResource: (String) -> Unit,
    val selectTask: (String) -> Unit,
    val toggleTaskChecked: (String, Boolean) -> Unit,
    val selectPreset: (String) -> Unit = {},
    val updateOverrideJson: (String) -> Unit = {},
    val updateLogLevel: (String) -> Unit,
    val updateTaskSwitchOption: (String, String, String) -> Unit,
    val toggleTaskCheckboxOption: (String, String, String) -> Unit,
    val updateTaskInputValue: (String, String, String, String) -> Unit,
    val updateSharedSwitchOption: (String, String, String) -> Unit = { _, _, _ -> },
    val toggleSharedCheckboxOption: (String, String, String) -> Unit = { _, _, _ -> },
    val updateSharedInputValue: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    val refreshResourceRepository: () -> Unit,
    val requestClearResourceRepositoryConfirmation: () -> Unit,
    val dismissClearResourceRepositoryConfirmation: () -> Unit,
    val clearResourceRepository: () -> Unit,
    val requestRootAndConnect: () -> Unit,
    val prepareRuntime: () -> Unit,
    val startWindowedGame: () -> Unit,
    val startSelectedTask: () -> Unit,
    val startSelectedPreset: () -> Unit = {},
    val stopRun: () -> Unit,
    val toggleDisplayPower: () -> Unit,
    val exportDiagnostics: () -> Unit,
    val exportConfig: (OutputStream) -> Unit,
    val importConfig: (InputStream) -> Unit,
    val setPreviewSurface: (Surface?) -> Unit,
    val onPreviewTouchDown: (Int, Int, Int) -> Boolean,
    val onPreviewTouchMove: (Int, Int, Int) -> Boolean,
    val onPreviewTouchUp: (Int, Int, Int) -> Boolean,
)

data class MaaHostUiConfig(
    val appName: String,
    val versionName: String = "",
    val policy: MaaHostPolicy = MaaHostPolicy(),
    val showSharedOptions: Boolean = false,
    val showManualOverride: Boolean = true,
    val showPresetRun: Boolean = true,
)
