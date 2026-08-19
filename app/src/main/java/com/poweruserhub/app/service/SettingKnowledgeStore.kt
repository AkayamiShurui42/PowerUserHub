package com.poweruserhub.app.service

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class SettingCorrelation(
    val namespace: String,
    val key: String,
    val before: String,
    val after: String,
    val observations: Int
)

data class SettingKnowledge(
    val namespace: String,
    val key: String,
    val displayName: String,
    val description: String,
    val acceptedValues: List<String>,
    val rejectedValues: List<String>,
    val correlations: List<SettingCorrelation>,
    val lastResult: String
) {
    val confidenceLabel: String
        get() = when {
            correlations.any { it.observations >= 3 } -> "Probable"
            acceptedValues.size >= 2 || correlations.isNotEmpty() -> "Observed"
            else -> "Unknown"
        }
}

/**
 * Device-local knowledge base for SettingsProvider variables.
 *
 * It records values Android accepted/rejected and side effects observed in the surrounding
 * SettingsProvider tables. This is evidence, not proof of causation, so correlations are
 * stored with repeat counts instead of being silently promoted into facts.
 */
class SettingKnowledgeStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("setting_knowledge_v1", Context.MODE_PRIVATE)

    fun get(namespace: String, key: String): SettingKnowledge {
        val id = id(namespace, key)
        val accepted = readStringSet("$id:accepted")
        val rejected = readStringSet("$id:rejected")
        val correlations = readCorrelations(id)
        val name = prefs.getString("$id:label", null)?.takeIf { it.isNotBlank() } ?: inferDisplayName(key)
        val customDescription = prefs.getString("$id:description", null)?.takeIf { it.isNotBlank() }
        val description = customDescription ?: inferDescription(key, correlations)
        return SettingKnowledge(
            namespace = namespace,
            key = key,
            displayName = name,
            description = description,
            acceptedValues = accepted.sorted(),
            rejectedValues = rejected.sorted(),
            correlations = correlations.sortedByDescending { it.observations },
            lastResult = prefs.getString("$id:last_result", "") ?: ""
        )
    }

    fun recordAccepted(
        namespace: String,
        key: String,
        oldValue: String,
        requestedValue: String,
        readBackValue: String,
        correlations: List<SettingCorrelation>
    ) {
        val id = id(namespace, key)
        val accepted = readStringSet("$id:accepted").toMutableSet()
        accepted += readBackValue
        if (oldValue.isNotBlank() && oldValue != "null") accepted += oldValue

        val normalized = requestedValue != readBackValue
        prefs.edit()
            .putStringSet("$id:accepted", accepted)
            .putString(
                "$id:last_result",
                if (normalized) "Requested '$requestedValue'; Android normalized it to '$readBackValue'."
                else "Android accepted '$requestedValue'."
            )
            .apply()
        mergeCorrelations(id, correlations)
    }

    fun recordRejected(namespace: String, key: String, attemptedValue: String, reason: String) {
        val id = id(namespace, key)
        val rejected = readStringSet("$id:rejected").toMutableSet()
        rejected += attemptedValue
        prefs.edit()
            .putStringSet("$id:rejected", rejected)
            .putString("$id:last_result", "Rejected '$attemptedValue': ${reason.take(240)}")
            .apply()
    }

    fun setCustomMetadata(namespace: String, key: String, label: String, description: String) {
        val id = id(namespace, key)
        prefs.edit()
            .putString("$id:label", label.trim())
            .putString("$id:description", description.trim())
            .apply()
    }

    fun buildCommunityRecord(namespace: String, key: String): JSONObject {
        val knowledge = get(namespace, key)
        val accepted = JSONArray().apply {
            knowledge.acceptedValues.forEach { value -> put(value) }
        }
        val rejected = JSONArray().apply {
            knowledge.rejectedValues.forEach { value -> put(value) }
        }
        val correlations = JSONArray().apply {
            knowledge.correlations.forEach { c ->
                put(
                    JSONObject()
                        .put("namespace", c.namespace)
                        .put("key", c.key)
                        .put("before", c.before)
                        .put("after", c.after)
                        .put("observations", c.observations)
                )
            }
        }
        return JSONObject()
            .put("schema", 1)
            .put("kind", "setting")
            .put("namespace", namespace.uppercase(Locale.ROOT))
            .put("key", key)
            .put("displayName", knowledge.displayName)
            .put("description", knowledge.description)
            .put("confidence", knowledge.confidenceLabel)
            .put("acceptedValues", accepted)
            .put("rejectedValues", rejected)
            .put("correlations", correlations)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            .put("fingerprint", Build.FINGERPRINT)
    }

    private fun mergeCorrelations(id: String, observed: List<SettingCorrelation>) {
        if (observed.isEmpty()) return
        val existing = readCorrelations(id).associateBy { correlationId(it) }.toMutableMap()
        observed.forEach { candidate ->
            val key = correlationId(candidate)
            val old = existing[key]
            existing[key] = if (old == null) {
                candidate.copy(observations = 1)
            } else {
                old.copy(observations = old.observations + 1)
            }
        }
        val json = JSONArray()
        existing.values
            .sortedByDescending { it.observations }
            .take(80)
            .forEach { c ->
                json.put(
                    JSONObject()
                        .put("namespace", c.namespace)
                        .put("key", c.key)
                        .put("before", c.before)
                        .put("after", c.after)
                        .put("observations", c.observations)
                )
            }
        prefs.edit().putString("$id:correlations", json.toString()).apply()
    }

    private fun readCorrelations(id: String): List<SettingCorrelation> {
        val raw = prefs.getString("$id:correlations", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        SettingCorrelation(
                            namespace = item.optString("namespace"),
                            key = item.optString("key"),
                            before = item.optString("before"),
                            after = item.optString("after"),
                            observations = item.optInt("observations", 1)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun readStringSet(key: String): Set<String> {
        return prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()
    }

    private fun correlationId(c: SettingCorrelation): String {
        return "${c.namespace}|${c.key}|${c.before}|${c.after}"
    }

    private fun inferDisplayName(key: String): String {
        return key
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                when (token.lowercase(Locale.ROOT)) {
                    "fps" -> "FPS"
                    "gpu" -> "GPU"
                    "cpu" -> "CPU"
                    "wifi" -> "Wi-Fi"
                    "adb" -> "ADB"
                    "hdr" -> "HDR"
                    "ui" -> "UI"
                    else -> token.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                }
            }
            .ifBlank { key }
    }

    private fun inferDescription(key: String, correlations: List<SettingCorrelation>): String {
        val lower = key.lowercase(Locale.ROOT)
        val base = when {
            "refresh" in lower || "fps" in lower -> "Name suggests a display refresh-rate or frame-rate control."
            "brightness" in lower -> "Name suggests a display-brightness control."
            "animation" in lower || "animator" in lower -> "Name suggests an Android animation timing or scaling control."
            "wifi" in lower -> "Name suggests Wi-Fi behavior or state."
            "bluetooth" in lower -> "Name suggests Bluetooth behavior or state."
            "location" in lower || "gps" in lower -> "Name suggests location or GPS behavior."
            "battery" in lower || "power" in lower -> "Name suggests battery or power-management behavior."
            "game" in lower -> "Name suggests game-mode or game-optimization behavior."
            "gpu" in lower || "renderer" in lower || "hwui" in lower -> "Name suggests graphics rendering or GPU behavior."
            "adb" in lower || "debug" in lower -> "Name suggests debugging or developer behavior."
            else -> "Purpose not verified yet. PowerHub learns accepted values and correlated setting changes when you experiment with it."
        }
        val strongest = correlations.maxByOrNull { it.observations }
        return if (strongest != null) {
            "$base Repeated observations currently associate changes here with ${strongest.namespace}.${strongest.key} changing from '${strongest.before}' to '${strongest.after}' (${strongest.observations} observation${if (strongest.observations == 1) "" else "s"}). This is correlation, not proof of causation."
        } else base
    }

    private fun id(namespace: String, key: String): String {
        return "setting|${namespace.uppercase(Locale.ROOT)}|$key"
    }
}

/** Captures and compares SettingsProvider state around one deliberate experiment. */
class SettingObservationEngine(private val shellService: ShellService) {

    fun snapshot(): Map<String, String> {
        val output = linkedMapOf<String, String>()
        listOf("SYSTEM", "SECURE", "GLOBAL").forEach { namespace ->
            shellService.readSettingsList(namespace).forEach { item ->
                output["$namespace|${item.key}"] = item.value
            }
        }
        return output
    }

    fun diff(
        before: Map<String, String>,
        after: Map<String, String>,
        targetNamespace: String,
        targetKey: String
    ): List<SettingCorrelation> {
        val targetId = "${targetNamespace.uppercase(Locale.ROOT)}|$targetKey"
        return (before.keys + after.keys)
            .asSequence()
            .filter { it != targetId }
            .distinct()
            .mapNotNull { id ->
                val old = before[id]
                val new = after[id]
                if (old == new || old == null || new == null) return@mapNotNull null
                val split = id.indexOf('|')
                if (split <= 0) return@mapNotNull null
                SettingCorrelation(
                    namespace = id.substring(0, split),
                    key = id.substring(split + 1),
                    before = old,
                    after = new,
                    observations = 1
                )
            }
            .take(100)
            .toList()
    }
}
