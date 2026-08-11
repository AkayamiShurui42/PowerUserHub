package com.poweruserhub.app.service

import android.content.Context

class AdbExecutor(private val context: Context) : CommandExecutor {

    override fun getName(): String = "Local ADB"

    override fun isAvailable(): Boolean {
        val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        val port = prefs.getInt("port", -1)
        val paired = prefs.getBoolean("paired", false)
        return port != -1 && paired
    }

    override fun execute(command: String): CommandResult {
        // Wireless debugging requires ADB protocol implementation.
        // We will output a message prompting the user that Shizuku is recommended,
        // or they can execute the command using their desktop computer's ADB shell:
        // "adb shell <command>"
        val cleanCommand = if (command.startsWith("adb shell ")) command else "adb shell $command"
        return CommandResult(
            -1, 
            "", 
            "ADB Local execution requires wireless handshake.\n" +
            "Please run this command on your computer instead:\n\n" +
            "   $cleanCommand"
        )
    }

    fun saveConfiguration(port: Int, paired: Boolean) {
        context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("port", port)
            .putBoolean("paired", paired)
            .apply()
    }
}
