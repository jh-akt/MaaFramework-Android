package com.maaframework.android.session

import java.util.ArrayDeque

/** One worker preserves Down/Move/Up order. Overflow cancels the whole gesture. */
internal class RuntimeInputQueue(private val cancelTouches: () -> Unit, private val capacity: Int = 64, private val onClosed: () -> Unit = {}) : AutoCloseable {
    private data class Event(val key: Int?, val action: () -> Unit, val control: Boolean = false)
    private val lock = Object()
    private val queue = ArrayDeque<Event>()
    private var closed = false
    private val worker = Thread({ drain() }, "maa-preview-input").apply { isDaemon = true; start() }

    fun offer(moveContact: Int? = null, action: () -> Unit): Boolean = synchronized(lock) {
        if (closed) return false
        if (moveContact != null && queue.peekLast()?.key == moveContact) queue.removeLast()
        if (queue.size >= capacity) {
            queue.removeAll { !it.control }
            queue.add(Event(null, cancelTouches))
            lock.notifyAll()
            return false
        }
        queue.add(Event(moveContact, action))
        lock.notifyAll()
        true
    }

    /** Surface updates survive gesture overflow; only the most recent pending surface matters. */
    fun offerControl(action: () -> Unit): Boolean = synchronized(lock) {
        if (closed) return false
        queue.removeAll { it.control }
        queue.add(Event(null, action, control = true))
        lock.notifyAll()
        true
    }

    private fun drain() {
        try { drainEvents() } finally { onClosed() }
    }

    private fun drainEvents() {
        while (true) {
            val event = synchronized(lock) {
                while (queue.isEmpty() && !closed) lock.wait()
                if (closed && queue.isEmpty()) return
                queue.removeFirst()
            }
            try { event.action() } catch (_: Exception) {
                synchronized(lock) { queue.removeAll { !it.control } }
                runCatching(cancelTouches)
            }
        }
    }

    override fun close(): Unit = synchronized(lock) {
        if (closed) return
        queue.clear()
        queue.add(Event(null, cancelTouches))
        closed = true
        lock.notifyAll()
    }
}
