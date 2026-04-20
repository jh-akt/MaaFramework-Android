package com.maaframework.android.sample.bbb

import android.app.Application
import android.util.Log
import com.maaframework.android.MaaFrameworkAndroid

class MaaBbbSampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            MaaFrameworkAndroid.initialize(this)
        }.onFailure { error ->
            Log.e(TAG, "Failed to initialize MaaFramework Android", error)
        }
    }

    private companion object {
        const val TAG = "MaaBbbSampleApp"
    }
}
