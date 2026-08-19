package com.poweruserhub.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Small runtime client for the moderated community catalog.
 *
 * The app only downloads descriptive evidence. It never downloads or executes commands.
 * The last successful catalog is cached so descriptions remain available offline.
 */
class CommunityKnowledgeClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("community_catalog_v1", Context.MODE_PRIVATE)

    fun refresh(): Result<Int> = runCatching {
        val connection = URL(CATALOG_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 7_000
            connection.readTimeout = 7_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("Catalog HTTP ${connection.responseCode}")
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            if (root.optInt("schema") != 1) error("Unsupported community catalog schema")
            val entries = root.optJSONArray("entries") ?: JSONArray()
            prefs.edit().putString("catalog", root.toString()).apply()
            entries.length()
        } finally {
            connection.disconnect()
        }
    }

    fun findSetting(namespace: String, key: String): JSONObject? {
        return entries().firstOrNull { item ->
            item.optString("kind") == "setting" &&
                item.optString("namespace").equals(namespace, true) &&
                item.optString("key") == key
        }
    }

    fun findService(packageName: String, componentName: String): JSONObject? {
        return entries().firstOrNull { item ->
            item.optString("kind") == "service" &&
                item.optString("package") == packageName &&
                item.optString("component") == componentName
        }
    }

    private fun entries(): List<JSONObject> {
        val raw = prefs.getString("catalog", null) ?: return emptyList()
        return try {
            val root = JSONObject(raw)
            val array = root.optJSONArray("entries") ?: return emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let(::add)
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    companion object {
        private const val CATALOG_URL =
            "https://raw.githubusercontent.com/AkayamiShurui42/PowerUserHub/master/community/catalog-v1.json"
    }
}

object CommunitySubmissionHelper {
    fun openSubmission(context: Context, title: String, record: JSONObject): Result<Unit> = runCatching {
        val body = "Power User Hub generated finding:\n\n```json\n${record.toString(2)}\n```"
        val url = buildString {
            append("https://github.com/AkayamiShurui42/PowerUserHub/issues/new")
            append("?template=community-finding.yml")
            append("&title=")
            append(encode("[Community Finding] $title"))
            append("&body=")
            append(encode(body))
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun shareRecord(context: Context, title: String, record: JSONObject): Result<Unit> = runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, record.toString(2))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share PowerHub finding").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }
}
