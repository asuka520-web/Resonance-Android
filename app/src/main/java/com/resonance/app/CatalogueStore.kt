package com.resonance.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

enum class GenreLookupStatus { READY, WAITING, RETRY, UNAVAILABLE }

data class CatalogueGenre(val mid: String, val labels: Set<String>, val source: String, val evidence: String,
                          val updatedAt: Long, val retryAt: Long, val recordingId: String = "",
                          val status: GenreLookupStatus = if (labels.isNotEmpty()) GenreLookupStatus.READY
                          else if (source == "pending") GenreLookupStatus.RETRY else GenreLookupStatus.UNAVAILABLE,
                          val sourceLabels:Map<String,Set<String>> = emptyMap(),val enrichmentState:String="",
                          val enrichRetryAt:Long=0,val enrichmentNote:String="",val metadataKey:String="",val descriptors:Set<String> = emptySet()) {
    fun providers()=if(sourceLabels.isNotEmpty())sourceLabels else if(labels.isNotEmpty())mapOf(source to labels) else emptyMap()
}

object GenreLookupPolicy {
    fun preserveKnown(previous:CatalogueGenre?,fresh:CatalogueGenre):CatalogueGenre {
        if(previous?.mid!=fresh.mid)return fresh
        if(previous.metadataKey.isNotBlank() && fresh.metadataKey.isNotBlank() && previous.metadataKey!=fresh.metadataKey)return fresh
        if(previous.labels.isNotEmpty() && fresh.labels.isEmpty())return previous.copy(retryAt=fresh.retryAt,descriptors=previous.descriptors+fresh.descriptors,
            recordingId=fresh.recordingId.ifBlank {previous.recordingId})
        if(previous.labels.isNotEmpty() && fresh.labels.isNotEmpty()) {
            val sources=previous.providers()+fresh.providers()
            return fresh.copy(labels=sources.values.flatten().toSet(),source=sources.keys.sorted().joinToString("+"),sourceLabels=sources,
                recordingId=fresh.recordingId.ifBlank {previous.recordingId},enrichmentState=previous.enrichmentState,
                enrichRetryAt=previous.enrichRetryAt,enrichmentNote=previous.enrichmentNote,descriptors=previous.descriptors+fresh.descriptors)
        }
        return fresh
    }
    fun status(genre: CatalogueGenre?): GenreLookupStatus = when {
        genre == null -> GenreLookupStatus.WAITING
        genre.labels.isNotEmpty() -> GenreLookupStatus.READY
        else -> genre.status
    }
    fun retry(genre: CatalogueGenre): CatalogueGenre = if (genre.labels.isNotEmpty()) genre
        else genre.copy(retryAt = 0, status = GenreLookupStatus.WAITING)
    fun description(genre: CatalogueGenre?): String = when (status(genre)) {
        GenreLookupStatus.READY -> "曲风已取得"
        GenreLookupStatus.WAITING -> "等待查询曲风"
        GenreLookupStatus.RETRY -> "查询失败 · 可重试"
        GenreLookupStatus.UNAVAILABLE -> "来源暂无曲风资料"
    }
}

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
        .put("updatedAt", value.updatedAt).put("retryAt", value.retryAt).put("recordingId", value.recordingId)
        .put("status", value.status.name).put("sourceLabels",JSONObject(value.sourceLabels.mapValues {JSONArray(it.value.sorted())}))
        .put("enrichmentState",value.enrichmentState).put("enrichRetryAt",value.enrichRetryAt)
        .put("descriptors",JSONArray(value.descriptors.sorted()))
        .put("enrichmentNote",value.enrichmentNote).put("metadataKey",value.metadataKey).toString()
    fun genres(raw: String?): CatalogueGenre? = try {
        val obj = JSONObject(raw ?: "")
        val rows = obj.getJSONArray("labels")
        val legacy = CatalogueGenre(obj.getString("mid"), (0 until rows.length()).map { normalizeGenre(rows.getString(it)) }.toSet(),
            obj.getString("source"), obj.getString("evidence"), obj.getLong("updatedAt"), obj.getLong("retryAt"), obj.optString("recordingId"))
        val providers=obj.optJSONObject("sourceLabels")
        val sourceLabels=providers?.keys()?.asSequence()?.associateWith {key ->
            val values=providers.optJSONArray(key)?:JSONArray();(0 until values.length()).map {normalizeGenre(values.getString(it))}.toSet()}.orEmpty()
        val descriptors=obj.optJSONArray("descriptors")?:JSONArray()
        legacy.copy(status = runCatching { GenreLookupStatus.valueOf(obj.getString("status")) }.getOrDefault(legacy.status),
            sourceLabels=sourceLabels,enrichmentState=obj.optString("enrichmentState"),enrichRetryAt=obj.optLong("enrichRetryAt"),
            enrichmentNote=obj.optString("enrichmentNote"),metadataKey=obj.optString("metadataKey"),
            descriptors=(0 until descriptors.length()).map {descriptors.getString(it)}.filter {it in RecordingDescriptors.allowed}.toSet())
    } catch (_: Exception) { null }
    fun normalizeGenre(value: String): String = when (val key = value.trim().lowercase(Locale.ROOT)) {
        "hip-hop", "hiphop", "rap/hip hop", "rap / hip hop", "hip hop/rap", "hip-hop/rap" -> "hip hop"
        "rhythm and blues", "rnb", "r & b" -> "r&b"
        "流行"->"pop";"摇滚"->"rock";"民谣"->"folk";"爵士"->"jazz";"电子"->"electronic";"古典"->"classical"
        "乡村"->"country";"灵魂乐"->"soul";"金属"->"metal";"说唱", "嘻哈"->"hip hop";"节奏布鲁斯"->"r&b"
        else -> key
    }
    fun identity(song:QqCatalogueTrack)=MessageDigest.getInstance("SHA-256").digest(
        JSONArray(listOf(song.name,song.artist,song.album,song.durationMs)).toString().toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
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
    fun directionHistory(context:Context,scope:String)=DirectionSwitchCodec.decode(library(context,scope).getString("directionHistory",null))
    fun saveDirectionHistory(context:Context,scope:String,rows:List<DirectionSwitch>) {library(context,scope).edit().putString("directionHistory",DirectionSwitchCodec.encode(rows)).apply()}
    fun discovery(context: Context, scope: String) = DiscoveryPool.decode(library(context, scope).getString("discovery", null))
    fun saveDiscovery(context: Context, scope: String, pool: DiscoveryPool) {
        library(context, scope).edit().putString("discovery", pool.encode()).apply()
    }
    fun genre(context: Context, mid: String) = CatalogueCodec.genres(context.getSharedPreferences("qq_catalogue_genres", Context.MODE_PRIVATE).getString(mid, null))
    fun saveGenre(context: Context, value: CatalogueGenre) {
        context.getSharedPreferences("qq_catalogue_genres", Context.MODE_PRIVATE).edit().putString(value.mid, CatalogueCodec.genres(value)).apply()
    }
    fun events(context: Context, scope: String): JSONArray = try {
        JSONArray(library(context, scope).getString("playerEvents", "[]"))
    } catch (_: Exception) { JSONArray() }
    fun scoreEvents(context:Context,scope:String):JSONArray = try {
        val prefs=library(context,scope)
        JSONArray(prefs.getString("scoreEvents",null)?:prefs.getString("playerEvents","[]"))
    }catch(_:Exception){JSONArray()}
    fun scoreEpoch(context:Context,scope:String)=library(context,scope).getLong("scoreEpoch",0)
    fun manualScores(context:Context,scope:String):Map<String,Double> = try {
        val json=JSONObject(library(context,scope).getString("manualScores","{}")?:"{}")
        val scores = mutableMapOf<String, Double>()
        for (key in json.keys()) {
            val value = json.optDouble(key)
            val label = CatalogueCodec.normalizeGenre(key)
            if (value.isFinite() && label.isNotBlank()) scores[label] = (scores[label] ?: 0.0) + value.coerceIn(-6.0,6.0)
        }
        scores.mapValues {it.value.coerceIn(-6.0,6.0)}
    }catch(_:Exception){emptyMap()}
    fun saveManualScores(context:Context,scope:String,scores:Map<String,Double>) {
        library(context,scope).edit().putString("manualScores",JSONObject(scores).toString()).apply()
    }
    fun clearScores(context:Context,scope:String):Long {
        val epoch=scoreEpoch(context,scope)+1
        library(context,scope).edit().putString("scoreEvents","[]").putString("manualScores","{}").putLong("scoreEpoch",epoch).apply()
        return epoch
    }
    fun record(context: Context, scope: String, mid: String, playedMs: Long, durationMs: Long, category: String, reason: String) {
        val rows = events(context, scope)
        val scoring=scoreEvents(context,scope)
        val event=JSONObject().put("mediaId", "qq:$mid").put("playedMs", playedMs).put("durationMs", durationMs)
            .put("category", category).put("reason", reason).put("timestamp", System.currentTimeMillis()).put("provider", "qq")
        rows.put(event);scoring.put(event)
        val retained = JSONArray()
        for (i in maxOf(0, rows.length() - 500) until rows.length()) retained.put(rows.get(i))
        val retainedScores=JSONArray()
        for(i in maxOf(0,scoring.length()-500) until scoring.length())retainedScores.put(scoring.get(i))
        library(context, scope).edit().putString("playerEvents", retained.toString()).putString("scoreEvents",retainedScores.toString()).apply()
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
