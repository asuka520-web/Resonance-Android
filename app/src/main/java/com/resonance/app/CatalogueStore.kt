package com.resonance.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class CatalogueGenre(val mid: String, val labels: Set<String>, val source: String, val evidence: String,
                          val updatedAt: Long, val retryAt: Long, val recordingId: String = "")

object CatalogueCodec {
    fun track(song: QqCatalogueTrack): JSONObject = JSONObject().put("mid", song.mid).put("mediaMid", song.mediaMid)
        .put("name", song.name).put("artist", song.artist).put("album", song.album).put("durationMs", song.durationMs)
        .put("songId", song.songId).put("albumMid", song.albumMid)
    fun track(obj: JSONObject): QqCatalogueTrack? {
        val mid = obj.optString("mid")
        val name = obj.optString("name")
        if (!mid.matches(Regex("[A-Za-z0-9]{1,80}")) || name.isBlank()) return null
        return QqCatalogueTrack(mid, obj.optString("mediaMid").ifBlank { mid }, name, obj.optString("artist"),
            obj.optString("album"), obj.optLong("durationMs"), obj.optString("songId"), obj.optString("albumMid"))
    }
    fun genres(value: CatalogueGenre): String = JSONObject().put("mid", value.mid)
        .put("labels", JSONArray(value.labels.sorted())).put("source", value.source).put("evidence", value.evidence)
        .put("updatedAt", value.updatedAt).put("retryAt", value.retryAt).put("recordingId", value.recordingId).toString()
    fun genres(raw: String?): CatalogueGenre? = try {
        val obj = JSONObject(raw ?: "")
        val rows = obj.getJSONArray("labels")
        CatalogueGenre(obj.getString("mid"), (0 until rows.length()).map { rows.getString(it) }.toSet(),
            obj.getString("source"), obj.getString("evidence"), obj.getLong("updatedAt"), obj.getLong("retryAt"), obj.optString("recordingId"))
    } catch (_: Exception) { null }
    fun normalizeGenre(value: String): String = when (val key = value.trim().lowercase(Locale.ROOT)) {
        "hip-hop", "hiphop" -> "hip hop"
        "rhythm and blues", "rnb" -> "r&b"
        else -> key
    }
}

/** Account-scoped favourites and history; no cookies or playback URLs ever enter this store. */
object CatalogueStore {
    fun scope(userId: String): String = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray())
        .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun library(context: Context, scope: String) = context.getSharedPreferences("qq_library_$scope", Context.MODE_PRIVATE)
    fun songs(context: Context, scope: String): List<QqCatalogueTrack> {
        val rows = try { JSONArray(library(context, scope).getString("favorites", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(CatalogueCodec::track) }
    }
    fun save(context: Context, scope: String, songs: List<QqCatalogueTrack>, complete: Boolean) {
        val editor = library(context, scope).edit().putString("favorites", JSONArray(songs.map(CatalogueCodec::track)).toString())
        if (complete) editor.putLong("syncedAt", System.currentTimeMillis())
        editor.apply()
    }
    fun syncedAt(context: Context, scope: String) = library(context, scope).getLong("syncedAt", 0)
    fun genre(context: Context, mid: String) = CatalogueCodec.genres(context.getSharedPreferences("qq_catalogue_genres", Context.MODE_PRIVATE).getString(mid, null))
    fun saveGenre(context: Context, value: CatalogueGenre) {
        context.getSharedPreferences("qq_catalogue_genres", Context.MODE_PRIVATE).edit().putString(value.mid, CatalogueCodec.genres(value)).apply()
    }
    fun events(context: Context, scope: String): JSONArray = try {
        JSONArray(library(context, scope).getString("playerEvents", "[]"))
    } catch (_: Exception) { JSONArray() }
    fun record(context: Context, scope: String, mid: String, playedMs: Long, durationMs: Long, category: String, reason: String) {
        val rows = events(context, scope)
        rows.put(JSONObject().put("mediaId", "qq:$mid").put("playedMs", playedMs).put("durationMs", durationMs)
            .put("category", category).put("reason", reason).put("timestamp", System.currentTimeMillis()).put("provider", "qq"))
        val retained = JSONArray()
        for (i in maxOf(0, rows.length() - 500) until rows.length()) retained.put(rows.get(i))
        library(context, scope).edit().putString("playerEvents", retained.toString()).apply()
    }
    fun artwork(song: QqCatalogueTrack): String? = song.albumMid.takeIf { it.matches(Regex("[A-Za-z0-9]{1,80}")) }
        ?.let { "https://y.qq.com/music/photo_new/T002R300x300M000$it.jpg" }
}

class CataloguePageAccumulator {
    private val seenPages = mutableSetOf<List<String>>()
    private val songs = linkedMapOf<String, QqCatalogueTrack>()
    val tracks: List<QqCatalogueTrack> get() = songs.values.toList()
    fun append(page: QqTrackPage) {
        val signature = page.tracks.map { it.mid }
        if (signature.isNotEmpty() && !seenPages.add(signature)) throw QqConnectionException("曲库接口重复返回同一页，已保留读取结果，稍后可重试。")
        page.tracks.forEach { songs[it.mid] = it }
    }
}
