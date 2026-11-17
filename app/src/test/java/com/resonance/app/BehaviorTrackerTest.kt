package com.resonance.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BehaviorTrackerTest {
    private fun contract(): JSONObject = javaClass.classLoader!!.getResourceAsStream("listening-contract.json")!!
        .bufferedReader().use { JSONObject(it.readText()) }

    @Test fun sharedClassificationBoundaries() {
        val cases = contract().getJSONArray("classifications")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val expected = if (case.isNull("expected")) null else case.getString("expected")
            assertEquals(case.toString(), expected, BehaviorRules.classify(case.getLong("positionMs"), case.getLong("durationMs")))
        }
    }

    @Test fun sharedObservationSequences() {
        val cases = contract().getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val tracker = BehaviorTracker()
            val actual = mutableListOf<InferredListeningEvent>()
            val steps = case.getJSONArray("steps")
            for (j in 0 until steps.length()) {
                val step = steps.getJSONObject(j)
                if (step.optBoolean("disconnect")) {
                    tracker.suspendObservation("fixture disconnect")
                } else {
                    tracker.observe(PlaybackObservation(
                        if (step.isNull("id")) null else step.getString("id"),
                        step.getLong("positionMs"), step.optLong("durationMs", 180_000L),
                        step.optBoolean("playing", true), step.getLong("atMs")
                    ))?.let(actual::add)
                }
            }
            val expected = case.getJSONArray("events")
            assertEquals(name, expected.length(), actual.size)
            actual.forEachIndexed { j, event ->
                val expectedEvent = expected.getJSONObject(j)
                assertEquals(name, expectedEvent.getString("mediaId"), event.mediaId)
                assertEquals(name, expectedEvent.getLong("playedMs"), event.playedMs)
                assertEquals(name, expectedEvent.getLong("durationMs"), event.durationMs)
                assertEquals(name, expectedEvent.getString("category"), event.category)
            }
            assertEquals(name, case.getInt("streak"), tracker.consecutiveFastSkips)
            assertEquals(name, case.getInt("analysisCount"), tracker.analysisCount)
        }
    }

    @Test fun sharedPreferenceScores() {
        val cases = contract().getJSONArray("profiles")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val actual = ListeningInsights.fromEvents(case.getJSONArray("events"), case.getInt("latest"))
            assertEquals(name, case.getInt("total"), actual.total)
            assertEquals(name, case.getInt("completionPercent"), actual.completionPercent)
            val scores = case.getJSONObject("scores")
            assertEquals(name, scores.length(), actual.scores.size)
            actual.scores.forEach { score ->
                val expected = scores.getJSONArray(score.mediaId)
                assertEquals(name, expected.getInt(0), score.score)
                assertEquals(name, expected.getInt(1), score.eventCount)
            }
        }
    }

    @Test fun positionExtrapolationClampsAndHandlesInvalidClocks() {
        assertEquals(180_000L, PlaybackPosition.estimate(179_000, 180_000, true, 1f, 1000, 5000))
        assertEquals(8000L, PlaybackPosition.estimate(8000, 180_000, false, 1f, 1000, 5000))
        assertEquals(-1L, PlaybackPosition.estimate(-1, 180_000, true, 1f, 1000, 5000))
        assertEquals(8000L, PlaybackPosition.estimate(8000, 180_000, true, 1f, 0, 5000))
        assertEquals(8000L, PlaybackPosition.estimate(8000, 180_000, true, 1f, 5000, 1000))
        assertEquals(8000L, PlaybackPosition.estimate(8000, 180_000, true, Float.NaN, 1000, 5000))
    }

    @Test fun emptyProfilesAndZeroWindowAreSafe() {
        assertEquals(0, ListeningInsights.fromEvents(JSONArray(), 20).completionPercent)
        assertEquals(0, ListeningInsights.fromEvents(JSONArray("[{\"mediaId\":\"a\",\"category\":\"fast\"}]"), 0).total)
    }
}
