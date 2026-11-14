package com.maaframework.android.session

import android.view.Surface
import com.maaframework.android.ipc.IRootRuntimeService
import com.maaframework.android.model.RunRequest
import com.maaframework.android.model.RuntimeLogChunk
import com.maaframework.android.model.RuntimeStateSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MaaRuntimeClient internal constructor(
    internal val service: IRootRuntimeService,
    private val onUnresponsive: (String) -> Unit = {},
) {
    private val calls = BoundedRuntimeCalls(onUnresponsive)
    private val input = RuntimeInputQueue({ rpc("touchCancel") { service.touchCancel() } }, onClosed = { calls.close() })

    private fun <T> rpc(name: String, timeout: Long = 5_000, action: () -> T): T = calls.call(name, timeout, action)

    private val deathRecipient = android.os.IBinder.DeathRecipient { close() }
    init {
        try { service.asBinder().linkToDeath(deathRecipient, 0) }
        catch (error: Exception) { input.close(); throw error }
    }

    internal fun close() {
        runCatching { service.asBinder().unlinkToDeath(deathRecipient, 0) }
        input.close()
    }

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun ping(): String = rpc("ping") { service.ping() }

    fun prepareRuntime(): Boolean = rpc("prepareRuntime") { service.prepareRuntime() }

    fun startRun(request: RunRequest): Boolean {
        return rpc("startRun") { service.startRun(json.encodeToString(RunRequest.serializer(), request)) }
    }

    fun stopRun() {
        rpc("stopRun") { service.stopRun() }
    }

    fun setMonitorSurface(surface: Surface?) {
        input.offerControl { rpc("setMonitorSurface") { service.setMonitorSurface(surface) } }
    }

    fun startWindowedGame(resourceId: String?): Boolean = rpc("startWindowedGame") { service.startWindowedGame(resourceId) }

    fun touchDown(x: Int, y: Int): Boolean = touchDown(contactId = 0, x = x, y = y)

    /** Returns queue admission, not remote injection success. */
    fun touchDown(contactId: Int, x: Int, y: Int): Boolean = input.offer() { rpc("touchDown") { service.touchDown(contactId, x, y) } }

    fun touchMove(x: Int, y: Int): Boolean = touchMove(contactId = 0, x = x, y = y)

    /** Returns queue admission, not remote injection success. */
    fun touchMove(contactId: Int, x: Int, y: Int): Boolean = input.offer(moveContact = contactId) { rpc("touchMove") { service.touchMove(contactId, x, y) } }

    fun touchUp(x: Int, y: Int): Boolean = touchUp(contactId = 0, x = x, y = y)

    /** Returns queue admission, not remote injection success. */
    fun touchUp(contactId: Int, x: Int, y: Int): Boolean = input.offer() { rpc("touchUp") { service.touchUp(contactId, x, y) } }

    fun getWindowedDisplayId(): Int = rpc("getWindowedDisplayId") { service.getWindowedDisplayId() }

    fun stopWindowedPreview() {
        input.offer { rpc("touchCancel") { service.touchCancel() } }
        rpc("stopWindowedPreview") { service.stopWindowedPreview() }
    }

    fun setDisplayPower(on: Boolean): Boolean = rpc("setDisplayPower") { service.setDisplayPower(on) }

    fun getState(): RuntimeStateSnapshot {
        return json.decodeFromString(RuntimeStateSnapshot.serializer(), rpc("getState") { service.getState() })
    }

    fun readLogChunk(offsetBytes: Long, maxBytes: Int): RuntimeLogChunk {
        return json.decodeFromString(RuntimeLogChunk.serializer(), rpc("readLogChunk") { service.readLogChunk(offsetBytes, maxBytes) })
    }

    fun exportDiagnostics(): String = rpc("exportDiagnostics", 30_000) { service.exportDiagnostics() }
}
