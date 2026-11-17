package com.resonance.app

import android.content.Context
import org.json.JSONObject

/** Only user-confirmed metadata is considered a verified song label. */
data class SongInfo(
    val mediaId: String,
    val name: String,
    val artist: String = "",
    val album: String = "",
    val source: String = "user-confirmed",
    val savedAt: Long = 0L
)

object SongInfoStore {
    private const val INFO_PREFS = "resonance_song_info"
    private const val LEGACY_LABELS = "resonance_song_labels"

    fun get(context: Context, mediaId: String): SongInfo? {
        if (mediaId.isBlank()) return null
        val raw = context.getSharedPreferences(INFO_PREFS, Context.MODE_PRIVATE)
            .getString(mediaId, null)
        if (raw != null) {
            try {
                val obj = JSONObject(raw)
                val name = obj.optString("name")
                if (name.isNotBlank()) {
                    return SongInfo(
                        mediaId, name, obj.optString("artist"), obj.optString("album"),
                        obj.optString("source", "user-confirmed"), obj.optLong("savedAt")
                    )
                }
            } catch (_: Exception) { }
        }
        val oldName = context.getSharedPreferences(LEGACY_LABELS, Context.MODE_PRIVATE)
            .getString(mediaId, null)
        return oldName?.takeIf { it.isNotBlank() }?.let { SongInfo(mediaId, it, source = "legacy-manual") }
    }

    fun saveConfirmed(context: Context, mediaId: String, name: String, artist: String, album: String) {
        if (mediaId.isBlank() || name.isBlank()) return
        val info = JSONObject()
            .put("mediaId", mediaId).put("name", name.trim()).put("artist", artist.trim())
            .put("album", album.trim()).put("source", "user-confirmed")
            .put("savedAt", System.currentTimeMillis())
        context.getSharedPreferences(INFO_PREFS, Context.MODE_PRIVATE)
            .edit().putString(mediaId, info.toString()).apply()
        // Keep older v0.4/v0.5 label consumers compatible.
        context.getSharedPreferences(LEGACY_LABELS, Context.MODE_PRIVATE)
            .edit().putString(mediaId, name.trim()).apply()
    }

    fun exportJson(context: Context): JSONObject {
        val result = JSONObject()
        context.getSharedPreferences(INFO_PREFS, Context.MODE_PRIVATE).all.forEach { (id, value) ->
            if (value is String) {
                try { result.put(id, JSONObject(value)) } catch (_: Exception) { }
            }
        }
        return result
    }
}
