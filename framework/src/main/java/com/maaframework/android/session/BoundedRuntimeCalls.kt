package com.maaframework.android.session

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** A timeout abandons the wait, not the underlying Binder transaction. Never retries work. */
internal class BoundedRuntimeCalls(private val onTimeout: (String) -> Unit = {}) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(8),
        { task -> Thread(task, "maa-runtime-rpc").apply { isDaemon = true } })

    fun <T> call(name: String, timeoutMillis: Long = 5_000, action: () -> T): T {
        check(!closed.get()) { "Runtime connection is closed" }
        val future = workers.submit(Callable { action() })
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            if (closed.compareAndSet(false, true)) {
                workers.shutdownNow()
                onTimeout(name)
            }
            throw TimeoutException("Runtime call timed out: $name; outcome unknown, reconnect before continuing")
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: InterruptedException) {
            // Do not replay an operation whose result was abandoned by the caller.
            Thread.currentThread().interrupt()
            throw error
        }
    }

    override fun close() {
        closed.set(true)
        workers.shutdownNow()
    }
}
