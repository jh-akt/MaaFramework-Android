package com.maaframework.android.session

import android.content.Context
import com.maaframework.android.ipc.IRootRuntimeService
import com.maaframework.android.model.RootEnvironmentReport
import com.maaframework.android.root.RootRuntimeConnector

class MaaFrameworkSession(
    private val context: Context,
    private val backend: PrivilegedRuntimeBackend = RootRuntimeConnector(context),
) {
    private var client: MaaRuntimeClient? = null
    val backendId: String get() = backend.id

    fun isRootAvailable(): Boolean = backend.diagnostics().available

    fun isRootGranted(): Boolean = backend.diagnostics().granted

    fun rootDiagnostics(): RootEnvironmentReport = backend.diagnostics()

    suspend fun requestRootPermission(): Boolean = backend.requestPermission()

    suspend fun connect(): Result<IRootRuntimeService> = backend.connect()

    suspend fun connectClient(): Result<MaaRuntimeClient> = connect().mapCatching { service ->
        synchronized(this) {
            client?.takeIf { it.service.asBinder() === service.asBinder() } ?: run {
                client?.close()
                MaaRuntimeClient(service) { call -> backend.onUnresponsive(service, call) }.also { client = it }
            }
        }
    }

    fun disconnect(service: IRootRuntimeService?) {
        synchronized(this) {
            if (client?.service?.asBinder() === service?.asBinder()) {
                client?.close()
                client = null
            }
        }
        backend.disconnect(service)
    }

    fun disconnect(client: MaaRuntimeClient?) {
        client?.close()
        backend.disconnect(client?.service)
        synchronized(this) { if (this.client === client) this.client = null }
    }
}
