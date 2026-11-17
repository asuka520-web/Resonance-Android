package com.resonance.app

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import org.json.JSONArray
import org.json.JSONObject

data class MusicSnapshot(
    val title: String, val skips: Int, val analysis: String,
    val fast: Int = 0, val partial: Int = 0, val completed: Int = 0
)

object MusicState {
    var autoMode = false
    private var snapshot = MusicSnapshot("等待 QQ 音乐播放", 0, "尚未触发分析")
    private val listeners = mutableSetOf<(MusicSnapshot) -> Unit>()
    fun subscribe(listener: (MusicSnapshot) -> Unit) { listeners.add(listener); listener(snapshot) }
    fun unsubscribe(listener: (MusicSnapshot) -> Unit) { listeners.remove(listener) }
    fun publish(value: MusicSnapshot) {
        snapshot = value
        listeners.toList().forEach { it(value) }
    }
}

class MusicNotificationListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastMediaId: String? = null
    private var lastDuration = 0L
    private var lastPosition = -1L
    private var lastObservedAt = 0L
    private var lastDisplay = "等待 QQ 音乐播放"
    private var consecutiveFastSkips = 0
    private var lastAnalysis = 0L
    private var analysisMessage = "等待歌曲切换"
    private val cooldownMs = 120_000L
    private val poll = object : Runnable {
        override fun run() {
            try { checkSession() } catch (_: Exception) {
                MusicState.publish(MusicSnapshot(lastDisplay, consecutiveFastSkips, "媒体读取暂时失败"))
            }
            handler.postDelayed(this, 1200L)
        }
    }
    override fun onListenerConnected() {
        super.onListenerConnected()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }
    override fun onListenerDisconnected() {
        handler.removeCallbacks(poll)
        super.onListenerDisconnected()
    }
    private fun checkSession() {
        val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val qq = manager.getActiveSessions(ComponentName(this, MusicNotificationListener::class.java))
            .firstOrNull { it.packageName == "com.tencent.qqmusic" }
        if (qq == null) {
            // A temporarily missing session is not evidence of a skip.
            publish("未检测到 QQ 音乐媒体会话")
            return
        }
        val metadata = qq.metadata ?: return
        val state = qq.playbackState ?: return
        val now = SystemClock.elapsedRealtime()
        val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotBlank() }
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val position = if (state.position >= 0L) {
            if (state.state == PlaybackState.STATE_PLAYING)
                state.position + ((now - state.lastPositionUpdateTime).coerceAtLeast(0L) * state.playbackSpeed).toLong()
            else state.position
        } else -1L

        // QQ Music exposes changing lyric lines in TITLE. Never use TITLE for identity.
        if (id != null && lastMediaId != null && id != lastMediaId) {
            if (lastDuration > 0L && lastPosition >= 0L &&
                lastObservedAt > 0L && now - lastObservedAt < 15_000L) {
                val ratio = lastPosition.toDouble() / lastDuration
                val category = when {
                    ratio >= 0.70 || lastDuration - lastPosition <= 10_000L -> "completed"
                    lastPosition < 15_000L -> "fast"
                    else -> "partial"
                }
                recordEvent(lastMediaId!!, lastPosition, lastDuration, category)
                consecutiveFastSkips = if (category == "fast") consecutiveFastSkips + 1 else 0
                analysisMessage = when (category) {
                    "fast" -> "快速跳过（15 秒内）"
                    "partial" -> "中途切歌（弱负反馈）"
                    else -> "基本听完（正反馈）"
                }
                if (consecutiveFastSkips >= 4) {
                    if (now - lastAnalysis >= cooldownMs) {
                        lastAnalysis = now
                        analysisMessage = "连续 4 次快速跳过，触发重新分析（风格引擎待接入）"
                        consecutiveFastSkips = 0
                    } else analysisMessage = "连续跳过达到阈值，120 秒冷却中"
                }
            } else {
                consecutiveFastSkips = 0
                analysisMessage = "已换歌，上一首进度不足以可靠分类"
            }
            lastPosition = -1L
            lastDuration = 0L
        }
        if (id != null) {
            lastMediaId = id
            lastDisplay = "媒体 ID：$id"
        } else {
            lastDisplay = "QQ 音乐正在播放（缺少稳定媒体 ID）"
        }
        if (duration > 0L) lastDuration = duration
        if (position >= 0L) {
            lastPosition = position
            lastObservedAt = now
        }
        publish(lastDisplay)
    }
    private fun recordEvent(id: String, position: Long, duration: Long, category: String) {
        val prefs = getSharedPreferences("resonance_history", Context.MODE_PRIVATE)
        val entries = try { JSONArray(prefs.getString("events", "[]")) } catch (_: Exception) { JSONArray() }
        val event = JSONObject()
            .put("mediaId", id).put("playedMs", position).put("durationMs", duration)
            .put("category", category).put("timestamp", System.currentTimeMillis())
        entries.put(event)
        val retained = JSONArray()
        for (i in maxOf(0, entries.length() - 500) until entries.length()) retained.put(entries.get(i))
        prefs.edit().putString("events", retained.toString()).apply()
    }
    private fun publish(title: String) {
        val prefs = getSharedPreferences("resonance_history", Context.MODE_PRIVATE)
        val entries = try { JSONArray(prefs.getString("events", "[]")) } catch (_: Exception) { JSONArray() }
        var fast = 0
        var partial = 0
        var completed = 0
        for (i in 0 until entries.length()) {
            when (entries.optJSONObject(i)?.optString("category")) {
                "fast" -> fast++
                "partial" -> partial++
                "completed" -> completed++
            }
        }
        MusicState.publish(MusicSnapshot(title, consecutiveFastSkips, analysisMessage, fast, partial, completed))
    }
}
