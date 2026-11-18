package com.resonance.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResonanceMotionTest {
    @Test
    fun slowFramesMustBeConsecutiveBeforeReducingDensity() {
        val ticker = FrameTicker()
        val slow = Motion.SlowFrameNanos + 1
        repeat(Motion.SlowFrameCount - 1) { ticker.reportFrame(slow) }
        ticker.reportFrame(Motion.SlowFrameNanos)
        repeat(Motion.SlowFrameCount - 1) { ticker.reportFrame(slow) }
        assertFalse(ticker.reducedQuality)
        ticker.reportFrame(slow)
        assertTrue(ticker.reducedQuality)
    }

    @Test
    fun hiddenPlateCanCancelItsWorkAndClockStaysIndependentOfMotionSetting() {
        val ticker = FrameTicker()
        ticker.request(Motion.TrackChange, sand = true)
        ticker.request(Motion.BatchEnter)
        assertTrue(ticker.hasSandWork())
        ticker.cancelSand()
        assertFalse(ticker.hasSandWork())
        assertFalse(MotionEnvironment(windowReady = false, systemEnabled = true).enabled)
        assertFalse(MotionEnvironment(windowReady = true, systemEnabled = false).enabled)
        assertTrue(MotionEnvironment(windowReady = true, systemEnabled = true).enabled)
    }

    @Test
    fun vibrationClarityAndStrikeStayWithinTheirIntendedRanges() {
        assertEquals(2.4f, Motion.sigma(0f), .001f)
        assertEquals(1.5f, Motion.sigma(.5f), .001f)
        assertEquals(.6f, Motion.sigma(1f), .001f)
        assertEquals(3f, Motion.knock(0f), .001f)
        assertTrue(Motion.knock(280f) < 0f)
        assertEquals(0f, Motion.knock(Motion.Strike.toFloat()), .001f)
        for (i in 0..200) {
            val t = i / 10f
            assertTrue(Motion.sandAmplitude(t) in .4f..2f)
            repeat(4) { assertTrue(Motion.logoHeight(it, t * 1000f, 1f) in .4f..1f) }
        }
    }

    @Test
    fun progressMovesBetweenSamplesAndSnapsOnPauseSeekOrTrackChange() {
        val playhead = SmoothPlayhead()
        assertEquals(5000f, playhead.position("a", 5000, 0L, true), .01f)
        assertEquals(5500f, playhead.position("a", 5000, 500_000_000L, true), .01f)
        assertEquals(6000f, playhead.position("a", 6050, 1_000_000_000L, true), .01f)
        assertEquals(7000f, playhead.position("a", 7000, 1_600_000_000L, false), .01f)
        assertEquals(14000f, playhead.position("a", 14000, 1_700_000_000L, true), .01f)
        assertEquals(500f, playhead.position("b", 500, 1_800_000_000L, true), .01f)
    }
}
