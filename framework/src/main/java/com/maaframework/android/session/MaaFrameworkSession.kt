package com.maaframework.android.session

import android.content.Context
import com.maaframework.android.ipc.IRootRuntimeService
import com.maaframework.android.model.RootEnvironmentReport
import com.maaframework.android.root.RootManager
import com.maaframework.android.root.RootRuntimeConnector

class MaaFrameworkSession(
    private val context: Context,
) {
    private val connector = RootRuntimeConnector(context)

    fun isRootAvailable(): Boolean = RootManager.isAvailable()

    fun isRootGranted(): Boolean = RootManager.isGranted()

    fun rootDiagnostics(): RootEnvironmentReport = RootManager.diagnostics()

    suspend fun requestRootPermission(): Boolean = RootManager.requestPermission()

    suspend fun connect(): Result<IRootRuntimeService> = connector.connect()

    suspend fun connectClient(): Result<MaaRuntimeClient> = connect().map(::MaaRuntimeClient)

    fun disconnect(service: IRootRuntimeService?) {
        connector.disconnect(service)
    }

    fun disconnect(client: MaaRuntimeClient?) {
        connector.disconnect(client?.service)
    }
}
