package com.resonance.app

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import org.json.JSONArray
import org.json.JSONObject

data class MetadataDiagnostics(
    val mediaId: String = "",
    val rawTitle: String = "",
    val displayTitle: String = "",
    val artist: String = "",
    val album: String = "",
    val subtitle: String = "",
    val description: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = -1L,
    val playing: Boolean = false,
    val titleChangedWithinId: Boolean = false,
    val displayTitleChangedWithinId: Boolean = false
)

data class MusicSnapshot(
    val title: String, val skips: Int, val analysis: String,
    val fast: Int = 0, val partial: Int = 0, val completed: Int = 0,
    val diagnostics: MetadataDiagnostics = MetadataDiagnostics(),
    val historyRevision: Long = 0L,
    val metadataRevision: Long = 0L,
    val metadataStatus: String = "等待 QQ 音乐歌曲信息",
    val control: QqControlSnapshot = QqControlSnapshot()
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
    private val tracker = BehaviorTracker()
    private val metadataResolver = QqMetadataResolver()
    private var metadataRevision = 0L
    private var metadataStatus = "等待 QQ 音乐歌曲信息"
    private var activeSessionToken: MediaSession.Token? = null
    private var diagnosticMediaId: String? = null
    private var historyRevision = 0L
    private var diagnostics = MetadataDiagnostics()
    private var previousRawTitle: String? = null
    private var previousDisplayTitle: String? = null
    private var rawTitleChanged = false
    private var displayTitleChanged = false
    private val poll = object : Runnable {
        override fun run() {
            try { checkSession() } catch (_: Exception) {
                resetReading("媒体读取暂时失败，暂停行为判断")
                publish("媒体读取暂时失败")
            }
            handler.postDelayed(this, 1200L)
        }
    }
    override fun onListenerConnected() {
        super.onListenerConnected()
        resetReading("媒体监听已连接，等待歌曲切换")
        handler.removeCallbacks(poll)
        handler.post(poll)
    }
    override fun onListenerDisconnected() {
        handler.removeCallbacks(poll)
        resetReading("媒体监听已断开，未推断跳歌")
        publish("媒体监听已断开")
        super.onListenerDisconnected()
    }
    override fun onDestroy() {
        handler.removeCallbacks(poll)
        resetReading("媒体监听已停止")
        publish("媒体监听已停止")
        super.onDestroy()
    }
    private fun resetReading(reason: String) {
        QqPlayback.disconnect(reason)
        tracker.suspendObservation(reason)
        metadataResolver.reset()
        metadataStatus = reason
        activeSessionToken = null
        diagnosticMediaId = null
        previousRawTitle = null
        previousDisplayTitle = null
        rawTitleChanged = false
        displayTitleChanged = false
        diagnostics = MetadataDiagnostics()
    }
    private fun checkSession() {
        val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val qq = manager.getActiveSessions(ComponentName(this, MusicNotificationListener::class.java))
            .firstOrNull { it.packageName == "com.tencent.qqmusic" }
        if (qq == null) {
            resetReading("未检测到会话，暂停行为判断")
            publish("未检测到 QQ 音乐媒体会话")
            return
        }
        val metadata = qq.metadata
        val state = qq.playbackState
        if (metadata == null || state == null) {
            resetReading("媒体信息暂时缺失，暂停行为判断")
            publish("等待完整媒体信息")
            return
        }
        if (activeSessionToken != qq.sessionToken) {
            resetReading("媒体会话已更新，等待歌曲切换")
            activeSessionToken = qq.sessionToken
        }
        val now = SystemClock.elapsedRealtime()
        val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotBlank() }
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val displayTitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val subtitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
        val description = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION).orEmpty()
        val playing = state.state == PlaybackState.STATE_PLAYING
        val position = PlaybackPosition.estimate(
            state.position, duration, playing, state.playbackSpeed,
            state.lastPositionUpdateTime, now
        )

        // QQ Music exposes changing lyric lines in TITLE. Never use TITLE for identity.
        if (id != null && id == diagnosticMediaId) {
            if (previousRawTitle != null && previousRawTitle != rawTitle) rawTitleChanged = true
            if (previousDisplayTitle != null && previousDisplayTitle != displayTitle) displayTitleChanged = true
        } else {
            rawTitleChanged = false
            displayTitleChanged = false
        }
        previousRawTitle = rawTitle
        previousDisplayTitle = displayTitle
        diagnosticMediaId = id
        diagnostics = MetadataDiagnostics(
            mediaId = id.orEmpty(), rawTitle = rawTitle, displayTitle = displayTitle,
            artist = artist, album = album, subtitle = subtitle, description = description,
            durationMs = duration, positionMs = position,
            playing = playing,
            titleChangedWithinId = rawTitleChanged,
            displayTitleChangedWithinId = displayTitleChanged
        )
        val resolution = metadataResolver.observe(id, artist, album, now)
        metadataStatus = resolution.status
        resolution.invalidateMediaId?.let {
            if (SongInfoStore.invalidateAutomatic(this, it)) metadataRevision++
        }
        resolution.song?.let {
            if (SongInfoStore.saveAutomatic(this, it)) metadataRevision++
        }
        val controlledTransition = QqPlayback.pending
        QqPlayback.observe(qq, id, playing, now)
        val previousAnalyses = tracker.analysisCount
        if (controlledTransition) {
            tracker.suspendObservation("正在确认推荐播放，未作为负反馈")
        } else tracker.observe(PlaybackObservation(id, position, duration, playing, now))?.let { event ->
            recordEvent(event.mediaId, event.playedMs, event.durationMs, event.category)
        }
        if (tracker.analysisCount > previousAnalyses) {
            StyleSession.changeDirection(this, id.orEmpty(), automaticTrigger = true)
        }
        publish(if (id != null) "媒体 ID：$id" else "QQ 音乐正在播放（缺少稳定媒体 ID）")
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
        historyRevision++
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
        MusicState.publish(MusicSnapshot(title, tracker.consecutiveFastSkips, tracker.notice, fast, partial, completed,
            diagnostics, historyRevision, metadataRevision, metadataStatus, QqPlayback.snapshot()))
    }
}
