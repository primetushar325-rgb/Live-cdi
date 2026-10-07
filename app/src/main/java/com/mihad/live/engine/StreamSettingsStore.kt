package com.mihad.live.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sensitive RTMP keys are saved only when the user explicitly enables "Remember key".
 * They live in AndroidX EncryptedSharedPreferences (AES-256-GCM + AES-256-SIV) and are
 * never copied into the project/history records or diagnostic logs.
 */
class StreamSettingsStore(context: Context) {

    private val appContext = context.applicationContext
    private val plain: SharedPreferences = appContext.getSharedPreferences("mihad_live", Context.MODE_PRIVATE)
    private val secure: SharedPreferences? by lazy { createSecurePreferences() }

    fun rememberedKey(): String? = runCatching { secure?.getString(KEY_STREAM_KEY, null) }.getOrNull()

    fun saveRememberedKey(key: String?) {
        runCatching {
            val prefs = secure ?: return
            if (key.isNullOrBlank()) prefs.edit().remove(KEY_STREAM_KEY).apply()
            else prefs.edit().putString(KEY_STREAM_KEY, key).apply()
        }.onFailure {
            Log.w(TAG, "SECURE_KEY_STORAGE_UNAVAILABLE")
        }
    }

    fun saveServerUrl(url: String) {
        plain.edit().putString(KEY_SERVER_URL, url).apply()
    }

    fun serverUrl(): String = plain.getString(KEY_SERVER_URL, "") ?: ""

    fun saveProject(project: SavedProject) {
        val old = readObjects(KEY_PROJECTS)
        val items = JSONArray()
        for (index in 0 until old.length()) {
            val item = old.optJSONObject(index) ?: continue
            val sameProject = item.optString("name") == project.name &&
                item.optString("source") == project.sourceName
            if (!sameProject) items.put(item)
        }
        val merged = JSONArray().put(JSONObject().apply {
            put("name", project.name)
            put("source", project.sourceName)
            put("uri", project.sourceUri)
            put("format", project.format.name)
            put("quality", project.quality.name)
            put("savedAt", project.savedAtMs)
        })
        for (index in 0 until minOf(items.length(), 7)) merged.put(items.getJSONObject(index))
        plain.edit().putString(KEY_PROJECTS, merged.toString()).apply()
    }

    fun projects(): List<SavedProject> = readObjects(KEY_PROJECTS).let { array ->
        (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                SavedProject(
                    name = o.getString("name"),
                    sourceName = o.optString("source", "Video"),
                    sourceUri = o.optString("uri", ""),
                    format = LiveFormat.valueOf(o.getString("format")), 
                    quality = VideoQuality.valueOf(o.optString("quality", VideoQuality.P720.name)),
                    savedAtMs = o.optLong("savedAt")
                )
            }.getOrNull()
        }
    }

    fun addHistory(entry: LiveHistoryEntry) {
        val items = readObjects(KEY_HISTORY)
        items.put(JSONObject().apply {
            put("name", entry.name)
            put("format", entry.format.name)
            put("resolution", entry.resolution)
            put("startedAt", entry.startedAtMs)
            put("duration", entry.durationMs)
            put("result", entry.result)
        })
        val trimmed = JSONArray()
        for (index in maxOf(0, items.length() - 20) until items.length()) trimmed.put(items.getJSONObject(index))
        plain.edit().putString(KEY_HISTORY, trimmed.toString()).apply()
    }

    fun history(): List<LiveHistoryEntry> = readObjects(KEY_HISTORY).let { array ->
        (array.length() - 1 downTo 0).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                LiveHistoryEntry(
                    name = o.optString("name", "Untitled live"),
                    format = LiveFormat.valueOf(o.getString("format")),
                    resolution = o.optString("resolution", "N/A"),
                    startedAtMs = o.optLong("startedAt"),
                    durationMs = o.optLong("duration"),
                    result = o.optString("result", "Stopped")
                )
            }.getOrNull()
        }
    }

    private fun readObjects(key: String): JSONArray = runCatching {
        JSONArray(plain.getString(key, "[]") ?: "[]")
    }.getOrDefault(JSONArray())

    private fun createSecurePreferences(): SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "mihad_live_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.onFailure {
        Log.w(TAG, "SECURE_KEY_STORAGE_UNAVAILABLE")
    }.getOrNull()

    companion object {
        private const val TAG = "MihadSettings"
        private const val KEY_STREAM_KEY = "stream_key"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_PROJECTS = "projects"
        private const val KEY_HISTORY = "history"
    }
}
