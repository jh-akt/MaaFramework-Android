package com.maaframework.android.preview

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import com.maaframework.android.preview.hidden.ServiceManager

object ActivityUtils {
    private const val TAG = "ActivityUtils"

    fun startApp(
        context: Context,
        packageName: String,
        displayId: Int,
        forceStop: Boolean = true,
        excludeFromRecents: Boolean = true,
    ): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: return false

        var flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (excludeFromRecents) {
            flags = flags or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
        if (displayId != 0) {
            flags = flags or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        }
        intent.addFlags(flags)

        if (forceStop) {
            ServiceManager.getActivityManager().forceStopPackage(packageName)
        }

        return try {
            val launchOptions = ActivityOptions.makeBasic()
            if (displayId != 0) {
                launchOptions.launchDisplayId = displayId
            }
            val ret = ServiceManager.getActivityManager().startActivity(intent, launchOptions.toBundle())
            Log.i(TAG, "startApp package=$packageName displayId=$displayId ret=$ret")
            ret >= 0
        } catch (e: Exception) {
            Log.e(TAG, "startApp failed package=$packageName displayId=$displayId", e)
            false
        }
    }
}
