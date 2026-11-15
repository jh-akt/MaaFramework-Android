package com.maaframework.android.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maaframework.android.host.MaaHostPolicy
import com.maaframework.android.host.MaaHostUiConfig
import com.maaframework.android.host.MaaHostViewModel
import com.maaframework.android.host.hostActions
import com.maaframework.android.model.shouldKeepScreenOn
import kotlinx.coroutines.launch

/** Default host UI, lifecycle and foreground update policy. Apps provide only branding. */
abstract class MaaHostActivity : ComponentActivity() {
    protected abstract val hostVersionName: String
    protected open val hostPreferencesName: String? = null
    protected open val hostPolicy: MaaHostPolicy = MaaHostPolicy()
    protected open fun hostUiConfig(appName: String) = MaaHostUiConfig(
        appName = appName,
        versionName = hostVersionName,
        policy = hostPolicy,
        showSharedOptions = true,
    )

    private val viewModel: MaaHostViewModel by viewModels {
        viewModelFactory {
            initializer {
                MaaHostViewModel(application, preferencesName = hostPreferencesName, taskPolicy = hostPolicy)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state.runtimeState.shouldKeepScreenOn()) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val actions = remember(viewModel) { viewModel.hostActions() }
            MaaHostTheme {
                MaaHostApp(state, actions, hostUiConfig(state.manifest.displayName))
            }
        }
    }

    override fun onStart() { super.onStart(); viewModel.onHostStarted() }
    override fun onStop() { viewModel.onHostStopped(); super.onStop() }
}
