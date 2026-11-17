package com.resonance.app

enum class TrackTransition { USER, NATURAL, AUTOMATIC, OTHER }
data class DirectListeningEvent(val mid: String, val heardMs: Long, val durationMs: Long, val category: String,
                                val reason: String, val changeDirection: Boolean)

/** Direct player events replace transition guesses. Seeking cannot inflate actual listening time. */
class DirectListeningTracker(var directionCooldownMs: Long = 0) {
    private var mid: String? = null
    private var durationMs = 0L
    private var heardMs = 0L
    private var lastTick = 0L
    private var wasPlaying = false
    private var lastHeardAt: Long? = null
    private var excerpt = false
    private var lastAnalysis: Long? = null
    var fastSkips = 0
        private set
    fun tick(now: Long, playing: Boolean) {
        val delta = now - lastTick
        if (wasPlaying && mid != null && delta in 1..10_000L) { heardMs += delta; lastHeardAt = now }
        lastTick = now
        wasPlaying = playing
    }
    fun setExcerpt(value: Boolean) { excerpt = value }
    fun resetDirection(){fastSkips=0;lastAnalysis=null}
    fun transition(nextMid: String?, nextDurationMs: Long, reason: TrackTransition, now: Long): DirectListeningEvent? {
        tick(now, false)
        val oldMid = mid
        val oldDuration = durationMs
        val heard = heardMs.coerceAtMost(oldDuration.coerceAtLeast(0))
        val oldExcerpt = excerpt
        val recentListening = lastHeardAt?.let { now - it <= 15_000L } == true
        mid = nextMid; durationMs = nextDurationMs; heardMs = 0; excerpt = false; wasPlaying = false; lastHeardAt = null
        if (oldMid == null || oldDuration <= 0 || oldExcerpt || reason in setOf(TrackTransition.AUTOMATIC, TrackTransition.OTHER)) {
            fastSkips = 0
            return null
        }
        if (reason == TrackTransition.USER && (heard <= 0 || !recentListening)) { fastSkips = 0; return null }
        val category = BehaviorRules.classify(heard, oldDuration) ?: return null
        if (reason == TrackTransition.NATURAL && category != "completed") { fastSkips = 0; return null }
        fastSkips = if (reason == TrackTransition.USER && category == "fast") fastSkips + 1 else 0
        var trigger = false
        if (fastSkips >= 4 && (lastAnalysis == null || now - lastAnalysis!! >= directionCooldownMs.coerceAtLeast(0))) {
            lastAnalysis = now; fastSkips = 0; trigger = true
        }
        return DirectListeningEvent(oldMid, heard, oldDuration,
            category, reason.name.lowercase(), trigger)
    }
    fun suspend(now: Long) { mid = null; heardMs = 0; wasPlaying = false; lastTick = now; fastSkips = 0 }
    val currentMid get() = mid
}

object DirectionCooldown {
    val options = listOf(0, 15, 30, 60, 120)
    fun milliseconds(seconds: Int) = (seconds.takeIf { it in options } ?: 0) * 1000L
    fun label(seconds: Int) = if (seconds == 0) "不额外等待" else "${seconds}秒"
}
