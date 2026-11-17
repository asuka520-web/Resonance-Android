package com.resonance.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class TrackFeatures(
    val mediaId: String, val name: String, val artist: String, val recordingId: String,
    val genres: Set<String>, val savedAt: Long, val album: String = ""
)

object TrackFeatureCodec {
    private fun identity(value: String) = value.trim().lowercase(Locale.ROOT)
    fun matches(feature: TrackFeatures, song: SongInfo): Boolean = feature.mediaId == song.mediaId &&
        identity(feature.name) == identity(song.name) && identity(feature.artist) == identity(song.artist) &&
        identity(feature.album) == identity(song.album)

    fun encode(value: TrackFeatures): JSONObject = JSONObject().put("mediaId", value.mediaId)
        .put("name", value.name).put("artist", value.artist).put("recordingId", value.recordingId)
        .put("album", value.album)
        .put("genres", JSONArray(value.genres.sorted())).put("savedAt", value.savedAt)
        .put("source", "musicbrainz-recording").put("mapping", "user-confirmed")

    fun decode(raw: String?): TrackFeatures? = try {
        val obj = JSONObject(raw ?: "")
        val genres = obj.getJSONArray("genres")
        if (obj.getString("source") != "musicbrainz-recording" || obj.getString("mapping") != "user-confirmed") null
        else TrackFeatures(obj.getString("mediaId"), obj.getString("name"), obj.getString("artist"),
            obj.getString("recordingId"), (0 until genres.length()).map { genres.getString(it).trim().lowercase(Locale.ROOT) }
                .filter { it.isNotBlank() }.toSet(), obj.getLong("savedAt"), obj.optString("album"))
    } catch (_: Exception) { null }
}

object TrackFeatureStore {
    private const val PREFS = "resonance_track_features"
    fun get(context: Context, song: SongInfo): TrackFeatures? = TrackFeatureCodec.decode(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(song.mediaId, null))
        ?.takeIf { TrackFeatureCodec.matches(it, song) }

    fun save(context: Context, song: SongInfo, recording: RecordingMatch, genres: Set<String>) {
        val features = TrackFeatures(song.mediaId, song.name, song.artist, recording.id, genres, System.currentTimeMillis(), song.album)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(song.mediaId, TrackFeatureCodec.encode(features).toString()).apply()
    }

    fun tracks(context: Context): List<StyleTrack> = songs(context).mapNotNull { song ->
        get(context, song)?.takeIf { it.genres.isNotEmpty() }?.let {
            StyleTrack(song.mediaId, song.name, song.artist, it.recordingId, it.genres)
        }
    }

    fun songs(context: Context): List<SongInfo> {
        val json = SongInfoStore.exportJson(context)
        return json.keys().asSequence().mapNotNull { SongInfoStore.get(context, it) }.toList().sortedBy { it.name }
    }

    fun exportJson(context: Context): JSONObject = JSONObject().apply {
        songs(context).forEach { song -> get(context, song)?.let { put(song.mediaId, TrackFeatureCodec.encode(it)) } }
    }

    fun events(context: Context): List<ListeningSignal> {
        val raw = context.getSharedPreferences("resonance_history", Context.MODE_PRIVATE).getString("events", "[]")
        return StyleRecommendations.signals(try { JSONArray(raw) } catch (_: Exception) { JSONArray() })
    }
}
