package com.maaframework.android.root

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RuntimeOperationGateTest {
    @Test
    fun `concurrent starts admit exactly one operation`() {
        val gate = RuntimeOperationGate()
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val requests = (1..8).map {
                executor.submit<RuntimeOperationGate.Operation?> { start.await(); gate.acquire() }
            }
            start.countDown()
            assertEquals(1, requests.map { it.get(5, TimeUnit.SECONDS) }.count { it != null })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `stop holds reservation until worker exits and does not leak into next run`() {
        val gate = RuntimeOperationGate()
        val first = gate.acquire()!!
        assertTrue(gate.requestStop {})
        assertTrue(first.stopRequested)
        assertNull(gate.acquire())
        gate.finish(first)
        val next = gate.acquire()!!
        assertFalse(next.stopRequested)
        var staleCallback = false
        gate.finish(first) { staleCallback = true }
        assertFalse(staleCallback)
        assertNull(gate.acquire())
    }

    @Test
    fun `failed final callback still releases reservation`() {
        val gate = RuntimeOperationGate()
        val operation = gate.acquire()!!
        runCatching { gate.finish(operation) { error("report failure") } }
        assertNotNull(gate.acquire())
    }

    @Test
    fun `shutdown rejects new work even after running worker exits`() {
        val gate = RuntimeOperationGate()
        val operation = gate.acquire()!!
        gate.close()
        assertTrue(operation.stopRequested)
        gate.finish(operation)
        assertNull(gate.acquire())
    }
}
