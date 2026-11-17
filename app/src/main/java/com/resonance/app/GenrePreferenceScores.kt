package com.resonance.app

data class GenreScore(val label:String,val recent:Double,val longTerm:Double,val combined:Double,val observations:Int)
fun LibraryState.genreScores()=GenrePreferenceScores.calculate(allTracks.map {song->
    StyleTrack("qq:${song.mid}",song.name,song.artist,genres[song.mid]?.recordingId?.ifBlank {"qq:${song.mid}"}?:"qq:${song.mid}",genres[song.mid]?.labels.orEmpty())
},scoreSignals)
object GenrePreferenceScores {
    fun calculate(tracks:List<StyleTrack>,events:List<ListeningSignal>):List<GenreScore> {
        val byId=tracks.associateBy {it.mediaId}
        fun points(event:ListeningSignal)=when(event.category){"completed"->2.0;"fast"->-3.0;"partial"->-1.0;else->0.0}
        fun sums(rows:List<ListeningSignal>):Map<String,Double> {
            val result=mutableMapOf<String,Double>()
            rows.forEach {event -> val labels=byId[event.mediaId]?.genres.orEmpty()
                labels.forEach {tag -> result[tag]=(result[tag]?:0.0)+points(event)/labels.size.coerceAtLeast(1)}}
            return result
        }
        val recent=sums(events.takeLast(20));val historical=events.takeLast(500);val long=sums(historical)
        val scale=20.0/maxOf(20,historical.size)
        val observations=historical.flatMap {byId[it.mediaId]?.genres.orEmpty()}.groupingBy {it}.eachCount()
        return tracks.flatMap {it.genres}.distinct().map {tag ->
            val short=recent[tag]?:0.0;val full=long[tag]?:0.0
            GenreScore(tag,short,full,short*.7+full*scale*.3,observations[tag]?:0)
        }.sortedWith(compareByDescending<GenreScore> {it.combined}.thenBy {it.label})
    }
    fun preference(track:StyleTrack,events:List<ListeningSignal>,scores:Map<String,GenreScore>):Double =
        (if(track.genres.isEmpty())0.0 else track.genres.sumOf {scores[it]?.combined?:0.0}/track.genres.size)-
            if(events.takeLast(3).any {it.mediaId==track.mediaId})3.0 else 0.0
}

data class DirectionSwitch(val id:Long,val time:Long,val fromMid:String,val targetMid:String,val targetName:String,
    val kind:String,val reason:String,val state:String="PREPARING",val detail:String="",val scores:Map<String,Double> = emptyMap())
object DirectionSwitchCodec {
    fun encode(rows:List<DirectionSwitch>)=org.json.JSONArray(rows.takeLast(20).map {row ->org.json.JSONObject()
        .put("id",row.id).put("time",row.time).put("from",row.fromMid).put("target",row.targetMid).put("name",row.targetName)
        .put("kind",row.kind).put("reason",row.reason).put("state",row.state).put("detail",row.detail)
        .put("scores",org.json.JSONObject(row.scores))}).toString()
    fun decode(raw:String?):List<DirectionSwitch> = try {
        val rows=org.json.JSONArray(raw?:"[]")
        (maxOf(0,rows.length()-20) until rows.length()).mapNotNull {i ->runCatching {
            val r=rows.getJSONObject(i);val scores=r.optJSONObject("scores")
            DirectionSwitch(r.getLong("id"),r.getLong("time"),r.getString("from"),r.getString("target"),r.getString("name"),
                r.getString("kind"),r.getString("reason"),r.getString("state"),r.optString("detail"),
                scores?.keys()?.asSequence()?.associateWith {scores.getDouble(it)}.orEmpty())
        }.getOrNull()}
    }catch(_:Exception){emptyList()}
}
