package com.maaframework.android.root

import android.content.Context
import android.os.IBinder
import android.os.Process
import com.maaframework.android.BuildConfig
import com.maaframework.android.ipc.IRootRuntimeService
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

class RootRuntimeConnector(
    private val context: Context,
) {
    suspend fun connect(): Result<IRootRuntimeService> = withContext(Dispatchers.IO) {
        val token = UUID.randomUUID().toString()
        val deferred = RootServiceBootstrapRegistry.register(token)

        runCatching {
            cleanupStaleRootRuntimes()
            val command = buildStartCommand(token)
            val result = Shell.cmd(command).exec()
            if (result.code != 0) {
                error(result.err.joinToString("\n").ifBlank { "exit code=${result.code}" })
            }
            val binder = withTimeout(ROOT_BIND_TIMEOUT_MS) { deferred.await() }
            val service = IRootRuntimeService.Stub.asInterface(binder)
                ?: error("Failed to bind Root runtime service")
            service
        }.also {
            if (it.isFailure) {
                RootServiceBootstrapRegistry.unregister(token)
            }
        }
    }

    fun disconnect(service: IRootRuntimeService?) {
        runCatching {
            service?.destroy()
        }
    }

    private fun buildStartCommand(token: String): String {
        val processName = "${context.packageName}:root_runtime"
        val launcherFile = File(context.applicationInfo.nativeLibraryDir, "librootlauncher.so")
        check(launcherFile.exists()) { "root launcher not found: ${launcherFile.absolutePath}" }
        val launcherPath = launcherFile.absolutePath
        val uid = Process.myUid()

        return buildString {
            append(shellQuote(launcherPath))
            append(" --apk=")
            append(shellQuote(context.applicationInfo.sourceDir))
            append(" --process-name=")
            append(shellQuote(processName))
            append(" --starter-class=")
            append(shellQuote(RootServiceStarter::class.java.name))
            append(" --token=")
            append(shellQuote(token))
            append(" --package=")
            append(shellQuote(context.packageName))
            append(" --class=")
            append(shellQuote(RootRuntimeService::class.java.name))
            append(" --uid=")
            append(uid)
            if (BuildConfig.DEBUG) {
                append(" --debug-name=")
                append(shellQuote(processName))
            }
            append(" >/dev/null 2>&1 &")
        }
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\"'\"'")}'"
    }

    private fun cleanupStaleRootRuntimes() {
        val processName = "${context.packageName}:root_runtime"
        val cleanupCommand = """
            for pid in ${'$'}(ps -A | grep '$processName' | awk '{print ${'$'}2}'); do
                kill ${'$'}pid >/dev/null 2>&1 || true
            done
        """.trimIndent()
        Shell.cmd(cleanupCommand).exec()
    }

    private companion object {
        const val ROOT_BIND_TIMEOUT_MS = 15_000L
    }
}
