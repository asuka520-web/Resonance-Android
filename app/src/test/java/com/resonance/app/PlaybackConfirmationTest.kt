package com.resonance.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackConfirmationTest {
    @Test fun unrelatedIdChangesOrPausedTargetAreNotSuccess() {
        val confirmation = PlaybackConfirmation()
        assertTrue(confirmation.start("target", 0))
        assertNull(confirmation.observe("other", true, 1200))
        assertNull(confirmation.observe("target", false, 2400))
        assertNotNull(confirmation.targetId)
        assertTrue(confirmation.observe("other", true, 10_000)!!.contains("超时"))
        assertNull(confirmation.targetId)
    }

    @Test fun targetMustBePlayingAcrossTwoSpacedPolls() {
        val confirmation = PlaybackConfirmation()
        confirmation.start("target", 1000)
        assertNull(confirmation.observe("target", true, 1100))
        assertNull(confirmation.observe("target", true, 1500))
        assertTrue(confirmation.observe("target", true, 2300)!!.contains("已确认"))
        assertNull(confirmation.targetId)
        assertNull(confirmation.observe("target", true, 3500))
    }

    @Test fun interruptionsResetConfirmationAndConcurrentRequestsAreRejected() {
        val confirmation = PlaybackConfirmation()
        confirmation.start("target", 0)
        assertFalse(confirmation.start("second", 1))
        confirmation.observe("target", true, 1200)
        confirmation.observe("other", true, 2400)
        assertNull(confirmation.observe("target", true, 3600))
        confirmation.cancel()
        assertNull(confirmation.observe("target", true, 4800))
        assertTrue(confirmation.start("second", 5000))
    }
}
