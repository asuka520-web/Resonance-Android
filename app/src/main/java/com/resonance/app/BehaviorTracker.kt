package com.resonance.app

/** No Android dependencies: exercised on the computer with the shared fixtures. */
data class PlaybackObservation(
    val mediaId: String?,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val observedAtMs: Long
)

data class InferredListeningEvent(
    val mediaId: String,
    val playedMs: Long,
    val durationMs: Long,
    val category: String
)

object BehaviorRules {
    fun classify(positionMs: Long, durationMs: Long): String? = when {
        durationMs <= 0L || positionMs < 0L -> null
        positionMs.toDouble() / durationMs >= 0.70 || durationMs - positionMs <= 10_000L -> "completed"
        positionMs < 15_000L -> "fast"
        else -> "partial"
    }
}

class BehaviorTracker {
    private var current: PlaybackObservation? = null
    private var lastAnalysisAtMs: Long? = null
    var consecutiveFastSkips = 0
        private set
    var analysisCount = 0
        private set
    var notice = "等待歌曲切换"
        private set

    fun suspendObservation(reason: String) {
        current = null
        consecutiveFastSkips = 0
        notice = reason
        // Keep the analysis cooldown across temporary disconnects.
    }

    fun observe(observation: PlaybackObservation): InferredListeningEvent? {
        val id = observation.mediaId?.takeIf { it.isNotBlank() }
        if (id == null) {
            suspendObservation("缺少稳定媒体 ID，暂停行为判断")
            return null
        }
        val previous = current
        current = observation.copy(mediaId = id)
        if (previous == null || previous.mediaId == id) return null
        val gap = observation.observedAtMs - previous.observedAtMs
        if (!previous.playing || gap !in 0L until 15_000L) {
            consecutiveFastSkips = 0
            notice = "已换歌，上一首暂停或观察已过期，未推断跳歌"
            return null
        }
        val category = BehaviorRules.classify(previous.positionMs, previous.durationMs)
        if (category == null) {
            consecutiveFastSkips = 0
            notice = "已换歌，上一首进度或时长无效，未写入记录"
            return null
        }
        consecutiveFastSkips = if (category == "fast") consecutiveFastSkips + 1 else 0
        notice = when (category) {
            "fast" -> "快速跳过（15 秒内）"
            "partial" -> "中途切歌（弱负反馈）"
            else -> "基本听完（正反馈）"
        }
        if (consecutiveFastSkips >= 4) {
            val lastAnalysis = lastAnalysisAtMs
            if (lastAnalysis == null || observation.observedAtMs - lastAnalysis >= 120_000L) {
                lastAnalysisAtMs = observation.observedAtMs
                analysisCount++
                consecutiveFastSkips = 0
                notice = "连续 4 次快速跳过，触发行为分析（未控制播放）"
            } else notice = "连续跳过达到阈值，120 秒冷却中"
        }
        return InferredListeningEvent(
            previous.mediaId!!, previous.positionMs.coerceAtMost(previous.durationMs),
            previous.durationMs, category
        )
    }
}

object PlaybackPosition {
    fun estimate(positionMs: Long, durationMs: Long, playing: Boolean,
                 speed: Float, updatedAtMs: Long, nowMs: Long): Long {
        if (positionMs < 0L) return -1L
        val elapsed = if (updatedAtMs > 0L && nowMs >= updatedAtMs) nowMs - updatedAtMs else 0L
        val advance = if (playing && speed.isFinite() && speed > 0f) elapsed.toDouble() * speed else 0.0
        val estimate = (positionMs.toDouble() + advance).toLong().coerceAtLeast(0L)
        return if (durationMs > 0L) estimate.coerceAtMost(durationMs) else estimate
    }
}
