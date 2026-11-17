package com.resonance.app

data class DirectionNotice(val id:Long,val createdAt:Long,val genres:Set<String>,val kind:String="GENRE",val message:String="")

/** Announce confirmed audible progress, never merely a queue replacement request. */
class DirectionNoticeGate {
    private var target:String?=null
    private var requestedAt=0L
    private var genres=emptySet<String>()
    private var observedAt:Long?=null
    private var position=0L
    private var kind="GENRE"
    val pending get()=target!=null
    fun request(mid:String,labels:Set<String>,now:Long,noticeKind:String="GENRE") {
        target=mid;genres=labels;requestedAt=now;observedAt=null;position=0;kind=noticeKind
    }
    fun cancel(){target=null;observedAt=null}
    fun observe(mid:String,playing:Boolean,positionMs:Long,excerpt:Boolean,now:Long):DirectionNotice? {
        if(target==null)return null
        if(now-requestedAt>30000 || excerpt){cancel();return null}
        if(mid!=target){observedAt=null;return null}
        if(!playing){observedAt=null;return null}
        val first=observedAt
        if(first!=null && now-first in 500..5000L && positionMs-position>=250) {
            val event=DirectionNotice(now,now,genres,kind)
            cancel();return event
        }
        observedAt=now;position=positionMs
        return null
    }
}
