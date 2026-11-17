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

object DirectionFallback {
    fun plan(original:StylePlan,tracks:List<StyleTrack>,events:List<ListeningSignal>,favorites:Set<String>,current:String):StylePlan {
        if(original.picks.isNotEmpty())return original
        if(events.takeLast(4).size<4 || events.takeLast(4).any {it.category!="fast"})return original
        val rejected=events.takeLast(20).map {it.mediaId}.toSet()+current
        val scores=GenrePreferenceScores.calculate(tracks,events).associateBy {it.label}
        val pool=tracks.filter {it.mediaId !in rejected}.distinctBy {it.recordingId}.sortedByDescending {GenrePreferenceScores.preference(it,events,scores)}.toMutableList()
        val ranked=mutableListOf<StyleTrack>()
        while(pool.isNotEmpty() && ranked.size<30) {
            val next=pool.firstOrNull {candidate->ranked.takeLast(2).none {it.artist==candidate.artist}}?:pool.first()
            pool.remove(next);ranked.add(next)
        }
        val known=favorites+events.map {it.mediaId}
        val selected=(ranked.filter {it.mediaId in known}.take(7)+ranked.filter {it.mediaId !in known}.take(3)).toMutableList()
        selected.addAll(ranked.filter {it !in selected}.take(10-selected.size))
        if(selected.isEmpty())return original
        return StylePlan("换一批，重新找感觉",original.explanation+"\n现有曲风不足以找到另一种风格，改换未在近期记录中出现的一批候选；不宣称已换曲风。",
            selected.map {StylePick(it,"连续快跳后换批候选，未按不同曲风匹配",it.mediaId !in known)},directionKind="BATCH")
    }
}
