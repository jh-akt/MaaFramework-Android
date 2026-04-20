package com.maaframework.android.root

import android.content.Context
import com.maaframework.android.BuildConfig
import com.maaframework.android.model.RootBinaryProbe
import com.maaframework.android.model.RootEnvironmentReport
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object RootManager {
    private var initialized = false
    private val commonSuPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/data/adb/magisk/busybox/su",
    )

    fun initialize(context: Context) {
        if (initialized) {
            return
        }
        initialized = true
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        @Suppress("DEPRECATION")
        Shell.setDefaultBuilder(
            Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR),
        )
    }

    fun isGranted(): Boolean {
        return runCatching {
            Shell.isAppGrantedRoot() == true || Shell.getCachedShell()?.isRoot == true
        }.getOrDefault(false)
    }

    fun isAvailable(): Boolean {
        return diagnostics().available
    }

    suspend fun requestPermission(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Shell.getShell().isRoot }.getOrDefault(false)
    }

    fun diagnostics(): RootEnvironmentReport {
        val granted = isGranted()
        val pathProbes = linkedMapOf<String, RootBinaryProbe>()

        commonSuPaths.forEach { path ->
            val file = File(path)
            pathProbes[path] = RootBinaryProbe(
                path = path,
                exists = file.exists(),
                executableByApp = file.canExecute(),
            )
        }

        val execPaths = System.getenv("PATH")?.split(":").orEmpty()
        execPaths
            .map { File(it, "su").absolutePath }
            .forEach { path ->
                if (path !in pathProbes) {
                    val file = File(path)
                    pathProbes[path] = RootBinaryProbe(
                        path = path,
                        exists = file.exists(),
                        executableByApp = file.canExecute(),
                    )
                }
            }

        val probes = pathProbes.values.toList()
        val visibleBinaries = probes.filter { it.exists }
        val available = granted || probes.any { it.executableByApp }
        val summary = when {
            granted -> "Root permission is already granted to the app."
            probes.any { it.executableByApp } -> "Executable su binary found. Tap Connect Root to request permission."
            visibleBinaries.isNotEmpty() -> {
                val paths = visibleBinaries.joinToString { it.path }
                "su exists at $paths, but the app process cannot execute it. adb root alone is not enough; use Magisk, KernelSU, or another app-grantable root solution."
            }
            else -> "No su binary is visible to the app process."
        }

        return RootEnvironmentReport(
            available = available,
            granted = granted,
            summary = summary,
            binaryProbes = probes,
        )
    }
}
