package com.resonance.app

import org.json.JSONArray

data class SongScore(val mediaId: String, val score: Int, val eventCount: Int)
data class BehaviorProfile(
    val total: Int,
    val fast: Int,
    val partial: Int,
    val completed: Int,
    val scores: List<SongScore>
) {
    val completionPercent: Int get() = if (total == 0) 0 else completed * 100 / total
}

object ListeningInsights {
    /**
     * All scores reflect observed behavior only. They are NOT genre or emotional classifications.
     * Positive: completed +2, partial -1, fast skip -3.
     */
    fun fromEvents(events: JSONArray, latest: Int): BehaviorProfile {
        val score = linkedMapOf<String, IntArray>()
        var fast = 0
        var partial = 0
        var completed = 0
        val start = maxOf(0, events.length() - latest.coerceAtLeast(0))
        for (i in start until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val mediaId = event.optString("mediaId")
            if (mediaId.isBlank()) continue
            val category = event.optString("category")
            val points = when (category) {
                "fast" -> { fast++; -3 }
                "partial" -> { partial++; -1 }
                "completed" -> { completed++; 2 }
                else -> continue
            }
            val scoreAndCount = score.getOrPut(mediaId) { intArrayOf(0, 0) }
            scoreAndCount[0] += points
            scoreAndCount[1]++
        }
        val scores = score.map { (id, values) -> SongScore(id, values[0], values[1]) }
        return BehaviorProfile(fast + partial + completed, fast, partial, completed, scores)
    }
}
