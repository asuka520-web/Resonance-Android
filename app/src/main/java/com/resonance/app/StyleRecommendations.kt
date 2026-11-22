package com.resonance.app

import org.json.JSONArray
import kotlin.math.roundToInt

data class StyleTrack(
    val mediaId: String, val name: String, val artist: String,
    val recordingId: String, val genres: Set<String>
)
data class ListeningSignal(val mediaId: String, val category: String, val ignoredScoreLabels: Set<String> = emptySet())
data class StylePick(val track: StyleTrack, val reason: String, val unfamiliar: Boolean, val surprise:Boolean=false)
data class StylePlan(
    val heading: String, val explanation: String, val picks: List<StylePick> = emptyList(),
    val rejectedGenres: Set<String> = emptySet(),val directionKind:String="GENRE",
    val directionExcludedIds: Set<String> = emptySet()
)

/** Genre overlap is an explainable first stage, not acoustic or mood similarity. */
object StyleRecommendations {
    fun signals(json: JSONArray, tagScoreStarts: Map<String, Int> = emptyMap()): List<ListeningSignal> = (0 until json.length()).mapNotNull {
        val event = json.optJSONObject(it) ?: return@mapNotNull null
        val id = event.optString("mediaId")
        val category = event.optString("category")
        if (id.isBlank() || category !in setOf("fast", "partial", "completed")) null
        else ListeningSignal(id, category, if (tagScoreStarts.isEmpty()) emptySet() else buildSet {
            for ((label, start) in tagScoreStarts) if (it < start) add(label)
        })
    }

    fun similar(anchorId: String, tracks: List<StyleTrack>, events: List<ListeningSignal>,
                familiarIds: Set<String> = events.map { it.mediaId }.toSet(), relatedIds: Set<String> = emptySet(), intent:ListeningIntent=ListeningIntent(),scoreEvents:List<ListeningSignal> = events,manualScores:Map<String,Double> = emptyMap()): StylePlan {
        val waiting = StylePlan("正在了解这首歌", "当前歌曲的曲风资料尚未就绪，自动补齐后即可推荐。")
        val anchor = tracks.firstOrNull { it.mediaId == anchorId } ?: return waiting
        val anchorGenres = GenreEvidence.comparable(anchor.genres)
        if (anchorGenres.isEmpty() && relatedIds.isEmpty() && intent.wanted.isEmpty()) return waiting
        val subgenres = GenreEvidence.subgenres(anchorGenres)
        val scores = GenrePreferenceScores.calculate(tracks, scoreEvents, manualScores).associateBy { it.label }
        data class Match(val track: StyleTrack, val subgenre: Double, val genre: Double, val preference: Double)
        val candidates = eligible(tracks, events, setOf(anchorId), relatedIds).mapNotNull { track ->
            val genres = GenreEvidence.comparable(track.genres)
            if (!intent.accepts(genres)) return@mapNotNull null
            val affinity = GenreEvidence.affinity(genres, anchorGenres)
            val related = track.mediaId in relatedIds
            val relatedFallback = related && (genres.isEmpty() || anchorGenres.isEmpty())
            if (intent.wanted.isEmpty() && affinity == 0.0 && !relatedFallback) return@mapNotNull null
            Match(track, GenreEvidence.affinity(genres, subgenres), affinity,
                GenrePreferenceScores.preference(track, events, scores) + if (related) 2.0 else 0.0)
        }
        val ranked = candidates.sortedWith(compareByDescending<Match> { it.subgenre }
            .thenByDescending { it.genre }.thenByDescending { it.preference }.thenBy { it.track.mediaId })
            .map { it.track }.distinctBy { it.recordingId }
        val heading = if(intent.wanted.isNotEmpty())"按你的选歌条件" else if (anchorGenres.isEmpty()) "与「${anchor.name}」相关的歌" else "接着「${anchor.name}」听"
        val explanation = if(intent.active)"依据全部已知标签检查本次条件，排除项优先；未知曲风不用于证明满足条件。" else if (anchorGenres.isEmpty()) "当前歌曲曲风未知，使用 QQ 返回的直接相关歌曲。"
            else "当前歌曲曲风：${anchorGenres.joinToString()}。优先匹配细分曲风，再看其他共同标签和听歌偏好。"
        return plan(heading, explanation, ranked, events, familiarIds = familiarIds) { track ->
            val genres = GenreEvidence.comparable(track.genres)
            val common = genres.intersect(anchorGenres)
            val sharedFamilies = GenreEvidence.withFamilies(genres).intersect(GenreEvidence.withFamilies(subgenres))
            when {
                intent.wanted.isNotEmpty() -> "符合想听标签：${genres.intersect(intent.wanted).joinToString()}"
                common.any { it in subgenres } -> "共同曲风：${common.intersect(subgenres).joinToString()}"
                sharedFamilies.isNotEmpty() -> "细分曲风相关：${sharedFamilies.joinToString()}"
                common.isNotEmpty() -> "共同曲风：${common.joinToString()}"
                GenreEvidence.affinity(genres, anchorGenres) > 0 -> "曲风关联：${genres.joinToString()}"
                else -> "QQ 相关歌曲 · 曲风待补齐"
            }
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
        val commonTag = known.flatMap { GenreEvidence.withFamilies(it.genres) }.groupingBy { it }.eachCount()
            .filterValues { it >= required }.entries
            .minWithOrNull(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { coverage[it.key] ?: Int.MAX_VALUE }.thenBy { it.key })
            ?: return StylePlan("四次快跳：风格混杂", "这组快跳没有足够一致的曲风信号。")
        val common = setOf(commonTag.key)
        val supporting = known.filter { GenreEvidence.overlaps(it.genres, common) }
        val commonFamily = GenreEvidence.withFamilies(common)
        val excluded = rejected.map {it.mediaId}.toSet() + currentId + excludedIds
        val scores = GenrePreferenceScores.calculate(tracks, scoreEvents, manualScores).associateBy { it.label }
        data class Candidate(val track: StyleTrack, val sameFamily: Boolean, val affinity: Double, val preference: Double)
        val ranked = eligible(tracks, events, excluded).mapNotNull { song ->
            val genres = GenreEvidence.comparable(song.genres)
            if ((candidateIds != null && song.mediaId !in candidateIds) || !intent.accepts(genres) ||
                GenreEvidence.overlaps(genres, common)) return@mapNotNull null
            Candidate(song, GenreEvidence.overlaps(genres, commonFamily),
                supporting.maxOf { GenreEvidence.affinity(genres, it.genres) }, GenrePreferenceScores.preference(song, events, scores))
        }.sortedWith(compareBy<Candidate> { it.sameFamily }.thenBy { it.affinity }
            .thenByDescending { it.preference }.thenBy { it.track.mediaId })
        val nearFamily = ranked.firstOrNull()?.sameFamily == true
        val tentative = known.size < 4 || nearFamily
        val explanation = "本次暂时避开 ${common.joinToString()}。四次快跳中 ${known.size} 首具备可比较曲风，" +
            "${commonTag.value} 首指向这个方向。" + when {
                nearFamily -> "最合适的候选仍属相近的大类，先试试这一首。"
                tentative -> "依据有限，先试试别的曲风。"
                else -> ""
            }
        return plan("四次快跳：尝试其他曲风", explanation, ranked.map { it.track }.distinctBy { it.recordingId }, events, common, familiarIds) {
            "候选曲风：${it.genres.joinToString()}"
        }.copy(directionKind = if (tentative) "GENRE_HINT" else "GENRE")
    }

    private fun eligible(tracks: List<StyleTrack>, events: List<ListeningSignal>, excluded: Set<String>, relatedIds: Set<String> = emptySet()): List<StyleTrack> {
        val rejectedIds = excluded + events.takeLast(10).filter { it.category != "completed" }.map { it.mediaId }
        val rejectedRecordings = tracks.filter { it.mediaId in rejectedIds && it.recordingId.isNotBlank() }.map { it.recordingId }.toSet()
        return tracks.filter { it.mediaId !in rejectedIds && it.recordingId !in rejectedRecordings &&
            (GenreEvidence.comparable(it.genres).isNotEmpty() || it.mediaId in relatedIds) }
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
        return StylePlan(heading, "$explanation\n$mix", ranked.filter { it in selected }.map {
            StylePick(it, reason(it), it.mediaId !in known)
        }, rejected)
    }
}
