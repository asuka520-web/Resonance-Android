package com.resonance.app

import org.json.JSONArray
import kotlin.math.roundToInt

data class StyleTrack(
    val mediaId: String, val name: String, val artist: String,
    val recordingId: String, val genres: Set<String>
)
data class ListeningSignal(val mediaId: String, val category: String)
data class StylePick(val track: StyleTrack, val reason: String, val unfamiliar: Boolean, val surprise:Boolean=false)
data class StylePlan(
    val heading: String, val explanation: String, val picks: List<StylePick> = emptyList(),
    val rejectedGenres: Set<String> = emptySet(),val directionKind:String="GENRE",
    val directionExcludedIds: Set<String> = emptySet()
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
                familiarIds: Set<String> = events.map { it.mediaId }.toSet(), relatedIds: Set<String> = emptySet(), intent:ListeningIntent=ListeningIntent(),scoreEvents:List<ListeningSignal> = events,manualScores:Map<String,Double> = emptyMap()): StylePlan {
        val anchor = tracks.firstOrNull { it.mediaId == anchorId && (it.genres.isNotEmpty() || relatedIds.isNotEmpty() || intent.wanted.isNotEmpty()) }
            ?: return StylePlan("正在了解这首歌", "当前歌曲的曲风资料尚未就绪，自动补齐后即可推荐。")
        val candidates = eligible(tracks, events, setOf(anchorId), relatedIds).filter {
            intent.accepts(it.genres) && it.recordingId != anchor.recordingId && (intent.wanted.isNotEmpty() || GenreEvidence.affinity(it.genres,anchor.genres)>0 ||
                (it.mediaId in relatedIds && (it.genres.isEmpty() || anchor.genres.isEmpty())))
        }
        val scores=GenrePreferenceScores.calculate(tracks,scoreEvents,manualScores).associateBy {it.label}
        val ranked = candidates.sortedWith(compareByDescending<StyleTrack> {
            10 * GenreEvidence.affinity(it.genres,anchor.genres) +
                (if (it.mediaId in relatedIds) 2.0 else 0.0) + GenrePreferenceScores.preference(it,events,scores)
        }.thenBy { it.mediaId }).distinctBy {it.recordingId}
        val heading = if(intent.wanted.isNotEmpty())"按你的选歌条件" else if (anchor.genres.isEmpty()) "与「${anchor.name}」相关的歌" else "接着「${anchor.name}」听"
        val explanation = if(intent.active)"依据全部已知标签检查本次条件，排除项优先；未知曲风不用于证明满足条件。" else if (anchor.genres.isEmpty()) "当前歌曲曲风未知，使用 QQ 返回的直接相关歌曲；没有推断曲风或情绪。"
            else "锚点曲风：${anchor.genres.joinToString()}。优先相同曲风，其次参考已知的细分曲风关系。"
        return plan(heading, explanation, ranked, events, familiarIds = familiarIds) { track ->
            val common = track.genres.intersect(anchor.genres)
            if(intent.wanted.isNotEmpty())"符合想听标签：${track.genres.intersect(intent.wanted).joinToString()}" else
                if (common.isNotEmpty()) "共同曲风：${common.joinToString()}"
                else if (GenreEvidence.affinity(track.genres,anchor.genres)>0) "曲风关联：${track.genres.joinToString()}"
                else "QQ 相关歌曲 · 曲风待补齐"
        }
    }

    fun changeDirection(currentId: String, tracks: List<StyleTrack>, events: List<ListeningSignal>,
                        familiarIds: Set<String> = events.map { it.mediaId }.toSet(), intent:ListeningIntent=ListeningIntent(),
                        scoreEvents:List<ListeningSignal> = events,manualScores:Map<String,Double> = emptyMap(),
                        allowPartialEvidence: Boolean = false, excludedIds: Set<String> = emptySet(),
                        candidateIds: Set<String>? = null): StylePlan {
        val rejected = events.takeLast(4)
        if (rejected.size != 4 || rejected.any { it.category != "fast" })
            return StylePlan("换方向", "需要连续四次有效快速跳过。")
        val tracksById = tracks.associateBy {it.mediaId}
        val rejectedTracks = rejected.mapNotNull {tracksById[it.mediaId]}.distinctBy {it.recordingId}
        val known = rejectedTracks.filter {GenreEvidence.comparable(it.genres).isNotEmpty()}
        if (known.size < 2 || (!allowPartialEvidence && known.size != 4))
            return StylePlan("四次快跳：资料不足", "四次快跳中有 ${known.size} 首不同录音具备可比较的曲风。")
        val required = if (known.size == 2) 2 else 3
        val coverage = tracks.flatMap {GenreEvidence.withFamilies(it.genres)}.groupingBy {it}.eachCount()
        val common = known.flatMap {GenreEvidence.withFamilies(it.genres)}.groupingBy {it}.eachCount()
            .filterValues {it >= required}.keys
            .minWithOrNull(compareBy<String> {coverage[it] ?: Int.MAX_VALUE}.thenBy {it})
            ?.let(::setOf).orEmpty()
        if (common.isEmpty())
            return StylePlan("四次快跳：风格混杂", "这组快跳没有足够一致的曲风信号。")
        val excluded = rejected.map {it.mediaId}.toSet() + currentId + excludedIds
        val rejectedRecordings = excluded.mapNotNull {tracksById[it]?.recordingId}.toSet()
        val scores=GenrePreferenceScores.calculate(tracks,scoreEvents,manualScores).associateBy {it.label}
        val ranked = eligible(tracks, events, excluded).filter {
            (candidateIds == null || it.mediaId in candidateIds) && intent.accepts(it.genres) &&
                GenreEvidence.comparable(it.genres).isNotEmpty() &&
                !GenreEvidence.overlaps(it.genres,common) && it.recordingId !in rejectedRecordings
        }.sortedWith(compareByDescending<StyleTrack> { GenrePreferenceScores.preference(it,events,scores) }.thenBy { it.mediaId })
            .distinctBy {it.recordingId}
        val tentative = known.size < 4
        val explanation = "本次暂时避开 ${common.joinToString()}。四次快跳中 ${known.size} 首具备可比较曲风，" +
            "${known.count {GenreEvidence.overlaps(it.genres,common)}} 首指向这个方向。" +
            if (tentative) "依据有限，先试试别的曲风。" else ""
        return plan("四次快跳：尝试其他曲风", explanation, ranked, events, common, familiarIds) {
            "候选曲风：${it.genres.joinToString()}"
        }.copy(directionKind = if (tentative) "GENRE_HINT" else "GENRE")
    }

    private fun eligible(tracks: List<StyleTrack>, events: List<ListeningSignal>, excluded: Set<String>, relatedIds: Set<String> = emptySet()): List<StyleTrack> {
        val recentRejected = events.takeLast(10).filter { it.category != "completed" }.map { it.mediaId }.toSet()
        return tracks.filter { (it.genres.isNotEmpty() || it.mediaId in relatedIds) && it.mediaId !in excluded && it.mediaId !in recentRejected }
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
