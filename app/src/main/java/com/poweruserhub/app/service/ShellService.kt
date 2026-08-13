package com.poweruserhub.app.service

import android.content.Context
import android.provider.Settings
import com.poweruserhub.app.model.SettingItem
import java.util.Locale

class ShellService(private val context: Context) {

    private val shizukuExecutor = ShizukuExecutor()
    private val rootExecutor = RootExecutor()
    private val adbExecutor = AdbExecutor(context)

    private var cachedExecutor: CommandExecutor? = null
    private var lastChecked: Long = 0
    @Volatile private var lastSettingsDiagnostic: String = ""

    @Synchronized
    fun getActiveExecutor(): CommandExecutor? {
        val now = System.currentTimeMillis()
        if (cachedExecutor == null || now - lastChecked > 10_000) {
            cachedExecutor = when {
                shizukuExecutor.isAvailable() -> shizukuExecutor
                rootExecutor.isAvailable() -> rootExecutor
                adbExecutor.isAvailable() -> adbExecutor
                else -> null
            }
            lastChecked = now
        }
        return cachedExecutor
    }

    fun invalidateBackendCache() {
        cachedExecutor = null
        lastChecked = 0
    }

    fun getActiveBackendName(): String {
        val executor = getActiveExecutor() ?: return "None (Limited Mode)"
        if (executor !== shizukuExecutor) return executor.getName()

        val plus = isShizukuPlusInstalled()
        val uidResult = executor.execute("id -u")
        val identity = if (uidResult.isSuccess) {
            when (uidResult.stdout.trim()) {
                "0" -> "root · uid 0"
                "1000" -> "system · uid 1000"
                "2000" -> "shell · uid 2000"
                else -> "uid ${uidResult.stdout.trim()}"
            }
        } else {
            "authorized"
        }
        return if (plus) "Shizuku+ ($identity)" else "Shizuku ($identity)"
    }

    fun isShizukuPlusInstalled(): Boolean {
        return hasPackage("af.shizuku.plus.api") || hasPackage("af.shizuku.plus")
    }

    private fun hasPackage(packageName: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isPrivilegedActive(): Boolean = getActiveExecutor() != null

    fun executeCommand(command: String): CommandResult {
        val executor = getActiveExecutor()
        return executor?.execute(command) ?: CommandResult(
            -1,
            "",
            "No active execution backend (Shizuku, Root, or ADB) is authorized."
        )
    }

    fun getPrivilegeProbe(): CommandResult {
        return executeCommand(
            "printf 'uid='; id -u; " +
                "printf 'user='; id -un; " +
                "printf 'settings='; command -v settings 2>/dev/null || true; " +
                "printf 'pm='; command -v pm 2>/dev/null || true; " +
                "printf 'am='; command -v am 2>/dev/null || true"
        )
    }

    /**
     * WRITE_SECURE_SETTINGS is a development/signature permission; merely declaring it
     * in AndroidManifest.xml does not grant it to a normal installed APK. Once Shizuku
     * or root is authorized, use that privileged identity to grant the declared
     * permission to Power User Hub itself. This makes direct SettingsProvider fallback
     * and APIs that check the app UID work in addition to shell-backed operations.
     */
    fun ensureAppPrivilegedPermissions(): CommandResult {
        val permission = android.Manifest.permission.WRITE_SECURE_SETTINGS
        if (context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return CommandResult(0, "WRITE_SECURE_SETTINGS already granted", "")
        }

        val executor = getActiveExecutor() ?: return CommandResult(
            -1,
            "",
            "Cannot grant WRITE_SECURE_SETTINGS without an authorized privileged backend."
        )
        val result = executor.execute(
            "pm grant ${shellQuote(context.packageName)} ${shellQuote(permission)}"
        )
        if (!result.isSuccess) return result

        return if (context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            CommandResult(0, "WRITE_SECURE_SETTINGS granted", "")
        } else {
            CommandResult(
                -6,
                result.stdout,
                "pm grant returned success but Android did not report WRITE_SECURE_SETTINGS as granted."
            )
        }
    }

    fun getLastSettingsDiagnostic(): String = lastSettingsDiagnostic

    fun readSetting(namespace: String, key: String): String {
        val ns = normalizeNamespace(namespace) ?: return "Error: invalid namespace"
        val executor = getActiveExecutor()
        if (executor != null) {
            val result = executor.execute("settings get $ns ${shellQuote(key)}")
            if (result.isSuccess && result.stdout.trim() != "null") {
                return result.stdout.trim()
            }
        }

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
            "Writing settings requires an authorized Shizuku, Root, or ADB backend."
        )
        val ns = normalizeNamespace(namespace)
            ?: return CommandResult(-1, "", "Unknown settings namespace: $namespace")

        val result = executor.execute(
            "settings put $ns ${shellQuote(key)} ${shellQuote(value)}"
        )
        if (!result.isSuccess) return result

        val readBack = executor.execute("settings get $ns ${shellQuote(key)}")
        if (!readBack.isSuccess) {
            return CommandResult(
                -4,
                result.stdout,
                "Write command returned success, but read-back failed: ${readBack.stderr}"
            )
        }
        if (readBack.stdout.trim() != value) {
            return CommandResult(
                -5,
                readBack.stdout,
                "Write was not verified. Expected '$value', read back '${readBack.stdout.trim()}'."
            )
        }
        return CommandResult(0, readBack.stdout.trim(), "")
    }

    fun readSettingsList(namespace: String): List<SettingItem> {
        val ns = normalizeNamespace(namespace) ?: run {
            lastSettingsDiagnostic = "Invalid namespace: $namespace"
            return emptyList()
        }

        val executor = getActiveExecutor()
        var permissionDiagnostic = ""
        if (executor != null) {
            val permissionResult = ensureAppPrivilegedPermissions()
            permissionDiagnostic = if (permissionResult.isSuccess) {
                "${permissionResult.stdout}. "
            } else {
                "WRITE_SECURE_SETTINGS bootstrap failed: ${permissionResult.stderr.ifBlank { permissionResult.stdout }}. "
            }

            val result = executor.execute("settings list $ns")
            if (result.isSuccess) {
                val parsed = parseSettingsOutput(result.stdout, namespace)
                if (parsed.isNotEmpty()) {
                    lastSettingsDiagnostic = permissionDiagnostic +
                        "Loaded ${parsed.size} $namespace rows through ${getActiveBackendName()}."
                    return parsed
                }
                lastSettingsDiagnostic = permissionDiagnostic +
                    "Privileged command succeeded but returned no $namespace rows."
            } else {
                lastSettingsDiagnostic = permissionDiagnostic +
                    "Privileged settings query failed (${result.exitCode}): ${result.stderr.ifBlank { "no stderr" }}"
            }
        } else {
            lastSettingsDiagnostic = "No authorized privileged backend. Trying Android SettingsProvider directly."
        }

        val providerRows = readSettingsProvider(namespace)
        if (providerRows.isNotEmpty()) {
            lastSettingsDiagnostic += " Provider fallback returned ${providerRows.size} readable rows."
            return providerRows
        }

        val fallback = readKnownSettings(namespace)
        if (fallback.isNotEmpty()) {
            lastSettingsDiagnostic += " Restricted provider fallback returned ${fallback.size} known rows."
        } else {
            lastSettingsDiagnostic += " No rows were readable. Check Shizuku authorization and service identity."
        }
        return fallback
    }

    private fun parseSettingsOutput(output: String, namespace: String): List<SettingItem> {
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('=') }
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null
                else SettingItem(
                    key = line.substring(0, index),
                    value = line.substring(index + 1),
                    namespace = namespace
                )
            }
            .distinctBy { it.key }
            .sortedBy { it.key.lowercase(Locale.ROOT) }
            .toList()
    }

    private fun readSettingsProvider(namespace: String): List<SettingItem> {
        val uri = when (namespace.uppercase(Locale.ROOT)) {
            "SYSTEM" -> Settings.System.CONTENT_URI
            "SECURE" -> Settings.Secure.CONTENT_URI
            "GLOBAL" -> Settings.Global.CONTENT_URI
            else -> return emptyList()
        }
        return try {
            val rows = mutableListOf<SettingItem>()
            context.contentResolver.query(
                uri,
                arrayOf("name", "value"),
                null,
                null,
                "name ASC"
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                val valueIndex = cursor.getColumnIndex("value")
                while (cursor.moveToNext()) {
                    if (nameIndex < 0) continue
                    val key = cursor.getString(nameIndex) ?: continue
                    val value = if (valueIndex >= 0) cursor.getString(valueIndex) ?: "" else ""
                    rows.add(SettingItem(key, value, namespace))
                }
            }
            rows.distinctBy { it.key }.sortedBy { it.key.lowercase(Locale.ROOT) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readKnownSettings(namespace: String): List<SettingItem> {
        val resolver = context.contentResolver
        val keys = when (namespace.uppercase(Locale.ROOT)) {
            "SYSTEM" -> listOf(
                Settings.System.SCREEN_BRIGHTNESS,
                Settings.System.SCREEN_OFF_TIMEOUT,
                Settings.System.ACCELEROMETER_ROTATION,
                "haptic_feedback_enabled",
                Settings.System.SOUND_EFFECTS_ENABLED
            )
            "SECURE" -> listOf(
                "adb_enabled",
                "location_mode",
                "install_non_market_apps",
                "sleep_timeout",
                "secure_properties"
            )
            "GLOBAL" -> listOf(
                "wifi_on",
                "bluetooth_on",
                "mobile_data",
                Settings.Global.AIRPLANE_MODE_ON,
                Settings.Global.AUTO_TIME,
                "development_settings_enabled"
            )
            else -> emptyList()
        }

        return keys.mapNotNull { key ->
            try {
                val value = when (namespace.uppercase(Locale.ROOT)) {
                    "SYSTEM" -> Settings.System.getString(resolver, key)
                    "SECURE" -> Settings.Secure.getString(resolver, key)
                    "GLOBAL" -> Settings.Global.getString(resolver, key)
                    else -> null
                }
                value?.let { SettingItem(key, it, namespace) }
            } catch (_: Exception) {
                null
            }
        }.sortedBy { it.key.lowercase(Locale.ROOT) }
    }

    // Component control -------------------------------------------------------

    fun startService(packageName: String, componentName: String, foreground: Boolean = false): CommandResult {
        val verb = if (foreground) "start-foreground-service" else "start-service"
        return executeCommand("am $verb --user current -n ${shellQuote(componentSpec(packageName, componentName))}")
    }

    fun stopService(packageName: String, componentName: String): CommandResult {
        return executeCommand("am stop-service --user current -n ${shellQuote(componentSpec(packageName, componentName))}")
    }

    fun sendBroadcast(packageName: String, componentName: String, action: String? = null): CommandResult {
        val actionArg = action?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { " -a ${shellQuote(it)}" }
            ?: ""
        return executeCommand(
            "am broadcast --user current$actionArg -n ${shellQuote(componentSpec(packageName, componentName))}"
        )
    }

    fun setComponentEnabled(packageName: String, componentName: String, enabled: Boolean): CommandResult {
        val command = if (enabled) "pm enable --user current" else "pm disable-user --user current"
        val spec = componentSpec(packageName, componentName)
        val result = executeCommand("$command ${shellQuote(spec)}")
        if (!result.isSuccess) return result

        // PackageManagerShellCommand performs setComponentEnabledSetting() and then
        // immediately calls getComponentEnabledSetting() before printing "new state".
        // Treat that shell output as the read-back rather than issuing a nonexistent
        // secondary pm command.
        val expectedState = if (enabled) "enabled" else "disabled-user"
        return if (result.stdout.contains("new state: $expectedState", ignoreCase = true)) {
            CommandResult(0, result.stdout, "")
        } else {
            CommandResult(
                -4,
                result.stdout,
                "Component command returned success but did not confirm expected state '$expectedState'."
            )
        }
    }

    private fun componentSpec(packageName: String, componentName: String): String {
        return "$packageName/$componentName"
    }

    // Battery Optimization ---------------------------------------------------

    fun setBatteryExempted(packageName: String, exempt: Boolean): CommandResult {
        val flag = if (exempt) "+" else "-"
        return executeCommand("dumpsys deviceidle whitelist ${shellQuote(flag + packageName)}")
    }

    fun isBatteryExempted(packageName: String): Boolean {
        val result = executeCommand("dumpsys deviceidle whitelist")
        return result.isSuccess && result.stdout.contains(packageName)
    }

    // App Standby Bucket -----------------------------------------------------

    fun setStandbyBucket(packageName: String, bucket: String): CommandResult {
        return executeCommand("am set-standby-bucket ${shellQuote(packageName)} ${shellQuote(bucket)}")
    }

    fun getStandbyBucket(packageName: String): String {
        val result = executeCommand("am get-standby-bucket ${shellQuote(packageName)}")
        if (result.isSuccess) {
            val output = result.stdout.trim()
            if (output.contains("active") || output == "10") return "active"
            if (output.contains("working_set") || output == "20") return "working_set"
            if (output.contains("frequent") || output == "30") return "frequent"
            if (output.contains("rare") || output == "40") return "rare"
            if (output.contains("restricted") || output == "45") return "restricted"
            return output
        }
        return "unknown"
    }

    // Background restrictions (AppOps) -------------------------------------

    fun setBackgroundRestricted(packageName: String, restricted: Boolean): CommandResult {
        val mode = if (restricted) "ignore" else "allow"
        return executeCommand(
            "cmd appops set ${shellQuote(packageName)} RUN_IN_BACKGROUND $mode"
        )
    }

    fun isBackgroundRestricted(packageName: String): Boolean {
        val result = executeCommand(
            "cmd appops get ${shellQuote(packageName)} RUN_IN_BACKGROUND"
        )
        return result.isSuccess && (result.stdout.contains("ignore") || result.stdout.contains("deny"))
    }

    private fun normalizeNamespace(namespace: String): String? {
        return when (namespace.uppercase(Locale.ROOT)) {
            "SYSTEM" -> "system"
            "SECURE" -> "secure"
            "GLOBAL" -> "global"
            else -> null
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
