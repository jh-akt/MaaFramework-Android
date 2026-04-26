package com.maaframework.android.preview

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import com.maaframework.android.preview.hidden.ServiceManager

object ActivityUtils {
    private const val TAG = "ActivityUtils"
    private val startedVirtualPackages = mutableMapOf<Int, String>()

    fun startApp(
        context: Context,
        packageName: String,
        displayId: Int,
        forceStop: Boolean = true,
        excludeFromRecents: Boolean = true,
    ): Boolean {
        synchronized(startedVirtualPackages) {
            if (!forceStop && displayId != 0 && startedVirtualPackages[displayId] == packageName) {
                Log.i(TAG, "startApp skipped duplicate package=$packageName displayId=$displayId")
                return true
            }
        }

        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: return false
        val componentName = intent.component?.flattenToShortString()

        var flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (excludeFromRecents) {
            flags = flags or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
        if (displayId != 0) {
            flags = flags or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        }
        intent.addFlags(flags)

        if (displayId == 0 && componentName != null) {
            val startedViaShell = startFullscreenViaShell(componentName, forceStop)
            if (startedViaShell) {
                return true
            }
        } else if (forceStop) {
            ServiceManager.getActivityManager().forceStopPackage(packageName)
        }

        return try {
            val launchOptions = ActivityOptions.makeBasic()
            if (displayId != 0) {
                launchOptions.launchDisplayId = displayId
            }
            val ret = ServiceManager.getActivityManager().startActivity(intent, launchOptions.toBundle())
            Log.i(TAG, "startApp package=$packageName displayId=$displayId ret=$ret")
            (ret >= 0).also { started ->
                if (started && displayId != 0) {
                    synchronized(startedVirtualPackages) {
                        startedVirtualPackages[displayId] = packageName
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startApp failed package=$packageName displayId=$displayId", e)
            false
        }
    }

    fun forgetVirtualDisplay(displayId: Int) {
        synchronized(startedVirtualPackages) {
            startedVirtualPackages.remove(displayId)
        }
    }

    private fun startFullscreenViaShell(componentName: String, forceStop: Boolean): Boolean {
        return try {
            val command = mutableListOf("/system/bin/am", "start", "-W")
            if (forceStop) {
                command += "-S"
            }
            command += listOf("--display", "0", "--windowingMode", "1", "-n", componentName)
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val code = process.waitFor()
            Log.i(TAG, "startApp shell component=$componentName code=$code output=$output")
            code == 0 && !output.contains("Exception", ignoreCase = true)
        } catch (e: Exception) {
            Log.w(TAG, "startApp shell fallback failed for component=$componentName", e)
            false
        }
    }
}
