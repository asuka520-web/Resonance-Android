package com.resonance.app

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

data class PlayerStatus(val mid: String = "", val playing: Boolean = false, val loading: Boolean = false,
                        val positionMs: Long = 0, val durationMs: Long = 0, val fastSkips: Int = 0,
                        val message: String = "选一首歌，开启今天的共鸣。", val excerpt: Boolean = false,
                        val playRequested: Boolean = false, val directionNotice:DirectionNotice?=null,
                        val playbackMode:String=PlaybackMode.SEQUENTIAL.name,val queueSize:Int=0)
object PlaybackStateBus {
    var value = PlayerStatus()
        private set
    private val listeners = mutableSetOf<(PlayerStatus) -> Unit>()
    fun subscribe(listener: (PlayerStatus) -> Unit) { listeners.add(listener); listener(value) }
    fun unsubscribe(listener: (PlayerStatus) -> Unit) { listeners.remove(listener) }
    fun publish(state: PlayerStatus) { value = state; listeners.toList().forEach { it(state) } }
}

/** Resolves signed URLs on the loader thread and hides them from session metadata and cache indexes. */
@UnstableApi
private class QqStreamDataSource(private val source: DataSource) : DataSource {
    private var originalUri: Uri? = null
    override fun addTransferListener(transferListener: TransferListener) = source.addTransferListener(transferListener)
    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.scheme != "qqtrack") throw IOException("Unsupported media source")
        val mid = uri.host ?: throw IOException("Missing track")
        val song = QqLibrary.track(mid) ?: throw IOException("Track no longer in catalogue")
        val account = QqSessionManager.active ?: throw IOException("Account signed out")
        originalUri = uri
        val url = try { runBlocking { QqAccountClient.playbackUrl(account, song) } }
        catch (_: Exception) { throw IOException("QQ playback authorization unavailable") }
        return source.open(dataSpec.withUri(Uri.parse(url)).withRequestHeaders(mapOf("Referer" to "https://y.qq.com/")))
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = source.read(buffer, offset, length)
    override fun getUri(): Uri? = originalUri
    override fun getResponseHeaders(): Map<String, List<String>> = source.responseHeaders
    override fun close() { source.close(); originalUri = null }
}

@UnstableApi
class ResonancePlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var cache: SimpleCache
    private lateinit var database: StandaloneDatabaseProvider
    private val handler = Handler(Looper.getMainLooper())
    private val tracker = DirectListeningTracker()
    private var nextTransition = TrackTransition.OTHER
    private var message = "选一首歌，开启今天的共鸣。"
    private var excerpt = false
    private var historyScope = ""
    private var confirmedMid = ""
    private var automaticQueue = false
    private val noticeGate=DirectionNoticeGate()
    private var directionNotice:DirectionNotice?=null
    private val poll = object : Runnable {
        override fun run() {
            tracker.tick(SystemClock.elapsedRealtime(), player.isPlaying)
            val current = QqSessionManager.active
            if (current != null && historyScope != CatalogueStore.scope(current.userId)) {
                player.stop(); player.clearMediaItems(); tracker.suspend(SystemClock.elapsedRealtime())
                historyScope = CatalogueStore.scope(current.userId)
                noticeGate.cancel();directionNotice=null
            }
            if (current == null && player.mediaItemCount > 0) { player.stop(); player.clearMediaItems(); message = "账号已退出，播放已停止。" }
            val mid = player.currentMediaItem?.mediaId.orEmpty().removePrefix("qq:")
            noticeGate.observe(mid,player.isPlaying,player.currentPosition,excerpt,SystemClock.elapsedRealtime())?.let {directionNotice=it}
            if (player.isPlaying && player.currentPosition >= 1500 && confirmedMid != mid) {
                confirmedMid = mid
                message = if (excerpt) "正在播放试听片段，本次不计入偏好。" else "正在播放 · QQ 音乐"
            }
            PlaybackStateBus.publish(PlayerStatus(mid, player.isPlaying, player.playbackState == Player.STATE_BUFFERING,
                player.currentPosition.coerceAtLeast(0), QqLibrary.track(mid)?.durationMs ?: 0, tracker.fastSkips, message, excerpt, player.playWhenReady,
                directionNotice?.takeIf {SystemClock.elapsedRealtime()-it.createdAt<3500},
                PlaybackMode.fromPlayer(player.repeatMode,player.shuffleModeEnabled).name,player.mediaItemCount))
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        val account = QqSessionManager.refresh()
        QqLibrary.activate(this, account)
        historyScope = account?.let { CatalogueStore.scope(it.userId) }.orEmpty()
        database = StandaloneDatabaseProvider(this)
        cache = SimpleCache(File(cacheDir, "qq-audio"), LeastRecentlyUsedCacheEvictor(100L * 1024 * 1024), database)
        val upstream = DataSource.Factory {
            QqStreamDataSource(DefaultHttpDataSource.Factory().setUserAgent(QqAccountProtocol.WEB_USER_AGENT)
                .setConnectTimeoutMs(12000).setReadTimeoutMs(12000).createDataSource())
        }
        val dataSource = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15000, 30000, 1500, 3000).build())
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        val savedMode=PlaybackMode.parse(getSharedPreferences("resonance_settings",MODE_PRIVATE).getString("qq_playback_mode",PlaybackMode.SEQUENTIAL.name).orEmpty())
        player.shuffleModeEnabled=savedMode==PlaybackMode.SHUFFLE
        player.repeatMode=when(savedMode){PlaybackMode.SINGLE->Player.REPEAT_MODE_ONE;PlaybackMode.LOOP,PlaybackMode.SHUFFLE->Player.REPEAT_MODE_ALL;else->Player.REPEAT_MODE_OFF}
        player.addListener(object : Player.Listener {
            private fun saveMode(){getSharedPreferences("resonance_settings",MODE_PRIVATE).edit()
                .putString("qq_playback_mode",PlaybackMode.fromPlayer(player.repeatMode,player.shuffleModeEnabled).name).apply()}
            override fun onRepeatModeChanged(repeatMode:Int){saveMode()}
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled:Boolean){saveMode()}
            override fun onIsPlayingChanged(isPlaying: Boolean) { tracker.tick(SystemClock.elapsedRealtime(), isPlaying) }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val mid = mediaItem?.mediaId?.removePrefix("qq:")
                if (mid != null) QqLibrary.prioritize(mid)
                val kind = if (reason in setOf(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)) TrackTransition.NATURAL else nextTransition
                if(kind!=TrackTransition.AUTOMATIC)noticeGate.cancel()
                nextTransition = TrackTransition.OTHER
                tracker.directionCooldownMs = DirectionCooldown.milliseconds(getSharedPreferences("resonance_settings", MODE_PRIVATE)
                    .getInt("qq_direction_cooldown_seconds", 0))
                val event = tracker.transition(mid, QqLibrary.track(mid.orEmpty())?.durationMs ?: 0, kind, SystemClock.elapsedRealtime())
                excerpt = false; confirmedMid = ""
                message = if (mid == null) "播放队列已清空。" else "正在连接音源…"
                if (!automaticQueue && mid != null) {
                    val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId.removePrefix("qq:") }
                    val following = PlaybackQueueWindow.following(queue, QqLibrary.state.allTracks.map { it.mid }, player.currentMediaItemIndex)
                    val items = following.mapNotNull(QqLibrary::track).map(CatalogueMediaItems::item)
                    if (items.isNotEmpty()) player.addMediaItems(items)
                }
                if (event != null && historyScope.isNotBlank()) {
                    CatalogueStore.record(this@ResonancePlaybackService, historyScope, event.mid, event.heardMs, event.durationMs, event.category, event.reason)
                    QqLibrary.refreshListening()
                    if (event.changeDirection && getSharedPreferences("resonance_settings", MODE_PRIVATE).getBoolean("qq_auto_direction", true)) {
                        handler.post {
                            val plan = QqLibrary.direction(mid.orEmpty())
                            val items = plan.picks.mapNotNull { QqLibrary.track(it.track.mediaId.removePrefix("qq:")) }.map(CatalogueMediaItems::recommendationItem)
                            if (items.isNotEmpty() && QqSessionManager.active != null) {
                                nextTransition = TrackTransition.AUTOMATIC
                                automaticQueue = true
                                player.setMediaItems(items,0,0)
                                player.prepare()
                                val first=items.first().mediaId.removePrefix("qq:")
                                noticeGate.request(first,QqLibrary.state.genres[first]?.labels.orEmpty(),SystemClock.elapsedRealtime())
                                message = "连续快跳，已请求切换推荐方向。"
                            } else QqLibrary.notice(plan.explanation)
                        }
                    }
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    tracker.transition(null, 0, TrackTransition.NATURAL, SystemClock.elapsedRealtime())?.let { event ->
                        if (historyScope.isNotBlank()) CatalogueStore.record(this@ResonancePlaybackService, historyScope,
                            event.mid, event.heardMs, event.durationMs, event.category, event.reason)
                        QqLibrary.refreshListening()
                    }
                    message = if (excerpt) "试听片段已结束。" else "本首播放结束。"
                }
                if (playbackState == Player.STATE_READY) {
                    val mid = player.currentMediaItem?.mediaId.orEmpty().removePrefix("qq:")
                    if (tracker.currentMid != mid) tracker.transition(mid, QqLibrary.track(mid)?.durationMs ?: 0,
                        TrackTransition.OTHER, SystemClock.elapsedRealtime())
                    excerpt = QqAccountProtocol.appearsToBeExcerpt(QqLibrary.track(mid)?.durationMs ?: 0, player.duration)
                    tracker.setExcerpt(excerpt)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                noticeGate.cancel()
                message = "这首歌暂时无法播放，请检查账号权益或稍后重试。"
                tracker.suspend(SystemClock.elapsedRealtime())
                nextTransition = TrackTransition.OTHER
            }
        })
        val activity = PendingIntent.getActivity(this, 0, Intent(this, QqAccountActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        mediaSession = MediaSession.Builder(this, player).setSessionActivity(activity).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (controller.uid != Process.myUid() && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                return super.onConnect(session, controller)
            }
            override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
                if (controller.uid != Process.myUid()) return Futures.immediateFailedFuture(SecurityException("Private catalogue"))
                QqSessionManager.refresh()
                historyScope = QqSessionManager.active?.let { CatalogueStore.scope(it.userId) }.orEmpty()
                if (historyScope.isBlank()) return Futures.immediateFailedFuture(SecurityException("Sign in required"))
                nextTransition = TrackTransition.USER
                automaticQueue = mediaItems.firstOrNull()?.mediaMetadata?.extras?.getBoolean(CatalogueMediaItems.FIXED_QUEUE) == true
                return Futures.immediateFuture(mediaItems.filter { item ->
                    val mid = item.mediaId.removePrefix("qq:")
                    item.localConfiguration?.uri?.scheme == "qqtrack" && item.localConfiguration?.uri?.host == mid && QqLibrary.track(mid) != null
                }.toMutableList())
            }
            @Suppress("DEPRECATION")
            override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
                if (playerCommand in setOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM))
                    nextTransition = TrackTransition.USER
                return androidx.media3.session.SessionResult.RESULT_SUCCESS
            }
        }).build()
        handler.post(poll)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tracker.suspend(SystemClock.elapsedRealtime())
        mediaSession?.release(); player.release(); cache.release(); database.close()
        mediaSession = null
        PlaybackStateBus.publish(PlayerStatus(message = "播放服务已停止。"))
        super.onDestroy()
    }
}
