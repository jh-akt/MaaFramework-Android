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
import com.maaframework.android.host.MaaHostUiConfig
import com.maaframework.android.host.MaaHostViewModel
import com.maaframework.android.host.hostActions
import com.maaframework.android.model.shouldKeepScreenOn
import kotlinx.coroutines.launch

/** Default host UI, lifecycle and foreground update policy. Apps provide only branding. */
abstract class MaaHostActivity : ComponentActivity() {
    protected abstract val hostVersionName: String
    private val viewModel: MaaHostViewModel by viewModels()

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
                MaaHostApp(state, actions, MaaHostUiConfig(appName = state.manifest.displayName, versionName = hostVersionName))
            }
        }
    }

    override fun onStart() { super.onStart(); viewModel.onHostStarted() }
    override fun onStop() { viewModel.onHostStopped(); super.onStop() }
}
