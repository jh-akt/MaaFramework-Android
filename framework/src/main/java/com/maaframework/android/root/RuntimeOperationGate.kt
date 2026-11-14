package com.maaframework.android.root

/** Reserves the runtime until its worker has actually exited, including during stop. */
internal class RuntimeOperationGate {
    class Operation {
        val id: String = java.util.UUID.randomUUID().toString()
        fun checkActive() { if (stopRequested) throw java.util.concurrent.CancellationException("Operation stopped") }
        @Volatile var stopRequested = false
            internal set
    }

    @Volatile private var active: Operation? = null
    private var closed = false

    val isStopping: Boolean get() = active?.stopRequested == true

    @Synchronized
    fun acquire(): Operation? {
        if (closed || active != null) return null
        return Operation().also { active = it }
    }

    @Synchronized
    fun requestStop(action: () -> Unit): Boolean {
        val operation = active ?: return false
        operation.stopRequested = true
        action()
        return true
    }

    @Synchronized
    fun finish(operation: Operation, action: () -> Unit = {}) {
        if (active !== operation) return
        active = null
        action()
    }

    @Synchronized
    fun close(action: () -> Unit = {}) {
        closed = true
        active?.stopRequested = true
        action()
    }
}
