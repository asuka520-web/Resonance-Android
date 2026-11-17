package com.resonance.app

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService

data class MusicSnapshot(val title: String, val skips: Int, val analysis: String)

object MusicState {
    var autoMode: Boolean = false
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
    private var lastArtist = ""
    private var lastDisplay = "等待 QQ 音乐播放"
    private var consecutiveSkips = 0
    private var lastAnalysis = 0L
    private var analysisMessage = "等待可靠的歌曲切换信号"
    private val cooldownMs = 120_000L
    private val poll = object : Runnable {
        override fun run() {
            try { checkSession() } catch (_: Exception) {
                MusicState.publish(MusicSnapshot(lastDisplay, consecutiveSkips, "媒体读取暂时失败"))
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
            resetTracking()
            MusicState.publish(MusicSnapshot("未检测到 QQ 音乐媒体会话", 0, "等待播放"))
            return
        }
        val metadata = qq.metadata ?: return
        val state = qq.playbackState ?: return
        val now = SystemClock.elapsedRealtime()
        val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotBlank() }
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val position = if (state.position >= 0L) {
            if (state.state == PlaybackState.STATE_PLAYING)
                state.position + ((now - state.lastPositionUpdateTime).coerceAtLeast(0L) * state.playbackSpeed).toLong()
            else state.position
        } else -1L

        // QQ Music may publish the current lyric line as METADATA_KEY_TITLE.
        // A title change alone is NEVER treated as a track change.
        val idChanged = id != null && lastMediaId != null && id != lastMediaId
        val positionReset = lastPosition >= 5_000L && position in 0L..4_000L &&
            lastObservedAt > 0L && now - lastObservedAt < 10_000L &&
            lastPosition - position > 5_000L
        val supportingMetadata = duration > 0L && lastDuration > 0L &&
            (duration != lastDuration || artist != lastArtist)
        val confirmedChange = idChanged || (positionReset && supportingMetadata)

        if (confirmedChange) {
            // Evaluate the PREVIOUS track, not the newly loaded track.
            val previousWasSkipped = lastDuration > 0L && lastPosition >= 0L &&
                lastPosition < minOf(30_000L, (lastDuration * 0.3).toLong()) &&
                lastDuration - lastPosition > 10_000L
            consecutiveSkips = if (previousWasSkipped) consecutiveSkips + 1 else 0
            if (consecutiveSkips >= 4) {
                if (now - lastAnalysis >= cooldownMs) {
                    lastAnalysis = now
                    analysisMessage = "检测到 4 次疑似提前切歌；音乐风格分析尚待接入"
                    consecutiveSkips = 0
                } else analysisMessage = "已触发冷却，暂不重复分析"
            } else analysisMessage = "已确认歌曲切换，继续观察"
        } else if (id == null && !supportingMetadata) {
            analysisMessage = "QQ 音乐未提供稳定歌曲 ID；歌词变化不会计为切歌"
        }

        if (id != null) lastMediaId = id
        if (duration > 0L) lastDuration = duration
        if (artist.isNotBlank()) lastArtist = artist
        if (position >= 0L) {
            lastPosition = position
            lastObservedAt = now
        }
        // Do not show a changing lyric as the song name.
        lastDisplay = if (id != null) "媒体 ID：$id" else
            "QQ 音乐正在播放（歌曲名待可靠识别）"
        MusicState.publish(MusicSnapshot(lastDisplay, consecutiveSkips, analysisMessage))
    }

    private fun resetTracking() {
        lastMediaId = null
        lastDuration = 0L
        lastPosition = -1L
        lastObservedAt = 0L
        lastArtist = ""
        consecutiveSkips = 0
        analysisMessage = "等待播放"
    }
}
