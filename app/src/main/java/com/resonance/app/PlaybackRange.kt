package com.resonance.app

enum class PlaybackRange(val label:String) {
    LIBRARY("收藏＋发现"),FAVORITES("仅收藏"),BATCH("当前这批");
    companion object {fun parse(value:String)=entries.firstOrNull {it.name==value}?:LIBRARY}
}
object PlaybackQueuePolicy {
    fun pool(state:LibraryState,range:PlaybackRange,batch:List<String>,current:String):List<QqCatalogueTrack> {
        val candidates=when(range){PlaybackRange.LIBRARY->state.allTracks;PlaybackRange.FAVORITES->state.favorites
            PlaybackRange.BATCH->batch.mapNotNull {mid->state.allTracks.firstOrNull {it.mid==mid}}}
        return candidates.filter {it.mid==current || state.intent.accepts(state.genres[it.mid]?.labels.orEmpty())}.distinctBy {it.mid}.take(6000)
    }
    fun build(initial:List<QqCatalogueTrack>,available:List<QqCatalogueTrack>,selected:String)=
        (initial.filter {it.mid==selected || available.any {song->song.mid==it.mid}}+available).distinctBy {it.mid}.take(6000)
    fun surprise(seed:QqCatalogueTrack,state:LibraryState)=
        (listOf(seed)+pool(state,PlaybackRange.LIBRARY,emptyList(),seed.mid)).distinctBy {it.mid}.take(100)
}

internal fun planDirection(state: LibraryState, tracks: List<StyleTrack>, current: String, range: PlaybackRange): StylePlan {
    val favorites = state.favorites.map {"qq:${it.mid}"}.toSet()
    val events = state.listening
    val recentIds = recentDirectionTargets(state.directionHistory, tracks) + events.takeLast(20).map {it.mediaId}
    val recentRecordings = tracks.filter {it.mediaId in recentIds}.map {it.recordingId}.toSet()
    val excluded = recentIds + tracks.filter {it.recordingId in recentRecordings}.map {it.mediaId}
    val candidates = tracks.filter {
        state.intent.accepts(it.genres) && (range != PlaybackRange.FAVORITES || it.mediaId in favorites)
    }.map {it.mediaId}.toSet()
    val style = StyleRecommendations.changeDirection(
        current, tracks, events, favorites, state.intent, state.scoreSignals, state.manualScores,
        allowPartialEvidence = true, excludedIds = excluded, candidateIds = candidates
    )
    return DirectionFallback.plan(style, tracks, events, favorites, current, state.scoreSignals, state.manualScores,
        excludedIds = excluded, candidateIds = candidates).copy(directionExcludedIds = excluded)
}

object DirectionFallback {
    fun plan(original: StylePlan, tracks: List<StyleTrack>, events: List<ListeningSignal>, favorites: Set<String>,
             current: String, scoreEvents: List<ListeningSignal> = events, manualScores: Map<String,Double> = emptyMap(),
             excludedIds: Set<String> = emptySet(), candidateIds: Set<String>? = null): StylePlan {
        if(original.picks.isNotEmpty())return original
        if(events.takeLast(4).size<4 || events.takeLast(4).any {it.category!="fast"})return original
        val rejected = events.takeLast(20).map {it.mediaId}.toSet() + current + excludedIds
        val byId = tracks.associateBy {it.mediaId}
        val rejectedRecordings = rejected.mapNotNull {byId[it]?.recordingId}.toSet()
        val recentStyles = events.takeLast(4).mapNotNull {byId[it.mediaId]}
            .map {GenreEvidence.comparable(it.genres)}.filter {it.isNotEmpty()}
        val scores = GenrePreferenceScores.calculate(tracks,scoreEvents,manualScores).associateBy {it.label}
        fun similarityToSkipped(song: StyleTrack): Double {
            if (recentStyles.isEmpty()) return 0.0
            if (GenreEvidence.comparable(song.genres).isEmpty()) return .65
            return recentStyles.maxOf {GenreEvidence.affinity(song.genres,it)}
        }
        val inScope = tracks.filter { candidateIds == null || it.mediaId in candidateIds }
        val pool = inScope.filter { it.mediaId !in rejected && it.recordingId !in rejectedRecordings }
            .sortedWith(compareBy<StyleTrack> {similarityToSkipped(it)}
            .thenByDescending {GenrePreferenceScores.preference(it,events,scores)})
            .distinctBy {it.recordingId}.toMutableList()
        val ranked=mutableListOf<StyleTrack>()
        while(pool.isNotEmpty() && ranked.size<30) {
            val next=pool.firstOrNull {candidate->ranked.takeLast(2).none {it.artist==candidate.artist}}?:pool.first()
            pool.remove(next);ranked.add(next)
        }
        val known=favorites+events.map {it.mediaId}
        val selected=(ranked.filter {it.mediaId in known}.take(7)+ranked.filter {it.mediaId !in known}.take(3)).toMutableList()
        selected.addAll(ranked.filter {it !in selected}.take(10-selected.size))
        if(selected.isEmpty()) return original.copy(explanation=original.explanation+
            "\n当前范围内有 ${inScope.size} 首符合选歌条件，排除当前歌曲、最近听过的歌和近期换向目标后没有候选，继续原队列。")
        val explanation = if (original.rejectedGenres.isEmpty()) original.explanation else
            "跳过的歌曲多带有 ${original.rejectedGenres.joinToString()}，但当前没有曲风资料足够且符合条件的其他风格候选。"
        return StylePlan("换一批", explanation + if(recentStyles.isEmpty())
            "\n换一批近期未出现的候选。" else "\n换一批近期未出现的候选，优先选与已知快跳曲风区别较大的歌曲。",
            ranked.filter { it in selected }.map { StylePick(it, "四次快跳后的换批候选", it.mediaId !in known) },
            directionKind = "BATCH")
    }
}
