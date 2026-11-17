package com.resonance.app

import org.json.JSONArray
import org.json.JSONObject

data class DiscoveryBatch(val seedMid: String, val fetchedAt: Long, val tracks: List<QqCatalogueTrack>)

/** Exact QQ relationships, stored per account. Neither inferred genres nor playback URLs. */
data class DiscoveryPool(val batches: List<DiscoveryBatch> = emptyList()) {
    val tracks get() = batches.flatMap { it.tracks }.distinctBy { it.mid }
    fun related(mid: String) = batches.firstOrNull { it.seedMid == mid }?.tracks?.map { it.mid }?.toSet().orEmpty()
    fun fresh(mid: String, now: Long) = batches.firstOrNull { it.seedMid == mid }
        ?.let { now - it.fetchedAt in 0 until 24 * 60 * 60 * 1000L } == true
    fun add(batch: DiscoveryBatch) = DiscoveryPool((listOf(batch.copy(tracks = batch.tracks
        .filter { it.mid != batch.seedMid }.distinctBy { it.mid }.take(30))) + batches.filter { it.seedMid != batch.seedMid }).take(24))
    fun encode(): String = JSONArray(batches.map { batch -> JSONObject().put("seedMid", batch.seedMid)
        .put("fetchedAt", batch.fetchedAt).put("tracks", JSONArray(batch.tracks.map(CatalogueCodec::track))) }).toString()
    companion object {
        fun decode(raw: String?): DiscoveryPool = try {
            val rows = JSONArray(raw ?: "[]")
            val batches = (0 until rows.length().coerceAtMost(24)).mapNotNull { index ->
                runCatching {
                    val obj = rows.getJSONObject(index)
                    val mid = obj.getString("seedMid")
                    require(mid.matches(Regex("[A-Za-z0-9]{1,80}")))
                    val songs = obj.getJSONArray("tracks")
                    DiscoveryBatch(mid, obj.getLong("fetchedAt"), (0 until songs.length().coerceAtMost(30))
                        .mapNotNull { songs.optJSONObject(it)?.let(CatalogueCodec::track) }.filter { it.mid != mid }.distinctBy { it.mid })
                }.getOrNull()
            }
            DiscoveryPool(batches.distinctBy { it.seedMid })
        } catch (_: Exception) { DiscoveryPool() }
    }
}

object DiscoverySeedPolicy {
    /** Spread seed requests across known genres and recent positive listening; never equate artist and genre. */
    fun select(tracks: List<QqCatalogueTrack>, genres: Map<String, CatalogueGenre>, events: List<ListeningSignal>, limit: Int = 6): List<QqCatalogueTrack> {
        val rejected = events.takeLast(10).filter { it.category != "completed" }.map { it.mediaId.removePrefix("qq:") }.toSet()
        val liked = events.takeLast(20).filter { it.category == "completed" }.map { it.mediaId.removePrefix("qq:") }.toSet()
        val remaining = tracks.filter { it.songId.matches(Regex("[0-9]{1,15}")) &&
            (it.songId.toLongOrNull() ?: 0) > 0 && it.mid !in rejected }.distinctBy { it.mid }.toMutableList()
        val chosen = mutableListOf<QqCatalogueTrack>()
        val covered = mutableSetOf<String>()
        repeat(limit.coerceAtLeast(0)) {
            val next = remaining.maxByOrNull { song ->
                genres[song.mid]?.labels.orEmpty().count { it !in covered } * 10 + if (song.mid in liked) 2 else 0
            } ?: return chosen
            chosen.add(next); remaining.remove(next); covered.addAll(genres[next.mid]?.labels.orEmpty())
        }
        return chosen
    }
}
