package com.poweruserhub.app.service

import android.content.Context

/**
 * Shizuku+ specific facade for service keep-alive operations.
 *
 * The generic ShellService remains responsible for one-shot component operations. This
 * controller is intentionally separate because keep-alive requires the daemon UserService
 * binder and should not silently fall back to ordinary ADB/root executors.
 */
class ServiceProtectionController(context: Context) {
    private val appContext = context.applicationContext
    private val executor = ShizukuExecutor(appContext)

    fun isShizukuPlusInstalled(): Boolean {
        return hasPackage("af.shizuku.plus.api") || hasPackage("af.shizuku.plus")
    }

    fun isAvailable(): Boolean = isShizukuPlusInstalled() && executor.isAvailable()

    fun protect(packageName: String, componentName: String, enabled: Boolean): CommandResult {
        if (!isShizukuPlusInstalled()) {
            return CommandResult(
                -20,
                "",
                "Service Keep Alive requires Shizuku+; the Shizuku+ package was not detected."
            )
        }
        return executor.setProtectedService(packageName, componentName, enabled)
    }

    fun isProtected(packageName: String, componentName: String): Boolean {
        if (!isAvailable()) return false
        return executor.isProtectedService(packageName, componentName)
    }

    fun getProtectedServices(): Set<String> {
        if (!isAvailable()) return emptySet()
        return executor.getProtectedServices()
    }

    fun drainEvents(): List<String> {
        if (!isAvailable()) return emptyList()
        return executor.drainProtectionEvents()
    }

    /** Rehydrates daemon state after a Shizuku+ server restart or app relaunch. */
    fun restoreProtection(specs: Set<String>): List<CommandResult> {
        if (!isAvailable()) return emptyList()
        return specs.mapNotNull { spec ->
            val split = spec.indexOf('/')
            if (split <= 0 || split >= spec.lastIndex) return@mapNotNull null
            val pkg = spec.substring(0, split)
            val component = spec.substring(split + 1)
            protect(pkg, component, true)
        }
    }

    private fun hasPackage(packageName: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
