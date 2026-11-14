package com.maaframework.android.runtime

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

/** File locks coordinate app and privileged processes; cancellation also works while waiting. */
internal fun <T> withRepositoryFileLease(file: File, checkCancelled: () -> Unit, action: () -> T): T {
    RandomAccessFile(file, "rw").use { handle ->
        while (true) {
            checkCancelled()
            val lease = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lease != null) lease.use { return action() }
            Thread.sleep(25)
        }
    }
}
