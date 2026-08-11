package com.poweruserhub.app.service

import android.content.Context
import android.provider.Settings
import com.poweruserhub.app.model.SettingItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShellService(private val context: Context) {

    private val shizukuExecutor = ShizukuExecutor()
    private val rootExecutor = RootExecutor()
    private val adbExecutor = AdbExecutor(context)

    fun getActiveExecutor(): CommandExecutor? {
        return when {
            shizukuExecutor.isAvailable() -> shizukuExecutor
            rootExecutor.isAvailable() -> rootExecutor
            adbExecutor.isAvailable() -> adbExecutor
            else -> null
        }
    }

    fun getActiveBackendName(): String {
        return getActiveExecutor()?.getName() ?: "None (Limited Mode)"
    }

    fun isPrivilegedActive(): Boolean {
        return getActiveExecutor() != null
    }

    fun executeCommand(command: String): CommandResult {
        val executor = getActiveExecutor()
        return executor?.execute(command) ?: CommandResult(
            -1, 
            "", 
            "No active execution backend (Shizuku, Root, or ADB) is authorized."
        )
    }

    fun readSetting(namespace: String, key: String): String {
        val executor = getActiveExecutor()
        if (executor != null) {
            val ns = namespace.lowercase(Locale.ROOT)
            val result = executor.execute("settings get $ns $key")
            if (result.isSuccess && result.stdout.trim() != "null") {
                return result.stdout.trim()
            }
        }
        
        // Fallback to ContentResolver for reading
        return try {
            val resolver = context.contentResolver
            when (namespace.uppercase(Locale.ROOT)) {
                "SYSTEM" -> Settings.System.getString(resolver, key) ?: "null"
                "SECURE" -> Settings.Secure.getString(resolver, key) ?: "null"
                "GLOBAL" -> Settings.Global.getString(resolver, key) ?: "null"
                else -> "null"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun writeSetting(namespace: String, key: String, value: String): CommandResult {
        val executor = getActiveExecutor() ?: return CommandResult(
            -1, 
            "", 
            "Writing settings requires Shizuku, Root, or ADB backend."
        )
        val ns = namespace.lowercase(Locale.ROOT)
        return executor.execute("settings put $ns $key \"$value\"")
    }

    fun readSettingsList(namespace: String): List<SettingItem> {
        val executor = getActiveExecutor()
        val items = mutableListOf<SettingItem>()

        if (executor != null) {
            val ns = namespace.lowercase(Locale.ROOT)
            val result = executor.execute("settings list $ns")
            if (result.isSuccess) {
                result.stdout.split("\n").forEach { line ->
                    if (line.contains("=")) {
                        val parts = line.split("=", limit = 2)
                        if (parts.size == 2) {
                            items.add(SettingItem(parts[0], parts[1], namespace))
                        }
                    }
                }
                return items.sortedBy { it.key }
            }
        }

        // Fallback to reading popular settings keys via ContentResolver
        val resolver = context.contentResolver
        val popularKeys = when (namespace.uppercase(Locale.ROOT)) {
            "SYSTEM" -> listOf(
                Settings.System.SCREEN_BRIGHTNESS,
                Settings.System.SCREEN_OFF_TIMEOUT,
                Settings.System.ACCELEROMETER_ROTATION,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                Settings.System.SOUND_EFFECTS_ENABLED
            )
            "SECURE" -> listOf(
                Settings.Secure.ADB_ENABLED,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.INSTALL_NON_MARKET_APPS,
                "sleep_timeout",
                "secure_properties"
            )
            "GLOBAL" -> listOf(
                "wifi_on",
                "bluetooth_on",
                Settings.Global.MOBILE_DATA,
                Settings.Global.AIRPLANE_MODE_ON,
                Settings.Global.AUTO_TIME,
                "development_settings_enabled"
            )
            else -> emptyList()
        }

        popularKeys.forEach { key ->
            try {
                val value = when (namespace.uppercase(Locale.ROOT)) {
                    "SYSTEM" -> Settings.System.getString(resolver, key)
                    "SECURE" -> Settings.Secure.getString(resolver, key)
                    "GLOBAL" -> Settings.Global.getString(resolver, key)
                    else -> null
                }
                if (value != null) {
                    items.add(SettingItem(key, value, namespace))
                }
            } catch (e: Exception) {
                // Ignore key read errors in fallback
            }
        }
        return items.sortedBy { it.key }
    }

    // Battery Optimization
    fun setBatteryExempted(packageName: String, exempt: Boolean): CommandResult {
        val flag = if (exempt) "+" else "-"
        return executeCommand("dumpsys deviceidle whitelist $flag$packageName")
    }

    fun isBatteryExempted(packageName: String): Boolean {
        val result = executeCommand("dumpsys deviceidle whitelist")
        return if (result.isSuccess) {
            result.stdout.contains(packageName)
        } else {
            false
        }
    }

    // App Standby Bucket
    fun setStandbyBucket(packageName: String, bucket: String): CommandResult {
        return executeCommand("am set-standby-bucket $packageName $bucket")
    }

    fun getStandbyBucket(packageName: String): String {
        val result = executeCommand("am get-standby-bucket $packageName")
        if (result.isSuccess) {
            val output = result.stdout.trim()
            // am get-standby-bucket output format: "10" or "App Standby Bucket: active"
            if (output.contains("active") || output.contains("10")) return "active"
            if (output.contains("working_set") || output.contains("20")) return "working_set"
            if (output.contains("frequent") || output.contains("30")) return "frequent"
            if (output.contains("rare") || output.contains("40")) return "rare"
            if (output.contains("restricted") || output.contains("45")) return "restricted"
            return output
        }
        return "unknown"
    }

    // Background restrictions (AppOps)
    fun setBackgroundRestricted(packageName: String, restricted: Boolean): CommandResult {
        val mode = if (restricted) "ignore" else "allow"
        return executeCommand("cmd appops set $packageName RUN_IN_BACKGROUND $mode")
    }

    fun isBackgroundRestricted(packageName: String): Boolean {
        val result = executeCommand("cmd appops get $packageName RUN_IN_BACKGROUND")
        return if (result.isSuccess) {
            result.stdout.contains("ignore") || result.stdout.contains("deny")
        } else {
            false
        }
    }
}
