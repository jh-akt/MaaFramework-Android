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
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID

class RootRuntimeConnector(
    private val context: Context,
) : com.maaframework.android.session.PrivilegedRuntimeBackend {
    override val id = "root"
    override fun diagnostics() = RootManager.diagnostics()
    override suspend fun requestPermission() = RootManager.requestPermission()

    private data class Binding(val service: IRootRuntimeService, val recipient: IBinder.DeathRecipient, val pid: Int)
    private val bindingLock = Any()
    private val connectLock = kotlinx.coroutines.sync.Mutex()
    private var binding: Binding? = null
    private data class Recovery(val service: IRootRuntimeService, val done: kotlinx.coroutines.CompletableDeferred<Result<Unit>>)
    private var recovery: Recovery? = null

    override suspend fun connect(): Result<IRootRuntimeService> = withContext(Dispatchers.IO) {
        connectLock.lock()
        try {
            val pendingRecovery = synchronized(bindingLock) { recovery }
            if (pendingRecovery != null) {
                val recovered = try { withTimeout(10_000) { pendingRecovery.done.await() } }
                catch (error: kotlinx.coroutines.TimeoutCancellationException) { return@withContext Result.failure(error) }
                if (recovered.isFailure && pendingRecovery.service.asBinder().isBinderAlive) {
                    return@withContext Result.failure(IllegalStateException("Previous runtime has not exited; reconnect would overlap sessions", recovered.exceptionOrNull()))
                }
                synchronized(bindingLock) { if (recovery === pendingRecovery) recovery = null }
            }
            synchronized(bindingLock) { binding?.service?.takeIf { it.asBinder().isBinderAlive } }?.let {
                return@withContext Result.success(it)
            }
            val token = UUID.randomUUID().toString()
            val deferred = RootServiceBootstrapRegistry.register(token)
            var candidate: IRootRuntimeService? = null
            try {
                val starter = com.maaframework.android.session.BoundedRuntimeCalls()
                try {
                    starter.call("root launcher", 20_000) {
                        val result = Shell.cmd(buildStartCommand(token)).exec()
                        check(result.code == 0) { result.err.joinToString("\n").ifBlank { "exit code=${result.code}" } }
                    }
                    val binder = kotlinx.coroutines.withTimeoutOrNull(ROOT_BIND_TIMEOUT_MS) { deferred.await() }
                        ?: error("Runtime bootstrap timed out")
                    val service = IRootRuntimeService.Stub.asInterface(binder) ?: error("Missing runtime Binder")
                    candidate = service
                    val pid = starter.call("runtime pid") { service.processId }
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val recipient = IBinder.DeathRecipient { forget(service) }
                    synchronized(bindingLock) {
                        binder.linkToDeath(recipient, 0)
                        binding = Binding(service, recipient, pid)
                        if (!binder.isBinderAlive) { forget(service); error("Runtime died during connection") }
                    }
                    Result.success(service)
                } finally { starter.close() }
            } catch (error: kotlinx.coroutines.CancellationException) {
                candidate?.let { forget(it); runCatching { it.destroy() } }
                throw error
            } catch (error: Exception) {
                candidate?.let { forget(it); runCatching { it.destroy() } }
                Result.failure(error)
            } finally { RootServiceBootstrapRegistry.unregister(token) }
        } finally { connectLock.unlock() }
    }

    private fun forget(service: IRootRuntimeService): Binding? = synchronized(bindingLock) {
        val current = binding ?: return null
        if (current.service.asBinder() !== service.asBinder()) return null
        binding = null
        runCatching { service.asBinder().unlinkToDeath(current.recipient, 0) }
        current
    }

    override fun disconnect(service: IRootRuntimeService?) {
        if (service == null || forget(service) == null) return
        runCatching { service.destroy() }
    }

    override fun onUnresponsive(service: IRootRuntimeService, call: String) {
        val (failed, recovering) = synchronized(bindingLock) {
            val failed = forget(service) ?: return // Late failure cannot terminate a replacement.
            val recovering = Recovery(service, kotlinx.coroutines.CompletableDeferred())
            recovery = recovering
            failed to recovering
        }
        Thread({
            val diagnostic = com.maaframework.android.session.BoundedRuntimeCalls()
            val dump = try {
                diagnostic.call("thread dump", 1_000) { service.dumpThreads() }
            } catch (error: Exception) { "Thread dump unavailable: ${error.javaClass.simpleName}" }
            finally { diagnostic.close() }
            runCatching {
                val directory = File(context.filesDir, "runtime-diagnostics").apply { mkdirs() }
                File(directory, "timeout-${System.currentTimeMillis()}.txt").writeText("call=$call pid=${failed.pid}\n$dump")
            }
            val recoveryCalls = com.maaframework.android.session.BoundedRuntimeCalls()
            try { recoveryCalls.call("restore display", 1_000) { service.setDisplayPower(true) } }
            catch (_: Exception) { /* persisted flag is recovered by the next runtime */ }
            finally { recoveryCalls.close() }
            runCatching { service.destroy() }
            // Target the failed PID only, with a command-line identity check; never replay the operation.
            val name = "${context.packageName}:root_runtime"
            val pid = failed.pid
            val result = runCatching {
                val killer = com.maaframework.android.session.BoundedRuntimeCalls()
                try {
                    killer.call("stop unresponsive runtime", 5_000) {
                        if (pid > 1) {
                            val command = "case \"\$(tr '\\000' '\\n' < /proc/$pid/cmdline 2>/dev/null | head -n 1)\" in ${shellQuote(name)}) kill -9 $pid;; esac"
                            Shell.cmd(command).exec()
                        }
                        // Death acknowledgement, rather than command submission, releases reconnect.
                        repeat(50) {
                            if (!service.asBinder().isBinderAlive) return@call
                            Thread.sleep(50)
                        }
                        check(!service.asBinder().isBinderAlive) { "Failed runtime is still alive" }
                    }
                } finally { killer.close() }
            }
            recovering.done.complete(result)
        }, "maa-runtime-recovery").apply { isDaemon = true }.start()
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
            val log = File(context.getExternalFilesDir(null) ?: context.filesDir, "runtime-launcher.log")
            append(" >")
            append(shellQuote(log.absolutePath))
            append(" 2>&1 &")
        }
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\"'\"'")}'"
    }

    private companion object {
        const val ROOT_BIND_TIMEOUT_MS = 15_000L
    }
}
