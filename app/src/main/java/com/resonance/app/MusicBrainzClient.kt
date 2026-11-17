package com.resonance.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

data class RecordingMatch(val id: String, val title: String, val artist: String,
                          val durationMs: Long, val version: String, val releases: String,
                          val primaryArtistNames: Set<String> = setOf(artist),
                          val albumTitles: Set<String> = emptySet(), val video: Boolean = false)
data class RecordingSearchResult(val recordings: List<RecordingMatch>, val complete: Boolean)

object MusicBrainzJson {
    fun search(raw: String): List<RecordingMatch> {
        val rows = JSONObject(raw).optJSONArray("recordings") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id")
            if (!validId(id)) return@mapNotNull null
            val credit = row.optJSONArray("artist-credit")
            val artist = if (credit == null) "" else (0 until credit.length()).joinToString("") {
                val item = credit.optJSONObject(it)
                (item?.optString("name") ?: "") + (item?.optString("joinphrase") ?: "")
            }
            val releases = row.optJSONArray("releases")
            val albumNames = if (releases == null) emptySet() else (0 until releases.length()).mapNotNull {
                releases.optJSONObject(it)?.optString("title")?.takeIf(String::isNotBlank)
            }.toSet()
            val firstCredit = credit?.optJSONObject(0)
            val primaryNames = setOfNotNull(firstCredit?.optString("name"),
                firstCredit?.optJSONObject("artist")?.optString("name")).filter(String::isNotBlank).toSet()
            RecordingMatch(id, row.optString("title"), artist, row.optLong("length"),
                row.optString("disambiguation"), albumNames.take(3).joinToString(" / "),
                primaryNames, albumNames, row.optBoolean("video"))
        }
    }
    fun results(raw: String): RecordingSearchResult {
        val obj = JSONObject(raw)
        val recordings = search(raw)
        return RecordingSearchResult(recordings, obj.optLong("count", Long.MAX_VALUE) <= recordings.size &&
            obj.optInt("offset", 0) == 0)
    }
    fun genres(raw: String, expectedId: String): Set<String> {
        val obj = JSONObject(raw)
        require(obj.optString("id") == expectedId) { "查询结果的录音 ID 不匹配" }
        val rows = obj.optJSONArray("genres") ?: return emptySet()
        return (0 until rows.length()).mapNotNull {
            rows.optJSONObject(it)?.takeIf { row -> row.optInt("count") > 0 }
                ?.optString("name")?.trim()?.takeIf(String::isNotBlank)
        }.toSet()
    }
    fun validId(id: String) = id.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
    fun query(name: String, artist: String): String {
        return "recording:${quote(name)} AND artist:${quote(artist)}"
    }
    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    fun automaticQuery(song: SongInfo, durationMs: Long): String = query(song.name, song.artist) +
        (if (song.album.isNotBlank()) " AND release:${quote(song.album)}" else "") +
        " AND dur:[${(durationMs - 3000).coerceAtLeast(0)} TO ${durationMs + 3000}] AND video:false"
}

/** Manual queries or an explicitly enabled automatic queue; never sends history, IDs or lyrics. */
object MusicBrainzClient {
    private var lastRequestAt: Long? = null
    suspend fun search(song: SongInfo): List<RecordingMatch> = withContext(Dispatchers.IO) {
        require(song.name.isNotBlank() && song.artist.isNotBlank()) { "请先核实歌名与歌手" }
        val query = URLEncoder.encode(MusicBrainzJson.query(song.name, song.artist), "UTF-8")
        MusicBrainzJson.search(request("recording/?query=$query&fmt=json&limit=5"))
    }
    suspend fun searchAutomatic(song: SongInfo, durationMs: Long): RecordingSearchResult = withContext(Dispatchers.IO) {
        require(song.name.isNotBlank() && song.artist.isNotBlank() && durationMs > 0)
        val query = URLEncoder.encode(MusicBrainzJson.automaticQuery(song, durationMs), "UTF-8")
        MusicBrainzJson.results(request("recording/?query=$query&fmt=json&limit=25"))
    }
    suspend fun genres(id: String): Set<String> = withContext(Dispatchers.IO) {
        require(MusicBrainzJson.validId(id))
        MusicBrainzJson.genres(request("recording/$id?inc=genres&fmt=json"), id)
    }

    @Synchronized private fun request(path: String): String {
        // Start-to-start spacing and one in-flight request satisfy MusicBrainz's public API limit.
        val waitMs = lastRequestAt?.let { 1100L - (System.nanoTime() / 1_000_000L - it) } ?: 0L
        if (waitMs > 0) Thread.sleep(waitMs)
        lastRequestAt = System.nanoTime() / 1_000_000L
        val connection = URI("https://musicbrainz.org/ws/2/$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Resonance/0.14.0 (https://github.com/asuka520-web/Resonance-Android)")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != 200) throw IOException("曲库服务返回 ${connection.responseCode}，请稍后再试")
            return connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1_048_576) throw IOException("查询结果过大，已停止读取")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }
}
