package com.resonance.app

import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/** Weighted permutation without replacement. Keep the visited prefix so previous/redo stay stable. */
class PreferenceShuffle(private val random:Random=Random.Default) {
    private var order=emptyList<String>()
    private var visitedThrough=-1
    private var lastCurrent:String?=null
    var generation=0L
        private set
    fun reset(){order=emptyList();visitedThrough=-1;lastCurrent=null;generation++}
    fun visit(mid:String,backwards:Boolean=false) {
        val index=order.indexOf(mid)
        if(!backwards && index==0 && visitedThrough==order.lastIndex && order.size>1 && lastCurrent==order.last()) {
            reset() // A completed cycle: the new current begins the next weighted cycle.
        } else if(index>=0)visitedThrough=maxOf(visitedThrough,index)
        lastCurrent=mid
    }
    fun reorder(mids:List<String>,current:String,scores:Map<String,Double>):List<String> {
        val available=mids.distinct()
        val availableSet=available.toSet()
        val prefix=order.take(visitedThrough+1).filter {it in availableSet}.toMutableList()
        if(current in availableSet && current !in prefix)prefix.add(current)
        val fixed=prefix.toSet()
        val remaining=available.filter {it !in fixed}.map {mid ->
            mid to -ln(random.nextDouble().coerceAtLeast(1e-12))/weight(scores[mid]?:0.0)
        }.sortedBy {it.second}.map {it.first}
        order=prefix+remaining;visitedThrough=prefix.lastIndex;lastCurrent=current
        return order
    }
    companion object {
        // Bounded, uncalibrated preference weights; unknown/zero remains neutral and can be explored.
        fun weight(score:Double)=exp(score.takeIf {it.isFinite()}?.coerceIn(-6.0,6.0)?.times(.28)?:0.0)
    }
}
