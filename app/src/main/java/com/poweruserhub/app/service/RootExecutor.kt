package com.poweruserhub.app.service

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

class RootExecutor : CommandExecutor {

    override fun getName(): String = "Root (su)"

    override fun isAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            process.waitFor()
            line != null
        } catch (e: Exception) {
            false
        }
    }

    override fun execute(command: String): CommandResult {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            
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
            
            // Clean command to strip "adb shell" prefix if it exists
            var cleanCommand = command
            if (cleanCommand.startsWith("adb shell ")) {
                cleanCommand = cleanCommand.substring("adb shell ".length)
            }
            
            os.writeBytes(cleanCommand + "\n")
            os.writeBytes("exit\n")
            os.flush()
            
            val exitCode = process.waitFor()
            outThread.join()
            errThread.join()
            
            CommandResult(exitCode, stdoutBuilder.toString().trim(), stderrBuilder.toString().trim())
        } catch (e: Exception) {
            CommandResult(-2, "", e.message ?: "Root execution error")
        }
    }
}
