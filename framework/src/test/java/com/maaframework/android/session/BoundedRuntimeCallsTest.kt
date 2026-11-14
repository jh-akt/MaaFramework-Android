package com.maaframework.android.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test

class BoundedRuntimeCallsTest {
    @Test fun `uninterruptible transaction cannot hold caller indefinitely and is never retried`() {
        val release = CountDownLatch(1)
        var failures = 0
        var attempts = 0
        val calls = BoundedRuntimeCalls { failures++ }
        try {
            assertThrows(TimeoutException::class.java) {
                calls.call("blocked", 100) {
                    attempts++
                    while (release.count != 0L) try { release.await() } catch (_: InterruptedException) { }
                }
            }
            assertEquals(1, failures)
            assertEquals(1, attempts)
            assertThrows(IllegalStateException::class.java) { calls.call("retry") { attempts++ } }
            assertEquals(1, attempts)
        } finally { release.countDown(); calls.close() }
    }

    @Test fun `business failure does not invalidate a healthy transport`() {
        BoundedRuntimeCalls { error("must not invalidate") }.use { calls ->
            assertThrows(IllegalArgumentException::class.java) { calls.call("invalid") { throw IllegalArgumentException() } }
            assertEquals(42, calls.call("healthy") { 42 })
        }
    }
}
