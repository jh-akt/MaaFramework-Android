package com.maaframework.android.session

import com.maaframework.android.ipc.IRootRuntimeService
import com.maaframework.android.model.RootEnvironmentReport

/** Transport/permission boundary. Task and resource code must not depend on su or Shizuku. */
interface PrivilegedRuntimeBackend {
    val id: String
    fun diagnostics(): RootEnvironmentReport
    suspend fun requestPermission(): Boolean
    suspend fun connect(): Result<IRootRuntimeService>
    fun disconnect(service: IRootRuntimeService?)
    fun onUnresponsive(service: IRootRuntimeService, call: String)
}
