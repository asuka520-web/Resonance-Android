package com.resonance.app

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PreferenceShuffleTest {
    @Test fun higherScoresIncreaseProbabilityWithoutMakingLowScoresImpossible() {
        var preferred=0
        repeat(1000){seed->val order=PreferenceShuffle(Random(seed)).reorder(listOf("current","high","low"),"current",mapOf("high" to 6.0,"low" to -6.0))
            if(order[1]=="high")preferred++}
        assertTrue("high first $preferred / 1000",preferred in 900..999)
    }
    @Test fun neutralAndUnknownScoresRetainUniformExploration() {
        var first=0
        repeat(1000){seed->if(PreferenceShuffle(Random(seed)).reorder(listOf("current","a","b"),"current",emptyMap())[1]=="a")first++}
        assertTrue(first in 400..600)
        assertEquals(1.0,PreferenceShuffle.weight(Double.NaN),0.0)
        assertEquals(PreferenceShuffle.weight(6.0),PreferenceShuffle.weight(60000.0),0.0)
    }
    @Test fun dynamicUpdatePreservesVisitedHistoryAndReweightsOnlyRemainingSongs() {
        val mids=(0..30).map {"s$it"};val shuffle=PreferenceShuffle(Random(10))
        val first=shuffle.reorder(mids,"s0",emptyMap())
        shuffle.visit(first[1]);shuffle.visit(first[2])
        val updated=shuffle.reorder(mids,first[2],mids.associateWith {if(it==first.last())6.0 else -6.0})
        assertEquals(first.take(3),updated.take(3));assertEquals(mids.toSet(),updated.toSet());assertEquals(mids.size,updated.size)
        shuffle.visit(first[1],true)
        val afterPrevious=shuffle.reorder(mids,first[1],emptyMap())
        assertEquals(first.take(3),afterPrevious.take(3))
    }
    @Test fun removalAppendAndCycleDoNotDuplicateOrLoseTracks() {
        val shuffle=PreferenceShuffle(Random(25));val mids=listOf("a","b","c","d")
        val order=shuffle.reorder(mids,"a",emptyMap())
        order.forEach {shuffle.visit(it)}
        val generation=shuffle.generation;shuffle.visit(order.first())
        assertTrue(shuffle.generation>generation)
        val new=shuffle.reorder(listOf("a","e","d"),"a",emptyMap())
        assertEquals(setOf("a","d","e"),new.toSet());assertEquals(3,new.size);assertEquals("a",new.first())
    }
}
