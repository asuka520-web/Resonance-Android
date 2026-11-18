package com.resonance.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ResonanceGraphTest {
    @Test fun strongestModesKeepTheSignOfTheirRealScores() {
        val scores = listOf(1, -3, 5, -12, 2, 9, 0).map {
            GenreScore(it.toString(), 0.0, 0.0, it.toDouble(), 1)
        }
        val modes = graphModes(scores)
        assertEquals(listOf("-12", "9", "5", "-3", "2"), modes.map { it.seed })
        assertEquals(-1f, modes.first().weight, 0f)
        assertEquals(.75f, modes[1].weight, 0f)
    }

    @Test fun oppositeWeightsCancelPotentialAndBothDerivatives() {
        val field = ChladniField.compose(listOf(GraphMode("dance", 1f), GraphMode("dance", -1f)))
        for (component in 0..2) assertEquals(0f, field.sample(14, 27, .6f, .2f, component), .00001f)
    }

    @Test fun particleCapacityUsesRealMinutesWithoutOverflow() {
        assertEquals(200, graphGrainCount(0))
        assertEquals(458, graphGrainCount(129))
        assertEquals(1200, graphGrainCount(Long.MAX_VALUE))
    }
}
