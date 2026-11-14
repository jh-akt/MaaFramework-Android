package com.maaframework.android.runtime

import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class RepositoryFileLeaseTest {
    @Test fun `reader keeps one revision until the complete snapshot copy finishes`() {
        val root = Files.createTempDirectory("maa-lease").toFile()
        val lock = root.resolve("repository.lock")
        val data = root.resolve("revision").apply { writeText("old") }
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val writerWaiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val reader = executor.submit<String> { withRepositoryFileLease(lock, {}) {
                val before = data.readText(); entered.countDown(); release.await()
                before + ":" + data.readText()
            } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val writer = executor.submit { withRepositoryFileLease(lock, { writerWaiting.countDown() }) { data.writeText("new") } }
            assertTrue(writerWaiting.await(2, TimeUnit.SECONDS))
            assertFalse(writer.isDone)
            release.countDown()
            assertEquals("old:old", reader.get(2, TimeUnit.SECONDS))
            writer.get(2, TimeUnit.SECONDS)
            assertEquals("new", data.readText())
        } finally { release.countDown(); executor.shutdownNow(); root.deleteRecursively() }
    }

    @Test fun `cancelled lease waiter does not mutate repository or retain lock`() {
        val root = Files.createTempDirectory("maa-lease").toFile()
        val lock = root.resolve("repository.lock")
        val executor = Executors.newSingleThreadExecutor()
        val waiting = CountDownLatch(1)
        val cancel = AtomicBoolean()
        try {
            withRepositoryFileLease(lock, {}) {
                val waiter = executor.submit { withRepositoryFileLease(lock, {
                    waiting.countDown()
                    if (cancel.get()) throw CancellationException()
                }) { fail("Cancelled waiter acquired a snapshot") } }
                assertTrue(waiting.await(2, TimeUnit.SECONDS)); cancel.set(true)
                try { waiter.get(2, TimeUnit.SECONDS); fail("Expected cancellation") }
                catch (error: ExecutionException) { assertTrue(error.cause is CancellationException) }
            }
            withRepositoryFileLease(lock, {}) { /* still available */ }
        } finally { executor.shutdownNow(); root.deleteRecursively() }
    }
}
