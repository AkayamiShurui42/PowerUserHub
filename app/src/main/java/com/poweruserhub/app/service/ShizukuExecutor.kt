package com.poweruserhub.app.service

import android.os.Build
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class ShizukuExecutor : CommandExecutor {

    override fun getName(): String = "Shizuku"

    override fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && 
                    Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    override fun execute(command: String): CommandResult {
        if (!isAvailable()) {
            return CommandResult(-1, "", "Shizuku service not available or not authorized.")
        }
        var process: java.lang.Process? = null
        var outReader: BufferedReader? = null
        var errReader: BufferedReader? = null
        return try {
            val cmdArgs = parseCommand(command).toTypedArray()
            process = Shizuku.newProcess(cmdArgs, null, null)
            
            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()
            
            outReader = BufferedReader(InputStreamReader(process.inputStream))
            errReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val outThread = Thread {
                try {
                    var line: String?
                    while (outReader.readLine().also { line = it } != null) {
                        stdoutBuilder.append(line).append("\n")
                    }
                } catch (e: Exception) {}
            }
            
            val errThread = Thread {
                try {
                    var line: String?
                    while (errReader.readLine().also { line = it } != null) {
                        stderrBuilder.append(line).append("\n")
                    }
                } catch (e: Exception) {}
            }
            
            outThread.start()
            errThread.start()
            
            val exited = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                process.destroy()
                CommandResult(-3, "", "Execution timed out")
            } else {
                outThread.join(2000)
                errThread.join(2000)
                val exitCode = process.exitValue()
                CommandResult(exitCode, stdoutBuilder.toString().trim(), stderrBuilder.toString().trim())
            }
        } catch (e: Exception) {
            CommandResult(-2, "", e.message ?: "Unknown Shizuku execution error")
        } finally {
            try { outReader?.close() } catch (e: Exception) {}
            try { errReader?.close() } catch (e: Exception) {}
            try { process?.inputStream?.close() } catch (e: Exception) {}
            try { process?.outputStream?.close() } catch (e: Exception) {}
            try { process?.errorStream?.close() } catch (e: Exception) {}
            try { process?.destroy() } catch (e: Exception) {}
        }
    }

    private fun parseCommand(command: String): List<String> {
        val list = mutableListOf<String>()
        val current = StringBuilder()
        var inDoubleQuotes = false
        var inSingleQuotes = false
        var escaped = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (escaped) {
                current.append(c)
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '\"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes
            } else if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes
            } else if ((c == ' ' || c == '\t' || c == '\n') && !inDoubleQuotes && !inSingleQuotes) {
                if (current.isNotEmpty()) {
                    list.add(current.toString())
                    current.setLength(0)
                }
            } else {
                current.append(c)
            }
            i++
        }
        if (current.isNotEmpty()) {
            list.add(current.toString())
        }
        // If it starts with "adb shell ", strip it as Shizuku runs commands in shell context directly
        if (list.isNotEmpty() && list[0] == "adb") {
            if (list.size > 2 && list[1] == "shell") {
                return list.subList(2, list.size)
            }
        }
        return list
    }
}
