package com.maaframework.android.preview

import android.os.Handler
import android.os.HandlerThread
import android.os.Process

object FrameCaptureHelper {
    fun createCaptureHandler(name: String): Handler {
        val thread = object : HandlerThread(name, Process.THREAD_PRIORITY_URGENT_DISPLAY) {
            override fun onLooperPrepared() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            }
        }
        thread.start()
        return Handler(thread.looper)
    }
}
