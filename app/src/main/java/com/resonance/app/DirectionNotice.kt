package com.resonance.app

data class DirectionNotice(val id:Long,val createdAt:Long,val genres:Set<String>)

/** Announce confirmed audible progress, never merely a queue replacement request. */
class DirectionNoticeGate {
    private var target:String?=null
    private var requestedAt=0L
    private var genres=emptySet<String>()
    private var observedAt:Long?=null
    private var position=0L
    fun request(mid:String,labels:Set<String>,now:Long) {
        target=mid;genres=labels;requestedAt=now;observedAt=null;position=0
    }
    fun cancel(){target=null;observedAt=null}
    fun observe(mid:String,playing:Boolean,positionMs:Long,excerpt:Boolean,now:Long):DirectionNotice? {
        if(target==null)return null
        if(now-requestedAt>30000 || mid!=target || excerpt){cancel();return null}
        if(!playing){observedAt=null;return null}
        val first=observedAt
        if(first!=null && now-first in 500..5000L && positionMs-position>=250) {
            val event=DirectionNotice(now,now,genres)
            cancel();return event
        }
        observedAt=now;position=positionMs
        return null
    }
}
