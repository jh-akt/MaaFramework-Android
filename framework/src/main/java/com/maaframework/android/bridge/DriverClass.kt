package com.maaframework.android.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import com.maaframework.android.bridge.NativeBridgeLib
import com.maaframework.android.preview.ActivityUtils
import java.io.BufferedInputStream

object DriverClass {
    private const val FRAME_WAIT_TIMEOUT_MS = 5_000
    private const val FRAME_WAIT_INTERVAL_MS = 50
    private val pressedKeys = linkedSetOf<Int>()

    @Volatile
    private var appContext: Context? = null

    @JvmStatic
    fun installContext(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun captureFrame(): Bitmap? {
        return runCatching {
            val process = ProcessBuilder("/system/bin/screencap", "-p")
                .redirectErrorStream(true)
                .start()
            val bytes = BufferedInputStream(process.inputStream).use { it.readBytes() }
            process.waitFor()
            if (bytes.isEmpty()) {
                null
            } else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }

    @JvmStatic
    fun startApp(packageName: String, displayId: Int, forceStop: Boolean): Boolean {
        val context = appContext ?: error("DriverClass context is not installed")
        val ret = ActivityUtils.startApp(
            context = context,
            packageName = packageName,
            displayId = displayId,
            forceStop = forceStop,
            excludeFromRecents = displayId != 0,
        )
        if (ret && displayId != 0) {
            awaitFirstFrame()
        }
        return ret
    }

    @JvmStatic
    fun stopApp(packageName: String, displayId: Int): Boolean {
        return runShell("/system/bin/am", "force-stop", packageName)
    }

    @JvmStatic
    fun touchDown(x: Int, y: Int, displayId: Int): Boolean {
        return InputControlUtils.down(x, y, displayId)
    }

    @JvmStatic
    fun touchMove(x: Int, y: Int, displayId: Int): Boolean {
        return InputControlUtils.move(x, y, displayId)
    }

    @JvmStatic
    fun touchUp(x: Int, y: Int, displayId: Int): Boolean {
        return InputControlUtils.up(x, y, displayId)
    }

    @JvmStatic
    fun keyDown(keyCode: Int, displayId: Int): Boolean {
        val translated = translateKeyCode(keyCode)
        pressedKeys += translated
        return InputControlUtils.keyDown(translated, displayId)
    }

    @JvmStatic
    fun keyUp(keyCode: Int, displayId: Int): Boolean {
        val translated = translateKeyCode(keyCode)
        val active = pressedKeys.remove(translated)
        if (!active) {
            return true
        }
        return InputControlUtils.keyUp(translated, displayId)
    }

    @JvmStatic
    fun inputText(text: String): Boolean {
        return runShell("/system/bin/input", "keyboard", "text", text.replace(" ", "%s"))
    }

    private fun runInput(displayId: Int, vararg args: String): Boolean {
        return if (displayId >= 0) {
            runShell("/system/bin/input", "-d", displayId.toString(), *args)
        } else {
            runShell("/system/bin/input", *args)
        }
    }

    private fun runShell(command: String, vararg args: String): Boolean {
        return runCatching {
            val process = ProcessBuilder(listOf(command, *args))
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    private fun awaitFirstFrame() {
        val baseline = NativeBridgeLib.getFrameCount()
        var elapsed = 0
        while (NativeBridgeLib.getFrameCount() <= baseline && elapsed < FRAME_WAIT_TIMEOUT_MS) {
            try {
                Thread.sleep(FRAME_WAIT_INTERVAL_MS.toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            elapsed += FRAME_WAIT_INTERVAL_MS
        }
    }

    private fun translateKeyCode(keyCode: Int): Int {
        return when (keyCode) {
            4 -> KeyEvent.KEYCODE_BACK
            9 -> KeyEvent.KEYCODE_TAB
            13 -> KeyEvent.KEYCODE_ENTER
            16, 160 -> KeyEvent.KEYCODE_SHIFT_LEFT
            161 -> KeyEvent.KEYCODE_SHIFT_RIGHT
            17, 162 -> KeyEvent.KEYCODE_CTRL_LEFT
            163 -> KeyEvent.KEYCODE_CTRL_RIGHT
            18, 164 -> KeyEvent.KEYCODE_ALT_LEFT
            165 -> KeyEvent.KEYCODE_ALT_RIGHT
            27 -> KeyEvent.KEYCODE_ESCAPE
            32 -> KeyEvent.KEYCODE_SPACE
            in 48..57 -> KeyEvent.KEYCODE_0 + (keyCode - 48)
            in 65..90 -> KeyEvent.KEYCODE_A + (keyCode - 65)
            in 112..123 -> KeyEvent.KEYCODE_F1 + (keyCode - 112)
            else -> keyCode
        }
    }
}
