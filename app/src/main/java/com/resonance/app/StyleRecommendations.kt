package com.resonance.app

import org.json.JSONArray
import kotlin.math.roundToInt

data class StyleTrack(
    val mediaId: String, val name: String, val artist: String,
    val recordingId: String, val genres: Set<String>
)
data class ListeningSignal(val mediaId: String, val category: String)
data class StylePick(val track: StyleTrack, val reason: String, val unfamiliar: Boolean)
data class StylePlan(
    val heading: String, val explanation: String, val picks: List<StylePick> = emptyList(),
    val rejectedGenres: Set<String> = emptySet()
)

/** Genre overlap is an explainable first stage, not acoustic or mood similarity. */
object StyleRecommendations {
    fun signals(json: JSONArray): List<ListeningSignal> = (0 until json.length()).mapNotNull {
        val event = json.optJSONObject(it) ?: return@mapNotNull null
        val id = event.optString("mediaId")
        val category = event.optString("category")
        if (id.isBlank() || category !in setOf("fast", "partial", "completed")) null
        else ListeningSignal(id, category)
    }

    fun similar(anchorId: String, tracks: List<StyleTrack>, events: List<ListeningSignal>,
                familiarIds: Set<String> = events.map { it.mediaId }.toSet()): StylePlan {
        val anchor = tracks.firstOrNull { it.mediaId == anchorId && it.genres.isNotEmpty() }
            ?: return StylePlan("正在了解这首歌", "当前歌曲的曲风资料尚未就绪，自动补齐后即可推荐。")
        val candidates = eligible(tracks, events, setOf(anchorId)).filter {
            it.recordingId != anchor.recordingId && it.genres.intersect(anchor.genres).isNotEmpty()
        }
        val ranked = candidates.sortedWith(compareByDescending<StyleTrack> {
            val overlap = it.genres.intersect(anchor.genres).size.toDouble()
            10 * overlap / it.genres.union(anchor.genres).size + preference(it, tracks, events)
        }.thenBy { it.mediaId })
        return plan("与「${anchor.name}」同曲风", "锚点曲风：${anchor.genres.joinToString()}。按曲风重合与听歌反馈排序；尚不包含声音、情绪或节奏识别。",
            ranked, events, familiarIds = familiarIds) { track -> "共同曲风：${track.genres.intersect(anchor.genres).joinToString()}" }
    }

    fun changeDirection(currentId: String, tracks: List<StyleTrack>, events: List<ListeningSignal>,
                        familiarIds: Set<String> = events.map { it.mediaId }.toSet()): StylePlan {
        val rejected = events.takeLast(4)
        if (rejected.size != 4 || rejected.any { it.category != "fast" })
            return StylePlan("换方向", "需要连续四次有效快速跳过。")
        val rejectedTracks = rejected.map { event -> tracks.firstOrNull { it.mediaId == event.mediaId && it.genres.isNotEmpty() } }
        if (rejectedTracks.any { it == null })
            return StylePlan("四次快跳：等待曲风", "刚跳过的四首中有 ${rejectedTracks.count { it == null }} 首缺少已核对曲风，暂不猜测你想避开的风格。补齐后可重新分析。")
        val common = rejectedTracks.filterNotNull().flatMap { it.genres }.groupingBy { it }.eachCount()
            .filterValues { it >= 3 }.keys
        if (common.isEmpty())
            return StylePlan("四次快跳：风格混杂", "这四首没有足够共同曲风，无法据此认定你不想听哪种风格；可改用当前歌曲的同曲风推荐。")
        val excluded = rejected.map { it.mediaId }.toSet() + currentId
        val rejectedRecordings = rejectedTracks.filterNotNull().map { it.recordingId }.toSet()
        val ranked = eligible(tracks, events, excluded).filter {
            it.genres.intersect(common).isEmpty() && it.recordingId !in rejectedRecordings
        }.sortedWith(compareByDescending<StyleTrack> { preference(it, tracks, events) }.thenBy { it.mediaId })
        return plan("四次快跳：尝试其他曲风", "本次暂时避开：${common.joinToString()}（四首中至少三首共有）。不永久屏蔽这些曲风。",
            ranked, events, common, familiarIds) { "候选曲风：${it.genres.joinToString()}；与本次避开标签无交集" }
    }

    private fun eligible(tracks: List<StyleTrack>, events: List<ListeningSignal>, excluded: Set<String>): List<StyleTrack> {
        val recentRejected = events.takeLast(10).filter { it.category != "completed" }.map { it.mediaId }.toSet()
        return tracks.filter { it.genres.isNotEmpty() && it.mediaId !in excluded && it.mediaId !in recentRejected }
            .distinctBy { it.recordingId }
    }

    private fun preference(track: StyleTrack, tracks: List<StyleTrack>, events: List<ListeningSignal>): Double {
        val byId = tracks.associateBy { it.mediaId }
        val longLikes = events.takeLast(500).filter { it.category == "completed" }
        val recentLikes = events.takeLast(20).filter { it.category == "completed" }
        fun matching(event: ListeningSignal) = byId[event.mediaId]?.genres?.intersect(track.genres)?.isNotEmpty() == true
        val repeatPenalty = if (events.takeLast(3).any { it.mediaId == track.mediaId }) 3.0 else 0.0
        return longLikes.count(::matching) * 0.1 + recentLikes.count(::matching) * 0.5 - repeatPenalty
    }

    private fun plan(heading: String, explanation: String, ranked: List<StyleTrack>, events: List<ListeningSignal>,
                     rejected: Set<String> = emptySet(), familiarIds: Set<String> = events.map { it.mediaId }.toSet(),
                     reason: (StyleTrack) -> String): StylePlan {
        if (ranked.isEmpty()) return StylePlan(heading,
            "$explanation\n当前曲库暂没有符合条件的其他歌曲，资料补齐后再试。", rejectedGenres = rejected)
        val known = familiarIds + events.map { it.mediaId }
        val familiar = ranked.filter { it.mediaId in known }
        val exploration = ranked.filter { it.mediaId !in known }
        val size = minOf(10, ranked.size)
        val targetExplore = (size * 0.3).roundToInt()
        val selected = (familiar.take(size - targetExplore) + exploration.take(targetExplore)).toMutableList()
        ranked.filter { it !in selected }.take(size - selected.size).forEach { selected.add(it) }
        val actualExplore = selected.count { it.mediaId !in known }
        val mix = "本批 ${selected.size} 首：熟悉候选 ${selected.size - actualExplore} 首、尚无记录 $actualExplore 首。目标 70% / 30%，按可用候选补足；无记录不代表你从未听过。"
        return StylePlan(heading, "$explanation\n$mix", selected.map { StylePick(it, reason(it), it.mediaId !in known) }, rejected)
    }
}
