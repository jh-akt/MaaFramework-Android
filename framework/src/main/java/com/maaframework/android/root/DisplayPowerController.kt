package com.maaframework.android.root

import android.os.Build
import com.maaframework.android.preview.hidden.DisplayControl
import com.maaframework.android.preview.hidden.SurfaceControl
import java.io.File

internal object DisplayPowerController {
    private const val FLAG_PATH = "/data/local/tmp/maaframework_display_power_off_flag"
    private val flagFile = File(FLAG_PATH)

    fun isDisplayPowerOffActive(): Boolean {
        return runCatching { flagFile.exists() }.getOrDefault(false)
    }

    fun recoverIfNeeded(logger: ((String) -> Unit)? = null) {
        if (!isDisplayPowerOffActive()) {
            return
        }
        logger?.invoke("Detected stale display power off flag, recovering screen power")
        runCatching { setDisplayPower(true) }
            .onFailure { error ->
                logger?.invoke("Display power recovery failed: ${error.message}")
            }
    }

    fun setDisplayPower(on: Boolean): Boolean {
        val success = setDisplayPowerInternal(on)
        if (success) {
            setFlag(!on)
        }
        return success
    }

    fun destroy(logger: ((String) -> Unit)? = null) {
        if (!isDisplayPowerOffActive()) {
            return
        }
        logger?.invoke("Restoring screen power before shutting down root runtime")
        runCatching { setDisplayPower(true) }
            .onFailure { error ->
                logger?.invoke("Failed to restore screen power: ${error.message}")
            }
    }

    private fun setDisplayPowerInternal(on: Boolean): Boolean {
        val mode = if (on) SurfaceControl.POWER_MODE_NORMAL else SurfaceControl.POWER_MODE_OFF
        val applyToPhysicalDisplays = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        if (applyToPhysicalDisplays) {
            val useDisplayControl =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    !SurfaceControl.hasGetPhysicalDisplayIdsMethod() &&
                    DisplayControl.available()

            val displayIds = if (useDisplayControl) {
                DisplayControl.getPhysicalDisplayIds()
            } else {
                SurfaceControl.getPhysicalDisplayIds()
            } ?: return false

            var allOk = true
            for (displayId in displayIds) {
                val token = if (useDisplayControl) {
                    DisplayControl.getPhysicalDisplayToken(displayId)
                } else {
                    SurfaceControl.getPhysicalDisplayToken(displayId)
                } ?: return false
                allOk = SurfaceControl.setDisplayPowerMode(token, mode) && allOk
            }
            return allOk
        }

        val builtInDisplay = SurfaceControl.getBuiltInDisplay() ?: return false
        return SurfaceControl.setDisplayPowerMode(builtInDisplay, mode)
    }

    private fun setFlag(enabled: Boolean) {
        runCatching {
            if (enabled) {
                flagFile.parentFile?.mkdirs()
                if (!flagFile.exists()) {
                    flagFile.createNewFile()
                }
            } else if (flagFile.exists()) {
                flagFile.delete()
            }
        }
    }
}
