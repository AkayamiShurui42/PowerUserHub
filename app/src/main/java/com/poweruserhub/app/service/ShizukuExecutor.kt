package com.poweruserhub.app.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Privileged command backend for stock Shizuku and compatible Shizuku+ providers.
 *
 * Preferred path: a daemon Shizuku UserService binder running under the server's actual UID.
 * Compatibility path: legacy newProcess launching /system/bin/sh -c while the
 * UserService is connecting or if a compatible provider does not expose UserService.
 */
class ShizukuExecutor(private val context: Context) : CommandExecutor {

    @Volatile
    private var userService: IPrivilegedUserService? = null

    @Volatile
    private var bindRequested = false

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, PrivilegedUserService::class.java.name)
        )
            // The keep-alive watchdog must not die merely because Android reclaims the UI app.
            .daemon(true)
            .processNameSuffix("privileged")
            .debuggable(false)
            .version(2)
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            userService = if (binder.pingBinder()) {
                IPrivilegedUserService.Stub.asInterface(binder)
            } else {
                null
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            userService = null
            bindRequested = false
        }
    }

    override fun getName(): String = if (userService != null) "Shizuku UserService" else "Shizuku"

    override fun isAvailable(): Boolean {
        val available = try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        if (available) ensureUserServiceBinding()
        return available
    }

    override fun execute(command: String): CommandResult {
        if (!isAvailable()) {
            return CommandResult(
                -1,
                "",
                "Shizuku service is not available or Power User Hub is not authorized."
            )
        }

        userService?.let { service ->
            try {
                return commandResult(service.execute(command))
            } catch (_: Throwable) {
                userService = null
                bindRequested = false
                ensureUserServiceBinding()
            }
        }

        // The first command after authorization can arrive before the asynchronous
        // UserService callback. Execute it immediately through Shizuku's shell process
        // rather than making the UI wait; later commands automatically use UserService.
        return executeLegacyShell(command)
    }

    fun setProtectedService(packageName: String, componentName: String, enabled: Boolean): CommandResult {
        if (!isAvailable()) {
            return CommandResult(-1, "", "Shizuku+ is not available or Power User Hub is not authorized.")
        }
        val service = awaitUserService()
            ?: return CommandResult(-7, "", "Privileged UserService did not become ready. Try again after Shizuku+ connects.")
        return try {
            commandResult(service.setProtectedService(packageName, componentName, enabled))
        } catch (t: Throwable) {
            userService = null
            bindRequested = false
            CommandResult(-8, "", "Service protection binder failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun isProtectedService(packageName: String, componentName: String): Boolean {
        val service = awaitUserService(700) ?: return false
        return try {
            service.isProtectedService(packageName, componentName)
        } catch (_: Throwable) {
            false
        }
    }

    fun getProtectedServices(): Set<String> {
        val service = awaitUserService(700) ?: return emptySet()
        return try {
            service.protectedServices?.toSet() ?: emptySet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun drainProtectionEvents(): List<String> {
        val service = awaitUserService(700) ?: return emptyList()
        return try {
            service.drainProtectionEvents()?.toList() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun awaitUserService(timeoutMs: Long = 2_000): IPrivilegedUserService? {
        userService?.let { return it }
        ensureUserServiceBinding()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            userService?.let { return it }
            try {
                Thread.sleep(40)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return userService
    }

    private fun ensureUserServiceBinding() {
        if (userService != null || bindRequested) return
        synchronized(this) {
            if (userService != null || bindRequested) return
            try {
                if (Shizuku.getVersion() >= 10) {
                    bindRequested = true
                    Shizuku.bindUserService(userServiceArgs, userServiceConnection)
                }
            } catch (_: Throwable) {
                bindRequested = false
            }
        }
    }

    private fun commandResult(response: Array<String>?): CommandResult {
        if (response == null || response.size < 3) {
            return CommandResult(-2, "", "Privileged service returned an invalid response.")
        }
        return CommandResult(
            response[0].toIntOrNull() ?: -2,
            response[1],
            response[2]
        )
    }

    private fun executeLegacyShell(command: String): CommandResult {
        var process: java.lang.Process? = null
        var outReader: BufferedReader? = null
        var errReader: BufferedReader? = null

        return try {
            val shellArgs = arrayOf("/system/bin/sh", "-c", command)
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            process = newProcessMethod.invoke(null, shellArgs, null, null) as java.lang.Process

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()
            outReader = BufferedReader(InputStreamReader(process.inputStream))
            errReader = BufferedReader(InputStreamReader(process.errorStream))

            val outThread = Thread {
                try {
                    var line: String?
                    while (outReader.readLine().also { line = it } != null) {
                        stdoutBuilder.append(line).append('\n')
                    }
                } catch (_: Exception) {
                }
            }
            val errThread = Thread {
                try {
                    var line: String?
                    while (errReader.readLine().also { line = it } != null) {
                        stderrBuilder.append(line).append('\n')
                    }
                } catch (_: Exception) {
                }
            }

            outThread.start()
            errThread.start()

            val exited = process.waitFor(15, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                CommandResult(
                    -3,
                    stdoutBuilder.toString().trim(),
                    "Execution timed out after 15 seconds"
                )
            } else {
                outThread.join(2000)
                errThread.join(2000)
                CommandResult(
                    process.exitValue(),
                    stdoutBuilder.toString().trim(),
                    stderrBuilder.toString().trim()
                )
            }
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            CommandResult(
                -2,
                "",
                "Shizuku execution failed: ${cause.javaClass.simpleName}: ${cause.message ?: "unknown error"}"
            )
        } finally {
            try { outReader?.close() } catch (_: Exception) {}
            try { errReader?.close() } catch (_: Exception) {}
            try { process?.inputStream?.close() } catch (_: Exception) {}
            try { process?.outputStream?.close() } catch (_: Exception) {}
            try { process?.errorStream?.close() } catch (_: Exception) {}
            try { process?.destroy() } catch (_: Exception) {}
        }
    }
}
