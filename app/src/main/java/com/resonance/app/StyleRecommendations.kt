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
    val rejectedGenres: Set<String> = emptySet(),val directionKind:String="GENRE"
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
            intent.accepts(it.genres) && it.recordingId != anchor.recordingId && (intent.wanted.isNotEmpty() || it.genres.intersect(anchor.genres).isNotEmpty() ||
                (it.mediaId in relatedIds && (it.genres.isEmpty() || anchor.genres.isEmpty())))
        }
        val scores=GenrePreferenceScores.calculate(tracks,scoreEvents,manualScores).associateBy {it.label}
        val ranked = candidates.sortedWith(compareByDescending<StyleTrack> {
            val overlap = it.genres.intersect(anchor.genres).size.toDouble()
            val union = it.genres.union(anchor.genres).size
            (if (union == 0) 0.0 else 10 * overlap / union) +
                (if (it.mediaId in relatedIds) 2.0 else 0.0) + GenrePreferenceScores.preference(it,events,scores)
        }.thenBy { it.mediaId })
        val heading = if(intent.wanted.isNotEmpty())"按你的选歌条件" else if (anchor.genres.isEmpty()) "与「${anchor.name}」相关的歌" else "接着「${anchor.name}」听"
        val explanation = if(intent.active)"依据全部已知标签检查本次条件，排除项优先；未知曲风不用于证明满足条件。" else if (anchor.genres.isEmpty()) "当前歌曲曲风未知，使用 QQ 返回的直接相关歌曲；没有推断曲风或情绪。"
            else "锚点曲风：${anchor.genres.joinToString()}。优先曲风重合；缺标签时仅允许 QQ 直接相关候选，不将关系当成曲风。"
        return plan(heading, explanation, ranked, events, familiarIds = familiarIds) { track ->
            val common = track.genres.intersect(anchor.genres)
            if(intent.wanted.isNotEmpty())"符合想听标签：${track.genres.intersect(intent.wanted).joinToString()}" else
                if (common.isNotEmpty()) "共同曲风：${common.joinToString()}" else "QQ 相关歌曲 · 曲风待补齐"
        }
    }

    fun changeDirection(currentId: String, tracks: List<StyleTrack>, events: List<ListeningSignal>,
                        familiarIds: Set<String> = events.map { it.mediaId }.toSet(), intent:ListeningIntent=ListeningIntent(),scoreEvents:List<ListeningSignal> = events,manualScores:Map<String,Double> = emptyMap()): StylePlan {
        val rejected = events.takeLast(4)
        if (rejected.size != 4 || rejected.any { it.category != "fast" })
            return StylePlan("换方向", "需要连续四次有效快速跳过。")
        val rejectedTracks = rejected.map { event -> tracks.firstOrNull { it.mediaId == event.mediaId && it.genres.isNotEmpty() } }
        if (rejectedTracks.any { it == null })
            return StylePlan("四次快跳：等待曲风", "刚跳过的四首中有 ${rejectedTracks.count { it == null }} 首缺少已核对曲风，暂不猜测你想避开的风格。补齐后可重新分析。")
        val common = rejectedTracks.filterNotNull().flatMap { it.genres }.groupingBy { it }.eachCount()
            .filterValues { it >= 3 }.keys.sortedWith(compareBy<String> { tag -> tracks.count {tag in it.genres} }.thenBy {it}).take(1).toSet()
        if (common.isEmpty())
            return StylePlan("四次快跳：风格混杂", "这四首没有足够共同曲风，无法据此认定你不想听哪种风格；可改用当前歌曲的同曲风推荐。")
        val excluded = rejected.map { it.mediaId }.toSet() + currentId
        val rejectedRecordings = rejectedTracks.filterNotNull().map { it.recordingId }.toSet()
        val scores=GenrePreferenceScores.calculate(tracks,scoreEvents,manualScores).associateBy {it.label}
        val ranked = eligible(tracks, events, excluded).filter {
            intent.accepts(it.genres) && it.genres.intersect(common).isEmpty() && it.recordingId !in rejectedRecordings
        }.sortedWith(compareByDescending<StyleTrack> { GenrePreferenceScores.preference(it,events,scores) }.thenBy { it.mediaId })
        return plan("四次快跳：尝试其他曲风", "本次暂时避开：${common.joinToString()}（四首中至少三首共有，优先选择在曲库中更有区分度的标签）。不永久屏蔽这些曲风。",
            ranked, events, common, familiarIds) { "候选曲风：${it.genres.joinToString()}；与本次避开标签无交集" }
    }

    private fun eligible(tracks: List<StyleTrack>, events: List<ListeningSignal>, excluded: Set<String>, relatedIds: Set<String> = emptySet()): List<StyleTrack> {
        val recentRejected = events.takeLast(10).filter { it.category != "completed" }.map { it.mediaId }.toSet()
        return tracks.filter { (it.genres.isNotEmpty() || it.mediaId in relatedIds) && it.mediaId !in excluded && it.mediaId !in recentRejected }
            .distinctBy { it.recordingId }
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
