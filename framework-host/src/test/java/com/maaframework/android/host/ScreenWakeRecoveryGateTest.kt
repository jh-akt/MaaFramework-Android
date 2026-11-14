package com.maaframework.android.host

import org.junit.Assert.*
import org.junit.Test

class ScreenWakeRecoveryGateTest {
    @Test fun `one wake event restores an active display and duplicates are ignored`() {
        val gate = ScreenWakeRecoveryGate()
        val session = Any()
        assertNull(gate.begin())
        gate.observe(session, true)
        val ticket = gate.begin()!!
        gate.observe(session, true) // a poll must not allow a duplicate recovery
        assertNull(gate.begin())
        assertTrue(gate.finish(ticket, true))
        assertFalse(gate.active)
        assertNull(gate.begin())
    }

    @Test fun `disconnect and new session reject late recovery completion`() {
        val gate = ScreenWakeRecoveryGate()
        gate.observe(Any(), true)
        val old = gate.begin()!!
        gate.observe(null, false)
        assertFalse(gate.finish(old, true))
        gate.observe(Any(), true)
        val current = gate.begin()!!
        assertFalse(gate.finish(old, true))
        assertTrue(gate.active)
        assertTrue(gate.finish(current, true))
    }

    @Test fun `failed restoration remains armed and manual restore invalidates pending wake`() {
        val gate = ScreenWakeRecoveryGate()
        val session = Any()
        gate.observe(session, true)
        assertFalse(gate.finish(gate.begin()!!, false))
        assertTrue(gate.active)
        val retry = gate.begin()!!
        gate.observe(session, false)
        assertFalse(gate.finish(retry, true))
        assertFalse(gate.active)
    }
}
