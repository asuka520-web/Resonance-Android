package com.resonance.app

import android.content.Context
import org.json.JSONObject

/** Automatic metadata keeps explicit provenance and never replaces manual data. */
data class SongInfo(
    val mediaId: String,
    val name: String,
    val artist: String = "",
    val album: String = "",
    val source: String = "user-confirmed",
    val savedAt: Long = 0L
) {
    val automatic: Boolean get() = source == "qq-session-artist"
}

object SongInfoResolver {
    fun decode(mediaId: String, raw: String?): SongInfo? {
        if (raw == null) return null
        return try {
            val obj = JSONObject(raw)
            val name = obj.optString("name")
            if (name.isBlank()) null else SongInfo(mediaId, name, obj.optString("artist"),
                obj.optString("album"), obj.optString("source", "user-confirmed"), obj.optLong("savedAt"))
        } catch (_: Exception) { null }
    }

    fun resolve(mediaId: String, manual: String?, legacy: String?, automatic: String?): SongInfo? {
        return decode(mediaId, manual)
            ?: legacy?.takeIf { it.isNotBlank() }?.let { SongInfo(mediaId, it, source = "legacy-manual") }
            ?: decode(mediaId, automatic)?.takeIf { it.automatic }
    }

    fun encode(info: SongInfo): JSONObject = JSONObject()
        .put("mediaId", info.mediaId).put("name", info.name).put("artist", info.artist)
        .put("album", info.album).put("source", info.source).put("savedAt", info.savedAt)
}

object SongInfoStore {
    private const val INFO_PREFS = "resonance_song_info"
    private const val LEGACY_LABELS = "resonance_song_labels"
    private const val AUTO_PREFS = "resonance_auto_song_info"

    fun get(context: Context, mediaId: String): SongInfo? {
        if (mediaId.isBlank()) return null
        return SongInfoResolver.resolve(mediaId,
            context.getSharedPreferences(INFO_PREFS, Context.MODE_PRIVATE).getString(mediaId, null),
            context.getSharedPreferences(LEGACY_LABELS, Context.MODE_PRIVATE).getString(mediaId, null),
            context.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE).getString(mediaId, null))
    }

    fun saveAutomatic(context: Context, song: ParsedQqSong): Boolean {
        val manual = SongInfoResolver.resolve(song.mediaId,
            context.getSharedPreferences(INFO_PREFS, Context.MODE_PRIVATE).getString(song.mediaId, null),
            context.getSharedPreferences(LEGACY_LABELS, Context.MODE_PRIVATE).getString(song.mediaId, null), null)
        if (manual != null) return false
        val prefs = context.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE)
        val existing = SongInfoResolver.decode(song.mediaId, prefs.getString(song.mediaId, null))
        if (existing?.name == song.name && existing.artist == song.artist && existing.album == song.album) return false
        val info = SongInfo(song.mediaId, song.name, song.artist, song.album, "qq-session-artist", System.currentTimeMillis())
        prefs.edit().putString(song.mediaId, SongInfoResolver.encode(info).toString()).apply()
        return true
    }

    fun invalidateAutomatic(context: Context, mediaId: String): Boolean {
        val prefs = context.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(mediaId)) return false
        prefs.edit().remove(mediaId).apply()
        return true
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
        val ids = listOf(INFO_PREFS, LEGACY_LABELS, AUTO_PREFS).flatMap {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).all.keys
        }.toSet()
        ids.forEach { id ->
            get(context, id)?.let { result.put(id, SongInfoResolver.encode(it)) }
        }
        return result
    }
}
