package com.poweruserhub.app.service

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.util.Locale

enum class ServiceKnowledgeConfidence(val label: String) {
    UNKNOWN("Unknown"),
    INFERRED("Inferred"),
    OBSERVED("Observed"),
    USER_DEFINED("User defined")
}

data class ServiceKnowledge(
    val packageName: String,
    val componentName: String,
    val displayName: String,
    val description: String,
    val confidence: ServiceKnowledgeConfidence,
    val manualStarts: Int,
    val manualStops: Int,
    val autoRestarts: Int,
    val disappearances: Int,
    val protectionEnables: Int,
    val lastEvent: String,
    val protected: Boolean
) {
    val componentSpec: String get() = "$packageName/$componentName"

    fun observationSummary(): String {
        val parts = mutableListOf<String>()
        if (manualStarts > 0) parts += "$manualStarts manual start${if (manualStarts == 1) "" else "s"}"
        if (manualStops > 0) parts += "$manualStops manual stop${if (manualStops == 1) "" else "s"}"
        if (autoRestarts > 0) parts += "$autoRestarts watchdog restore${if (autoRestarts == 1) "" else "s"}"
        if (disappearances > 0) parts += "$disappearances disappearance${if (disappearances == 1) "" else "s"}"
        return if (parts.isEmpty()) "No runtime behavior has been learned yet." else "Observed: ${parts.joinToString(" · ")}"
    }
}

/**
 * Local, device-specific service knowledge base.
 *
 * Raw component names are never hidden. This layer adds human terminology and records
 * observed behavior so a future community backend can submit reproducible findings instead
 * of anonymous guesses about what a service might do.
 */
class ServiceKnowledgeStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("service_knowledge_v1", Context.MODE_PRIVATE)

    fun get(packageName: String, componentName: String): ServiceKnowledge {
        val id = id(packageName, componentName)
        val customLabel = prefs.getString("$id:label", null)?.trim().orEmpty()
        val customDescription = prefs.getString("$id:description", null)?.trim().orEmpty()
        val manualStarts = prefs.getInt("$id:manual_starts", 0)
        val manualStops = prefs.getInt("$id:manual_stops", 0)
        val autoRestarts = prefs.getInt("$id:auto_restarts", 0)
        val disappearances = prefs.getInt("$id:disappearances", 0)
        val protectionEnables = prefs.getInt("$id:protection_enables", 0)
        val lastEvent = prefs.getString("$id:last_event", "") ?: ""
        val protected = prefs.getBoolean("$id:protected", false)

        val inferredName = inferDisplayName(componentName)
        val inferredDescription = inferDescription(inferredName)
        val confidence = when {
            customLabel.isNotEmpty() || customDescription.isNotEmpty() -> ServiceKnowledgeConfidence.USER_DEFINED
            manualStarts + manualStops + autoRestarts + disappearances + protectionEnables > 0 -> ServiceKnowledgeConfidence.OBSERVED
            inferredDescription != GENERIC_DESCRIPTION -> ServiceKnowledgeConfidence.INFERRED
            else -> ServiceKnowledgeConfidence.UNKNOWN
        }

        return ServiceKnowledge(
            packageName = packageName,
            componentName = componentName,
            displayName = customLabel.ifEmpty { inferredName },
            description = customDescription.ifEmpty { inferredDescription },
            confidence = confidence,
            manualStarts = manualStarts,
            manualStops = manualStops,
            autoRestarts = autoRestarts,
            disappearances = disappearances,
            protectionEnables = protectionEnables,
            lastEvent = lastEvent,
            protected = protected
        )
    }

    fun setCustomMetadata(packageName: String, componentName: String, label: String, description: String) {
        val id = id(packageName, componentName)
        prefs.edit()
            .putString("$id:label", label.trim())
            .putString("$id:description", description.trim())
            .apply()
    }

    fun recordManualStart(packageName: String, componentName: String) {
        increment(packageName, componentName, "manual_starts", "Manually started")
    }

    fun recordManualStop(packageName: String, componentName: String) {
        increment(packageName, componentName, "manual_stops", "Manually stopped")
    }

    fun setProtected(packageName: String, componentName: String, enabled: Boolean) {
        val id = id(packageName, componentName)
        val editor = prefs.edit().putBoolean("$id:protected", enabled)
            .putString("$id:last_event", if (enabled) "Keep Alive enabled" else "Keep Alive disabled")
        if (enabled) {
            editor.putInt("$id:protection_enables", prefs.getInt("$id:protection_enables", 0) + 1)
        }
        editor.apply()
    }

    fun getProtectedSpecs(): Set<String> {
        val suffix = ":protected"
        return prefs.all.entries.mapNotNull { (key, value) ->
            if (!key.endsWith(suffix) || value != true) return@mapNotNull null
            decodeId(key.removeSuffix(suffix))
        }.toSet()
    }

    /**
     * Consumes tab-delimited events emitted by the privileged daemon.
     * Format: timestamp, type, package, component, details.
     */
    fun applyWatchdogEvents(events: List<String>): Int {
        var applied = 0
        events.forEach { row ->
            val fields = row.split('\t', limit = 5)
            if (fields.size < 4) return@forEach
            val type = fields[1]
            val pkg = fields[2]
            val component = fields[3]
            if (pkg.isBlank() || component.isBlank()) return@forEach
            val details = fields.getOrNull(4).orEmpty()
            val id = id(pkg, component)
            val editor = prefs.edit()
                .putString("$id:last_event", "$type${details.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
            when (type) {
                "AUTO_RESTARTED" -> editor.putInt("$id:auto_restarts", prefs.getInt("$id:auto_restarts", 0) + 1)
                "DISAPPEARED" -> editor.putInt("$id:disappearances", prefs.getInt("$id:disappearances", 0) + 1)
                "PROTECTED" -> editor.putBoolean("$id:protected", true)
                "UNPROTECTED" -> editor.putBoolean("$id:protected", false)
            }
            editor.apply()
            applied++
        }
        return applied
    }

    /** Community-ready record; transport and moderation are intentionally separate. */
    fun buildCommunityRecord(packageName: String, componentName: String): JSONObject {
        val knowledge = get(packageName, componentName)
        return JSONObject()
            .put("schema", 1)
            .put("package", packageName)
            .put("component", componentName)
            .put("displayName", knowledge.displayName)
            .put("description", knowledge.description)
            .put("confidence", knowledge.confidence.name)
            .put("manualStarts", knowledge.manualStarts)
            .put("manualStops", knowledge.manualStops)
            .put("autoRestarts", knowledge.autoRestarts)
            .put("disappearances", knowledge.disappearances)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            .put("fingerprint", Build.FINGERPRINT)
    }

    private fun increment(packageName: String, componentName: String, field: String, event: String) {
        val id = id(packageName, componentName)
        prefs.edit()
            .putInt("$id:$field", prefs.getInt("$id:$field", 0) + 1)
            .putString("$id:last_event", event)
            .apply()
    }

    private fun inferDisplayName(componentName: String): String {
        var name = componentName.substringAfterLast('.')
            .removeSuffix("Service")
            .replace('_', ' ')
            .replace('$', ' ')
        name = name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (name.isBlank()) componentName.substringAfterLast('.') else name
    }

    private fun inferDescription(displayName: String): String {
        val lower = displayName.lowercase(Locale.ROOT)
        return when {
            "location" in lower || "gps" in lower -> "Component name suggests location or GPS-related background work. The purpose is inferred from the name and has not been independently verified yet."
            "sync" in lower -> "Component name suggests synchronization or account/data refresh work. PowerHub will refine this description from observed behavior."
            "notification" in lower -> "Component name suggests notification delivery, monitoring, or notification-side background work. This is currently an inference."
            "media" in lower || "audio" in lower || "music" in lower -> "Component name suggests media or audio-related background work. PowerHub has not yet verified the exact responsibility."
            "vpn" in lower -> "Component name suggests VPN or tunnel-related background work. The exact role should be verified from runtime behavior or trusted documentation."
            "accessibility" in lower -> "Component name suggests accessibility-related background work. Changing or stopping it may affect accessibility features."
            "bluetooth" in lower -> "Component name suggests Bluetooth-related background work. PowerHub will record starts, stops, and watchdog restores to build evidence."
            "push" in lower || "message" in lower || "firebase" in lower -> "Component name suggests push messaging or message-delivery background work. This is an inferred description."
            "update" in lower -> "Component name suggests update checking, downloading, or installation support. The exact responsibility is not verified yet."
            "game" in lower -> "Component name suggests game-related support or optimization work. PowerHub will learn more as the component is used."
            "battery" in lower || "power" in lower -> "Component name suggests battery, charging, or power-management work. Treat changes cautiously until behavior is verified."
            else -> GENERIC_DESCRIPTION
        }
    }

    private fun id(packageName: String, componentName: String): String {
        return "svc|$packageName|$componentName"
    }

    private fun decodeId(id: String): String? {
        if (!id.startsWith("svc|")) return null
        val first = id.indexOf('|', 4)
        if (first < 0) return null
        val pkg = id.substring(4, first)
        val component = id.substring(first + 1)
        if (pkg.isBlank() || component.isBlank()) return null
        return "$pkg/$component"
    }

    companion object {
        private const val GENERIC_DESCRIPTION =
            "Purpose not verified yet. PowerHub will build an observed history as this service is started, stopped, protected, or restored."
    }
}
