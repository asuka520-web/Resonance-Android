package com.resonance.app

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.content.ComponentName
import android.os.Handler
import android.os.Looper

data class MusicSnapshot(val title: String, val skips: Int, val analysis: String)

object MusicState {
    var autoMode: Boolean = false
    private var snapshot = MusicSnapshot("等待 QQ 音乐播放", 0, "尚未触发分析")
    private val listeners = mutableSetOf<(MusicSnapshot) -> Unit>()
    fun subscribe(listener: (MusicSnapshot) -> Unit) { listeners.add(listener); listener(snapshot) }
    fun unsubscribe(listener: (MusicSnapshot) -> Unit) { listeners.remove(listener) }
    fun publish(value: MusicSnapshot) { snapshot = value; listeners.toList().forEach { it(value) } }
}

class MusicNotificationListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var lastKey = ""
    private var lastStart = 0L
    private var consecutiveSkips = 0
    private var lastAnalysis = 0L
    private val cooldownMs = 120_000L
    private val poll = object : Runnable {
        override fun run() {
            try { checkSession() } catch (_: Exception) { }
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
        val sessions = manager.getActiveSessions(ComponentName(this, MusicNotificationListener::class.java))
        val qq = sessions.firstOrNull { it.packageName == "com.tencent.qqmusic" }
        if (qq == null) {
            MusicState.publish(MusicSnapshot("未检测到 QQ 音乐媒体会话", consecutiveSkips, "等待播放"))
            return
        }
        controller = qq
        val metadata = qq.metadata ?: return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val key = "$title|$artist"
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastKey.isNotEmpty() && lastKey != key) {
            val elapsed = now - lastStart
            val skipWindow = if (duration > 0) (duration * 0.3).toLong() else 30_000L
            if (elapsed in 1000L..minOf(skipWindow, 30_000L)) consecutiveSkips++
            else consecutiveSkips = 0
        }
        if (lastKey != key) { lastKey = key; lastStart = now }
        var analysis = "观察当前听歌行为"
        if (consecutiveSkips >= 4 && now - lastAnalysis >= cooldownMs) {
            lastAnalysis = now
            consecutiveSkips = 0
            analysis = "检测到连续跳过 4 首，建议重新选择风格（曲库分析待接入）"
        }
        MusicState.publish(MusicSnapshot("$title — $artist", consecutiveSkips, analysis))
    }
}
