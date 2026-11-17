package com.resonance.app

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock

/** A transport request is not success. Require the exact target playing on two polls. */
class PlaybackConfirmation {
    var targetId: String? = null
        private set
    private var startedAt = 0L
    private var firstPlayingAt: Long? = null
    fun start(id: String, now: Long): Boolean {
        if (targetId != null) return false
        targetId = id
        startedAt = now
        firstPlayingAt = null
        return true
    }
    fun cancel() { targetId = null; firstPlayingAt = null }
    fun observe(id: String?, playing: Boolean, now: Long): String? {
        val target = targetId ?: return null
        if (now - startedAt >= 10_000L) {
            cancel()
            return "播放请求超时：未确认目标歌曲正在播放，不会自动重试。"
        }
        if (id == target && playing) {
            val first = firstPlayingAt
            if (first != null && now - first >= 1000L) {
                cancel()
                return "已确认目标歌曲正在播放（媒体 ID：$target）。"
            }
            if (first == null) firstPlayingAt = now
        } else firstPlayingAt = null
        return null
    }
}

data class QqControlSnapshot(
    val connected: Boolean = false, val queueSize: Int = 0,
    val queuedMediaIds: Set<String> = emptySet(),
    val canPlayFromId: Boolean = false, val canSkipToQueueItem: Boolean = false,
    val pending: Boolean = false, val status: String = "等待 QQ 音乐会话；指定播放尚未经真机验证。"
) {
    fun canRequest(id: String): Boolean = connected && !pending &&
        (canPlayFromId || (canSkipToQueueItem && id in queuedMediaIds))
}

/** Uses only Android's public MediaSession APIs and IDs actually observed on this device. */
object QqPlayback {
    private var controller: MediaController? = null
    private val confirmation = PlaybackConfirmation()
    private var status = "等待指定播放测试。"
    val pending: Boolean get() = confirmation.targetId != null
    val isPlaying: Boolean get() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    fun disconnect(reason: String) {
        if (pending) status = "播放确认中断：$reason；不会自动重试。"
        controller = null
        confirmation.cancel()
    }

    fun observe(qq: MediaController, id: String?, playing: Boolean, now: Long) {
        if (controller != null && controller?.sessionToken != qq.sessionToken) disconnect("QQ 音乐会话已改变")
        controller = qq
        confirmation.observe(id, playing, now)?.let { status = it }
    }

    fun snapshot(): QqControlSnapshot {
        val qq = controller ?: return QqControlSnapshot(status = status)
        return try {
            val actions = qq.playbackState?.actions ?: 0L
            val queue = qq.queue.orEmpty()
            QqControlSnapshot(true, queue.size, queue.filter { it.queueId >= 0 }
                .mapNotNull { it.description.mediaId?.takeIf(String::isNotBlank) }.toSet(),
                actions and PlaybackState.ACTION_PLAY_FROM_MEDIA_ID != 0L,
                actions and PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM != 0L, pending, status)
        } catch (_: Exception) { QqControlSnapshot(status = "读取 QQ 音乐播放能力失败。") }
    }

    fun request(context: Context, mediaId: String): String {
        if (pending) return "已有播放请求待确认，请稍候。"
        val qq = controller ?: return "未连接 QQ 音乐，请先播放歌曲并开启通知读取权限。"
        val song = SongInfoStore.get(context, mediaId) ?: return "目标尚无本机核实的 QQ 音乐身份，未发送请求。"
        if (TrackFeatureStore.get(context, song)?.genres.isNullOrEmpty())
            return "目标的歌曲信息或曲风已变化，请重新生成推荐，未发送请求。"
        return try {
            if (qq.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) == mediaId)
                return "当前已是这首歌，请选择其他推荐。"
            val actions = qq.playbackState?.actions ?: 0L
            val queueItem = qq.queue.orEmpty().firstOrNull { it.description.mediaId == mediaId && it.queueId >= 0 }
            val inQueue = queueItem != null && actions and PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM != 0L
            if (!inQueue && actions and PlaybackState.ACTION_PLAY_FROM_MEDIA_ID == 0L)
                return "QQ 音乐未公开此目标的指定播放能力；保留推荐，请到 QQ 音乐搜索。".also { status = it }
            confirmation.start(mediaId, SystemClock.elapsedRealtime())
            if (inQueue) qq.transportControls.skipToQueueItem(queueItem!!.queueId)
            else qq.transportControls.playFromMediaId(mediaId, null)
            "已发送${if (inQueue) "队列选曲" else "媒体 ID 选曲"}请求，等待 QQ 音乐实际播放确认。".also { status = it }
        } catch (_: Exception) {
            confirmation.cancel()
            "QQ 音乐未接受播放请求；不会自动重试。".also { status = it }
        }
    }
}
