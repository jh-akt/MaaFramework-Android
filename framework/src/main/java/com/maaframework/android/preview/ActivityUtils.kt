package com.maaframework.android.preview

import android.app.ActivityOptions
import android.content.ComponentName
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
        val launchTarget = LaunchTarget.parse(packageName)
        synchronized(startedVirtualPackages) {
            if (!forceStop && displayId != 0 && startedVirtualPackages[displayId] == launchTarget.packageName) {
                Log.i(TAG, "startApp skipped duplicate package=${launchTarget.raw} displayId=$displayId")
                return true
            }
        }

        val pm = context.packageManager
        val intent = launchTarget.toIntent()
            ?: pm.getLaunchIntentForPackage(launchTarget.packageName)
            ?: pm.getLeanbackLaunchIntentForPackage(launchTarget.packageName)
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
            ServiceManager.getActivityManager().forceStopPackage(launchTarget.packageName)
        }

        return try {
            val launchOptions = ActivityOptions.makeBasic()
            if (displayId != 0) {
                launchOptions.launchDisplayId = displayId
            }
            val ret = ServiceManager.getActivityManager().startActivity(intent, launchOptions.toBundle())
            Log.i(TAG, "startApp package=${launchTarget.raw} displayId=$displayId ret=$ret")
            (ret >= 0).also { started ->
                if (started && displayId != 0) {
                    synchronized(startedVirtualPackages) {
                        startedVirtualPackages[displayId] = launchTarget.packageName
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startApp failed package=${launchTarget.raw} displayId=$displayId", e)
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

    private data class LaunchTarget(
        val raw: String,
        val packageName: String,
        val componentName: ComponentName?,
    ) {
        fun toIntent(): Intent? {
            val component = componentName ?: return null
            return Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setComponent(component)
            }
        }

        companion object {
            fun parse(raw: String): LaunchTarget {
                val trimmed = raw.trim()
                val slashIndex = trimmed.indexOf('/')
                if (slashIndex <= 0 || slashIndex == trimmed.lastIndex) {
                    return LaunchTarget(trimmed, trimmed, null)
                }

                val pkg = trimmed.substring(0, slashIndex)
                val activity = trimmed.substring(slashIndex + 1)
                val className = if (activity.startsWith(".")) {
                    pkg + activity
                } else {
                    activity
                }
                return LaunchTarget(trimmed, pkg, ComponentName(pkg, className))
            }
        }
    }
}
