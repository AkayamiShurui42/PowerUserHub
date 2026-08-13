package com.poweruserhub.app.service

import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Command backend backed by the Shizuku binder.
 *
 * Power User Hub intentionally executes through /system/bin/sh -c instead of trying
 * to spawn commands such as `settings`, `pm`, and `am` as standalone executables.
 * This gives the remote process the Android shell environment and also makes the
 * backend compatible with Shizuku+ transparent shell interception.
 *
 * Shizuku's legacy newProcess API is deprecated upstream in favour of UserService,
 * but it remains the broadest compatibility path for stock Shizuku and Shizuku+
 * while the app migrates privileged operations to typed binder services.
 */
class ShizukuExecutor : CommandExecutor {

    override fun getName(): String = "Shizuku"

    override fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    override fun execute(command: String): CommandResult {
        if (!isAvailable()) {
            return CommandResult(-1, "", "Shizuku service is not available or Power User Hub is not authorized.")
        }

        var process: java.lang.Process? = null
        var outReader: BufferedReader? = null
        var errReader: BufferedReader? = null

        return try {
            // Keep reflection for API/fork compatibility, but always launch a real Android shell.
            // This fixes PATH/environment failures seen when invoking `settings` directly.
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
                CommandResult(-3, stdoutBuilder.toString().trim(), "Execution timed out after 15 seconds")
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
