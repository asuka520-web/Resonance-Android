package com.resonance.app

import org.junit.Assert.*
import org.junit.Test

class DirectionNoticeTest {
    @Test fun announcesOnlyOnceAfterActualConsecutiveProgress() {
        val gate=DirectionNoticeGate()
        gate.request("target",setOf("jazz"),0)
        assertNull(gate.observe("target",false,0,false,1000))
        assertNull(gate.observe("target",true,1500,false,2000))
        val notice=gate.observe("target",true,2500,false,3000)!!
        assertEquals(setOf("jazz"),notice.genres)
        assertNull(gate.observe("target",true,3500,false,4000))
    }
    @Test fun pausedStaticExcerptWrongTrackAndExpiredRequestNeverClaimSuccess() {
        val gate=DirectionNoticeGate()
        gate.request("t",setOf("rock"),0)
        assertNull(gate.observe("t",true,90000,false,1000))
        assertNull(gate.observe("t",true,90000,false,2000))
        assertNull(gate.observe("t",false,90000,false,3000))
        assertNull(gate.observe("t",true,91000,true,4000))
        gate.request("t",setOf("rock"),5000)
        assertNull(gate.observe("other",true,2000,false,6000))
        gate.request("t",setOf("rock"),7000)
        assertNull(gate.observe("t",true,2000,false,40000))
    }
    @Test fun cancellationAndANewRequestDoNotReuseAnOldNotice() {
        val gate=DirectionNoticeGate()
        gate.request("a",setOf("rock"),0);gate.cancel()
        assertNull(gate.observe("a",true,2000,false,1000))
        gate.request("b",setOf("soul"),2000)
        assertNull(gate.observe("b",true,1000,false,3000))
        assertEquals(setOf("soul"),gate.observe("b",true,2000,false,4000)!!.genres)
    }
}
