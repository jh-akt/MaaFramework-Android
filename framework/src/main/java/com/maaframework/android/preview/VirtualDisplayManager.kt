package com.maaframework.android.preview

import android.content.Context
import android.hardware.display.DisplayManager as AndroidDisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.util.Log
import android.view.Surface
import com.maaframework.android.bridge.NativeBridgeLib
import com.maaframework.android.preview.hidden.ServiceManager
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object VirtualDisplayManager {
    private const val TAG = "VirtualDisplayManager"
    private const val STATE_IDLE = 0
    private const val STATE_CAPTURING = 1

    private const val VIRTUAL_DISPLAY_FLAG_PUBLIC = AndroidDisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    private const val VIRTUAL_DISPLAY_FLAG_PRESENTATION = AndroidDisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = AndroidDisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
    private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
    private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
    private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 shl 14
    private const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15
    private const val VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    data class DisplayConfig(
        val width: Int = DefaultDisplayConfig.WIDTH,
        val height: Int = DefaultDisplayConfig.HEIGHT,
        val dpi: Int = DefaultDisplayConfig.DPI,
    )

    private val state = AtomicInteger(STATE_IDLE)
    private val config = AtomicReference(DisplayConfig())
    private val displayId = AtomicInteger(DefaultDisplayConfig.DISPLAY_NONE)
    private val virtualDisplay = AtomicReference<VirtualDisplay?>()
    private val monitorSurface = AtomicReference<Surface?>()

    fun setMonitorSurface(surface: Surface?) {
        Log.i(TAG, "setMonitorSurface surface=${surface != null}")
        monitorSurface.set(surface)
        NativeBridgeLib.setPreviewSurface(surface)
        if (surface != null && state.get() == STATE_IDLE) {
            start(currentContext ?: return)
        }
    }

    @Volatile
    private var currentContext: Context? = null

    fun start(context: Context): Int {
        currentContext = context.applicationContext
        Log.i(TAG, "start()")
        if (!state.compareAndSet(STATE_IDLE, STATE_CAPTURING)) {
            Log.i(TAG, "already capturing, displayId=${displayId.get()}")
            return displayId.get()
        }
        return startInternal(context.applicationContext)
    }

    fun stop() {
        if (!state.compareAndSet(STATE_CAPTURING, STATE_IDLE)) {
            return
        }
        Log.i(TAG, "stop()")
        monitorSurface.set(null)
        NativeBridgeLib.setPreviewSurface(null)
        releaseResources()
    }

    fun restart() {
        val context = currentContext ?: return
        if (state.get() != STATE_CAPTURING) {
            return
        }
        releaseResources()
        startInternal(context)
    }

    fun getDisplayId(): Int = displayId.get()

    private fun startInternal(context: Context): Int {
        val cfg = config.get()
        val surface = NativeBridgeLib.setupNativeCapturer(cfg.width, cfg.height) ?: return DefaultDisplayConfig.DISPLAY_NONE
        return try {
            createVirtualDisplay(context, surface, cfg).also { displayId.set(it) }
        } catch (e: Exception) {
            Log.e(TAG, "startInternal failed", e)
            state.set(STATE_IDLE)
            DefaultDisplayConfig.DISPLAY_NONE
        }
    }

    private fun releaseResources() {
        Log.i(TAG, "releaseResources()")
        virtualDisplay.getAndSet(null)?.release()
        displayId.set(DefaultDisplayConfig.DISPLAY_NONE)
        NativeBridgeLib.releaseNativeCapturer()
    }

    private fun createVirtualDisplay(context: Context, surface: Surface, cfg: DisplayConfig): Int {
        val flags = buildDisplayFlags()
        val vd = ServiceManager.getDisplayManager().createNewVirtualDisplay(
            context,
            DefaultDisplayConfig.VD_NAME,
            cfg.width,
            cfg.height,
            cfg.dpi,
            surface,
            flags,
        )
        virtualDisplay.set(vd)
        Log.i(TAG, "created virtual display id=${vd.display.displayId} size=${cfg.width}x${cfg.height} dpi=${cfg.dpi}")
        return vd.display.displayId
    }

    private fun buildDisplayFlags(): Int {
        var flags = VIRTUAL_DISPLAY_FLAG_PUBLIC or
            VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
            VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT or
            VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or
                VIRTUAL_DISPLAY_FLAG_TRUSTED or
                VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
                VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
                VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = flags or
                VIRTUAL_DISPLAY_FLAG_OWN_FOCUS or
                VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP or
                VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED
        }
        return flags
    }
}
