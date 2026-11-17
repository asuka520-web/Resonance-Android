package com.resonance.app

object CatalogueEnrichmentPlan {
    fun candidates(tracks:List<QqCatalogueTrack>,genres:Map<String,CatalogueGenre>,mid:String?,automatic:Boolean,now:Long)=
        tracks.distinctBy {it.mid}.filter {song ->
            if(mid!=null)song.mid==mid else if(automatic)genres[song.mid]?.labels?.size==1 else genres[song.mid]?.labels.orEmpty().size<=1
        }.filter {!automatic || (genres[it.mid]?.enrichRetryAt?:0)<=now}
}
