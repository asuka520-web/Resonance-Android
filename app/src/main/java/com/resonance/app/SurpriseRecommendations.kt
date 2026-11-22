package com.resonance.app

import kotlin.random.Random

/** Unknown is a separate evidence state, never a genre and never proof of a different direction. */
object SurpriseRecommendations {
    fun compose(base:StylePlan,tracks:List<StyleTrack>,events:List<ListeningSignal>,favorites:Set<String>,
                anchorId:String,enabled:Boolean,preferredId:String?=null,random:Random=Random.Default,intent:ListeningIntent=ListeningIntent()):StylePlan {
        if(!enabled)return base
        val knownIds=favorites+events.map {it.mediaId}
        val recent=events.takeLast(20).map {it.mediaId}.toSet()
        val picks = base.picks.filter {
            val styles = GenreEvidence.comparable(it.track.genres)
            styles.isNotEmpty() && intent.accepts(styles)
        }.take(10).toMutableList()
        val excludedIds = recent + anchorId
        val excludedRecordings = (tracks.filter { it.mediaId in excludedIds } + picks.map { it.track })
            .map { it.recordingId }.filter(String::isNotBlank).toSet()
        val pool = if (intent.active) emptyList() else tracks.filter {
            it.mediaId !in excludedIds && it.recordingId !in excludedRecordings && GenreEvidence.comparable(it.genres).isEmpty()
        }
        val eligibleIds = pool.map { it.mediaId }.toSet()
        val related = base.picks.filter { it.track.mediaId in eligibleIds && !it.surprise }
        val chosen = related.firstOrNull { it.track.mediaId == preferredId } ?: related.firstOrNull()
            ?: (pool.firstOrNull { it.mediaId == preferredId } ?: pool.distinctBy { it.recordingId }.randomOrNull(random))
                ?.let { StylePick(it, "惊喜一首 · 曲风未知，未按风格匹配", it.mediaId !in knownIds, surprise = true) }
        if(chosen!=null) {
            if(picks.size==10) {
                val sameGroup=picks.indexOfLast {it.unfamiliar==chosen.unfamiliar}
                picks.removeAt(if(sameGroup>=0)sameGroup else picks.lastIndex)
            }
            picks.add(if (chosen.surprise) minOf(3, picks.size) else picks.size, chosen)
        }
        val unfamiliar=picks.count {it.unfamiliar}
        val explanation=base.explanation.substringBefore("\n本批 ")+
            "\n本批 ${picks.size} 首：熟悉候选 ${picks.size-unfamiliar} 首、尚无记录 $unfamiliar 首。目标 70% / 30%，按实际候选提供。"+
            when {
                chosen?.surprise == true -> "\n本批有一首随机惊喜，曲风待补齐。"
                chosen != null -> "\n本批有一首 QQ 直接相关歌曲，曲风待补齐。"
                intent.active -> "\n本次有想听或避开条件，未知曲风无法确认符合；清除条件后可抽取惊喜。"
                else -> "\n暂无可抽取的未知曲风歌曲。"
            }
        return base.copy(heading=if(base.picks.isEmpty() && chosen!=null)"惊喜一首" else base.heading,
            explanation=explanation,picks=picks)
    }
}
