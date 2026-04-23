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
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun ping(): String = service.ping()

    fun prepareRuntime(): Boolean = service.prepareRuntime()

    fun startRun(request: RunRequest): Boolean {
        return service.startRun(json.encodeToString(RunRequest.serializer(), request))
    }

    fun stopRun() {
        service.stopRun()
    }

    fun setMonitorSurface(surface: Surface?) {
        service.setMonitorSurface(surface)
    }

    fun startWindowedGame(resourceId: String?): Boolean = service.startWindowedGame(resourceId)

    fun touchDown(x: Int, y: Int): Boolean = touchDown(contactId = 0, x = x, y = y)

    fun touchDown(contactId: Int, x: Int, y: Int): Boolean = service.touchDown(contactId, x, y)

    fun touchMove(x: Int, y: Int): Boolean = touchMove(contactId = 0, x = x, y = y)

    fun touchMove(contactId: Int, x: Int, y: Int): Boolean = service.touchMove(contactId, x, y)

    fun touchUp(x: Int, y: Int): Boolean = touchUp(contactId = 0, x = x, y = y)

    fun touchUp(contactId: Int, x: Int, y: Int): Boolean = service.touchUp(contactId, x, y)

    fun getWindowedDisplayId(): Int = service.getWindowedDisplayId()

    fun stopWindowedPreview() {
        service.stopWindowedPreview()
    }

    fun setDisplayPower(on: Boolean): Boolean = service.setDisplayPower(on)

    fun getState(): RuntimeStateSnapshot {
        return json.decodeFromString(RuntimeStateSnapshot.serializer(), service.getState())
    }

    fun readLogChunk(offsetBytes: Long, maxBytes: Int): RuntimeLogChunk {
        return json.decodeFromString(RuntimeLogChunk.serializer(), service.readLogChunk(offsetBytes, maxBytes))
    }

    fun exportDiagnostics(): String = service.exportDiagnostics()
}
