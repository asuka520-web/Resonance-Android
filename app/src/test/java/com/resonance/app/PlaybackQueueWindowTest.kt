package com.resonance.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueWindowTest {
    @Test fun largeFavoritesContinueBeyondFirstQueueWindowWithoutLoadingAllAudio() {
        val library=(0..999).map{ "mid$it" }
        val initial=library.take(100)
        assertTrue(PlaybackQueueWindow.following(initial,library,20).isEmpty())
        val next=PlaybackQueueWindow.following(initial,library,95)
        assertEquals(library.subList(100,150),next)
        assertEquals(50,next.size)
    }
    @Test fun missingOrFinalCatalogueItemDoesNotLoopToTheBeginning() {
        assertTrue(PlaybackQueueWindow.following(listOf("missing"),listOf("a","b"),0).isEmpty())
        assertTrue(PlaybackQueueWindow.following(listOf("a","b"),listOf("a","b"),1).isEmpty())
    }
}
