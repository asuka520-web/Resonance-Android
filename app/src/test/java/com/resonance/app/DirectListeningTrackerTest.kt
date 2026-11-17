package com.resonance.app

import org.junit.Assert.*
import org.junit.Test

class DirectListeningTrackerTest {
    @Test fun fourExplicitFastSkipsTriggerOnceAndAutomaticSwitchIsNotNegativeFeedback() {
        val tracker=DirectListeningTracker()
        tracker.transition("0",200000,TrackTransition.OTHER,0)
        for(i in 1..4) {
            tracker.tick((i-1)*5000L,true)
            val event=tracker.transition("$i",200000,TrackTransition.USER,i*5000L)!!
            assertEquals("fast",event.category)
            assertEquals(i==4,event.changeDirection)
        }
        assertEquals(0,tracker.fastSkips)
        tracker.tick(20000,true)
        assertNull(tracker.transition("recommended",200000,TrackTransition.AUTOMATIC,21000))
        assertEquals(0,tracker.fastSkips)
    }
    @Test fun skipsInsideCooldownDoNotRetrigger() {
        val tracker=DirectListeningTracker(120000)
        tracker.transition("0",200000,TrackTransition.OTHER,0)
        for(i in 1..8) {
            tracker.tick((i-1)*5000L,true)
            val event=tracker.transition("$i",200000,TrackTransition.USER,i*5000L)!!
            assertEquals(i==4,event.changeDirection)
        }
    }
    @Test fun defaultCanChangeDirectionAgainAfterAnotherFourValidSkips() {
        val tracker=DirectListeningTracker()
        tracker.transition("0",200000,TrackTransition.OTHER,0)
        for(i in 1..12) {
            tracker.tick((i-1)*2000L,true)
            val event=tracker.transition("$i",200000,TrackTransition.USER,i*2000L)!!
            assertEquals(i%4==0,event.changeDirection)
        }
    }
    @Test fun changingCooldownTakesEffectImmediatelyAndInvalidSettingsUseDefault() {
        val tracker=DirectListeningTracker(DirectionCooldown.milliseconds(120))
        tracker.transition("0",200000,TrackTransition.OTHER,0)
        for(i in 1..7) {
            tracker.tick((i-1)*2000L,true)
            tracker.transition("$i",200000,TrackTransition.USER,i*2000L)
        }
        tracker.directionCooldownMs=DirectionCooldown.milliseconds(0)
        tracker.tick(14000,true)
        assertTrue(tracker.transition("8",200000,TrackTransition.USER,16000)!!.changeDirection)
        assertEquals(0L,DirectionCooldown.milliseconds(-1))
        assertEquals(30000L,DirectionCooldown.milliseconds(30))
    }
    @Test fun seekingAndPausedTimeDoNotInflateListening() {
        val tracker=DirectListeningTracker()
        tracker.transition("a",200000,TrackTransition.OTHER,0)
        tracker.tick(0,true)
        tracker.tick(4000,false)
        tracker.tick(9000,false)
        val event=tracker.transition("b",200000,TrackTransition.USER,10000)!!
        assertEquals(4000,event.heardMs)
        assertEquals("fast",event.category)
    }
    @Test fun NeverPlayedOrLongPausedTrackIsNotAnInferredDislike() {
        val tracker=DirectListeningTracker()
        tracker.transition("a",200000,TrackTransition.OTHER,0)
        assertNull(tracker.transition("b",200000,TrackTransition.USER,1000))
        tracker.tick(1000,true);tracker.tick(4000,false);tracker.tick(30000,false)
        assertNull(tracker.transition("c",200000,TrackTransition.USER,31000))
    }
    @Test fun naturalEndAndTrialExcerptsAreHandledSeparately() {
        val tracker=DirectListeningTracker()
        tracker.transition("a",200000,TrackTransition.OTHER,0)
        tracker.tick(0,true)
        for(i in 1..20)tracker.tick(i*10000L,true)
        assertEquals("completed",tracker.transition("b",200000,TrackTransition.NATURAL,200000)!!.category)
        tracker.tick(200000,true);tracker.setExcerpt(true)
        assertNull(tracker.transition("c",200000,TrackTransition.NATURAL,205000))
        assertEquals(0,tracker.fastSkips)
    }
    @Test fun seekToNaturalEndAfterOneSecondIsNotPositiveFeedback() {
        val tracker=DirectListeningTracker()
        tracker.transition("a",200000,TrackTransition.OTHER,0);tracker.tick(0,true)
        assertNull(tracker.transition("b",200000,TrackTransition.NATURAL,1000))
    }
}
