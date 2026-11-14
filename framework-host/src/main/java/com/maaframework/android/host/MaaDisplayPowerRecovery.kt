package com.maaframework.android.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.maaframework.android.model.RuntimeStateSnapshot
import com.maaframework.android.session.MaaRuntimeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Host policy; runtime stop/failure/destroy always restore physical display power. */
data class MaaDisplayPowerPolicy(val restoreOnScreenOn: Boolean = true)

/** Call observe/close on the main thread. Never performs a synchronous Binder call on that thread. */
class MaaDisplayPowerRecovery(
    context: Context,
    private val scope: CoroutineScope,
    private val policy: MaaDisplayPowerPolicy = MaaDisplayPowerPolicy(),
    private val onRestored: () -> Unit = {},
) : AutoCloseable {
    private val context = context.applicationContext
    private val gate = ScreenWakeRecoveryGate()
    private var client: MaaRuntimeClient? = null
    private var registered = false
    private var closed = false
    private var restoreJob: Job? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_ON) return
            val ticket = gate.begin() ?: return
            val target = client ?: return
            restoreJob = scope.launch {
                val restored = withContext(Dispatchers.IO) {
                    runCatching { target.setDisplayPower(true) }.getOrDefault(false)
                }
                if (gate.finish(ticket, restored)) {
                    syncReceiver()
                    onRestored()
                }
            }
        }
    }

    fun observe(client: MaaRuntimeClient?, state: RuntimeStateSnapshot) {
        if (closed) return
        if (this.client !== client) {
            restoreJob?.cancel()
            this.client = client
        }
        gate.observe(client, policy.restoreOnScreenOn && state.displayPowerOffActive)
        syncReceiver()
    }

    private fun syncReceiver() {
        if (gate.active && !registered) {
            // SCREEN_ON is a system-only broadcast; no exported custom action is exposed.
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            registered = true
        } else if (!gate.active && registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
    }

    override fun close() {
        closed = true
        restoreJob?.cancel()
        client = null
        gate.observe(null, false)
        syncReceiver()
    }
}

/** Ticket identity prevents late wake callbacks from changing a replacement session. */
internal class ScreenWakeRecoveryGate {
    class Ticket internal constructor()
    private var session: Any? = null
    private var pending: Ticket? = null
    var active = false
        private set

    fun observe(session: Any?, active: Boolean) {
        val nextActive = session != null && active
        if (this.session !== session || this.active != nextActive) pending = null
        this.session = session
        this.active = nextActive
    }

    fun begin(): Ticket? {
        if (!active || pending != null) return null
        return Ticket().also { pending = it }
    }

    fun finish(ticket: Ticket, restored: Boolean): Boolean {
        if (pending !== ticket) return false
        pending = null
        if (restored) active = false
        return restored
    }
}
