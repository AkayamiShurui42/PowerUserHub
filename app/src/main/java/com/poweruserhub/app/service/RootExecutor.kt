package com.poweruserhub.app.service

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

class RootExecutor : CommandExecutor {

    override fun getName(): String = "Root (su)"

    override fun isAvailable(): Boolean {
        var process: java.lang.Process? = null
        var reader: BufferedReader? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            process.waitFor()
            line != null
        } catch (e: Exception) {
            false
        } finally {
            try { reader?.close() } catch (e: Exception) {}
            try { process?.inputStream?.close() } catch (e: Exception) {}
            try { process?.outputStream?.close() } catch (e: Exception) {}
            try { process?.errorStream?.close() } catch (e: Exception) {}
            try { process?.destroy() } catch (e: Exception) {}
        }
    }

    override fun execute(command: String): CommandResult {
        var process: java.lang.Process? = null
        var writer: java.io.BufferedWriter? = null
        var outReader: BufferedReader? = null
        var errReader: BufferedReader? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            writer = java.io.BufferedWriter(java.io.OutputStreamWriter(process.outputStream, Charsets.UTF_8))
            
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
            
            // Parse and safely escape arguments to prevent command injection
            val args = parseCommand(command)
            if (args.isEmpty()) {
                return CommandResult(0, "", "")
            }
            
            val escapedCommand = args.joinToString(" ") { escapeShellArg(it) }
            
            writer.write(escapedCommand + "\n")
            writer.write("exit\n")
            writer.flush()
            
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
            CommandResult(-2, "", e.message ?: "Root execution error")
        } finally {
            try { writer?.close() } catch (e: Exception) {}
            try { outReader?.close() } catch (e: Exception) {}
            try { errReader?.close() } catch (e: Exception) {}
            try { process?.inputStream?.close() } catch (e: Exception) {}
            try { process?.outputStream?.close() } catch (e: Exception) {}
            try { process?.errorStream?.close() } catch (e: Exception) {}
            try { process?.destroy() } catch (e: Exception) {}
        }
    }

    private fun escapeShellArg(arg: String): String {
        return "'" + arg.replace("'", "'\\''") + "'"
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
        if (list.isNotEmpty() && list[0] == "adb") {
            if (list.size > 2 && list[1] == "shell") {
                return list.subList(2, list.size)
            }
        }
        return list
    }
}
