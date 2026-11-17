package com.resonance.app

import kotlin.random.Random

/** Unknown is a separate evidence state, never a genre and never proof of a different direction. */
object SurpriseRecommendations {
    fun compose(base:StylePlan,tracks:List<StyleTrack>,events:List<ListeningSignal>,favorites:Set<String>,
                anchorId:String,enabled:Boolean,preferredId:String?=null,random:Random=Random.Default,intent:ListeningIntent=ListeningIntent()):StylePlan {
        if(!enabled)return base
        val knownIds=favorites+events.map {it.mediaId}
        val recent=events.takeLast(20).map {it.mediaId}.toSet()
        val anchorRecording=tracks.firstOrNull {it.mediaId==anchorId}?.recordingId
        val pool=tracks.filter {intent.accepts(it.genres) && it.genres.isEmpty() && it.mediaId!=anchorId && it.mediaId !in recent &&
            it.recordingId!=anchorRecording}.distinctBy {it.recordingId}
        val chosen=pool.firstOrNull {it.mediaId==preferredId} ?: pool.randomOrNull(random)
        val picks=base.picks.filter {it.track.genres.isNotEmpty() && intent.accepts(it.track.genres)}.take(10).toMutableList()
        if(chosen!=null) {
            val unfamiliar=chosen.mediaId !in knownIds
            if(picks.size==10) {
                val sameGroup=picks.indexOfLast {it.unfamiliar==unfamiliar}
                picks.removeAt(if(sameGroup>=0)sameGroup else picks.lastIndex)
            }
            picks.add(minOf(3,picks.size),StylePick(chosen,"惊喜一首 · 曲风未知，未按风格匹配",unfamiliar,surprise=true))
        }
        val unfamiliar=picks.count {it.unfamiliar}
        val explanation=base.explanation.substringBefore("\n本批 ")+
            "\n本批 ${picks.size} 首：熟悉候选 ${picks.size-unfamiliar} 首、尚无记录 $unfamiliar 首。目标 70% / 30%，按实际候选提供。"+
            if(chosen==null) {if(intent.active)"\n本次有想听或避开条件，未知曲风无法确认符合；清除条件后可抽取惊喜。" else "\n暂无可抽取的未知曲风歌曲。"}
            else "\n最多一首未知曲风惊喜；收藏歌曲仍算熟悉，不将未知当作其他曲风。"
        return base.copy(heading=if(base.picks.isEmpty() && chosen!=null)"惊喜一首" else base.heading,
            explanation=explanation,picks=picks)
    }
}
