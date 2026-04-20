package com.maaframework.android

import android.content.Context
import com.maaframework.android.bridge.DriverClass
import com.maaframework.android.root.RootManager

object MaaFrameworkAndroid {
    fun initialize(context: Context) {
        DriverClass.installContext(context)
        RootManager.initialize(context)
    }
}
