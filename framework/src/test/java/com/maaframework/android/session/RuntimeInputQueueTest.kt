package com.maaframework.android.session

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RuntimeInputQueueTest {
    @Test fun `moves coalesce without reordering down and up`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        RuntimeInputQueue({}).use { queue ->
            queue.offer { entered.countDown(); release.await(); events.add("down") }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            queue.offer(0) { events.add("old move") }
            queue.offer(0) { events.add("new move") }
            queue.offer { events.add("up"); done.countDown() }
            release.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("down", "new move", "up"), events)
        }
    }

    @Test fun `overflow cancels pending gesture instead of losing its release`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        RuntimeInputQueue({ cancelled.countDown() }, capacity = 1).use { queue ->
            queue.offer { entered.countDown(); release.await() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(queue.offer { error("discarded move") })
            assertFalse(queue.offer { error("must not execute") })
            release.countDown()
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        }
    }
    @Test fun `closing cancels gesture before disposing transport`() {
        val closed = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val queue = RuntimeInputQueue({ events.add("cancel") }, onClosed = { events.add("dispose"); closed.countDown() })
        queue.close()
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("cancel", "dispose"), events)
        assertFalse(queue.offer { fail("Closed queue accepted input") })
    }

    @Test fun `surface updates survive gesture overflow`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        RuntimeInputQueue({ events.add("cancel"); cancelled.countDown() }, capacity = 2).use { queue ->
            queue.offer { entered.countDown(); release.await() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            queue.offerControl { events.add("surface") }
            queue.offer { fail("Discarded touch executed") }
            assertFalse(queue.offer { fail("Overflow touch executed") })
            release.countDown()
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("surface", "cancel"), events)
        }
    }

}
