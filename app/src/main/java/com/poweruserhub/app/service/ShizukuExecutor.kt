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
        return try {
            val cmdArgs = parseCommand(command).toTypedArray()
            val process = Shizuku.newProcess(cmdArgs, null, null)
            
            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()
            
            val outReader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val outThread = Thread {
                var line: String?
                while (outReader.readLine().also { line = it } != null) {
                    stdoutBuilder.append(line).append("\n")
                }
            }
            
            val errThread = Thread {
                var line: String?
                while (errReader.readLine().also { line = it } != null) {
                    stderrBuilder.append(line).append("\n")
                }
            }
            
            outThread.start()
            errThread.start()
            
            val exitCode = process.waitFor()
            outThread.join()
            errThread.join()
            
            CommandResult(exitCode, stdoutBuilder.toString().trim(), stderrBuilder.toString().trim())
        } catch (e: Exception) {
            CommandResult(-2, "", e.message ?: "Unknown Shizuku execution error")
        }
    }

    private fun parseCommand(command: String): List<String> {
        val list = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (c == '\"') {
                inQuotes = !inQuotes
            } else if (c == ' ' && !inQuotes) {
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
