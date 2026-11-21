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
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class PlayerStatus(val mid: String = "", val playing: Boolean = false, val loading: Boolean = false,
                        val positionMs: Long = 0, val durationMs: Long = 0, val fastSkips: Int = 0,
                        val message: String = "选一首歌，开启今天的共鸣。", val excerpt: Boolean = false,
                        val playRequested: Boolean = false, val directionNotice:DirectionNotice?=null,
                        val playbackMode:String=PlaybackMode.SEQUENTIAL.name,val queueSize:Int=0,
                        val playbackRange:String=PlaybackRange.LIBRARY.name,val listeningTimeMs:Long=0,
                        val audioMeasurement: PlaybackAudioMeasurement? = null,
                        val modelGenreStatus: String = "", val modelGenreAnalyzingMid: String = "")
object PlaybackStateBus {
    var value = PlayerStatus()
        private set
    private val listeners = mutableSetOf<(PlayerStatus) -> Unit>()
    fun subscribe(listener: (PlayerStatus) -> Unit) { listeners.add(listener); listener(value) }
    fun unsubscribe(listener: (PlayerStatus) -> Unit) { listeners.remove(listener) }
    fun publish(state: PlayerStatus) { value = state; listeners.toList().forEach { it(state) } }
}

private class QqAudioResolutionException(message: String) : IOException(message)

/** Resolves signed URLs on the loader thread and hides them from session metadata and cache indexes. */
@UnstableApi
private class QqStreamDataSource(private val source: DataSource) : DataSource {
    private var originalUri: Uri? = null
    override fun addTransferListener(transferListener: TransferListener) = source.addTransferListener(transferListener)
    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.scheme != "qqtrack") throw IOException("Unsupported media source")
        val mid = uri.host ?: throw IOException("Missing track")
        val song = QqLibrary.track(mid) ?: throw QqAudioResolutionException("目标歌曲已不在当前曲库。")
        val account = QqSessionManager.active ?: throw QqAudioResolutionException("QQ 音乐账号已退出，请重新登录。")
        originalUri = uri
        val url = try { runBlocking { QqAccountClient.playbackUrl(account, song) } }
        catch (error: QqConnectionException) { throw QqAudioResolutionException(error.publicReason) }
        catch (_: IOException) { throw QqAudioResolutionException("请求 QQ 音乐音源时网络中断或超时。") }
        catch (_: Exception) { throw QqAudioResolutionException("QQ 音源资料暂时无法读取。") }
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
    private val audioProbe = PlaybackAudioProbe()
    private val playbackSettings by lazy { getSharedPreferences("resonance_settings", MODE_PRIVATE) }
    private var audioProbeEnabled = false
    private var audioProbePlan = GenreSamplePlan.PRIMARY
    private var audioProbeWork: ModelGenreWork? = null
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var modelJob: Job? = null
    private var genreModel: File? = null
    private var modelRetryAt = 0L
    private var modelGenreStatus = ""
    private var modelGenreAnalyzingMid = ""
    private var nextTransition = TrackTransition.OTHER
    private var message = "选一首歌，开启今天的共鸣。"
    private var excerpt = false
    private var historyScope = ""
    private var confirmedMid = ""
    private var automaticQueue = false
    private val noticeGate=DirectionNoticeGate()
    private var directionNotice:DirectionNotice?=null
    private var batchMids=emptyList<String>()
    private var lastRange=PlaybackRange.LIBRARY
    private var lastIntent=ListeningIntent()
    private var pendingDirection:Long?=null
    private var automaticAvoided=emptySet<String>()
    private var automaticExcluded=emptySet<String>()
    private lateinit var listeningTime:ListeningTimeCounter
    private var lastTimeSave=0L
    private var scoreEpoch=0L
    private val preferenceShuffle=PreferenceShuffle()
    private var shuffleKey:Any?=null
    private var previousRequested=false
    private fun refreshPreferenceShuffle() {
        if(!player.shuffleModeEnabled || player.mediaItemCount==0){shuffleKey=null;return}
        val mids=(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId.removePrefix("qq:")}
        val current=player.currentMediaItem?.mediaId.orEmpty().removePrefix("qq:")
        val scores=QqLibrary.state.genreScores().associate {it.label to it.combined}
        val weights=mids.associateWith {mid->
            val labels=QqLibrary.state.genres[mid]?.labels.orEmpty()
            if(labels.isEmpty())0.0 else labels.sumOf {scores[it]?:0.0}/labels.size
        }
        val key=listOf(mids,weights,QqLibrary.state.scoreEpoch,preferenceShuffle.generation)
        if(key==shuffleKey)return
        val ordered=preferenceShuffle.reorder(mids,current,weights)
        val indices=mids.withIndex().associate {it.value to it.index}
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(ordered.map {indices.getValue(it)}.toIntArray(),System.nanoTime()))
        shuffleKey=key
    }
    private fun tickListeningTime(playing:Boolean,forceSave:Boolean=false) {
        val now=SystemClock.elapsedRealtime()
        listeningTime.tick(now,playing)
        if(forceSave || !playing || now-lastTimeSave>=15000) {
            ListeningTimeStore.save(this,listeningTime.totalMs);lastTimeSave=now
        }
    }
    private fun applyScoreReset() {
        if(historyScope!=QqLibrary.state.accountScope || scoreEpoch==QqLibrary.state.scoreEpoch)return
        scoreEpoch=QqLibrary.state.scoreEpoch
        tracker.resetDirection();automaticAvoided=emptySet();automaticExcluded=emptySet();noticeGate.cancel();directionNotice=null
        pendingDirection?.let {QqLibrary.updateDirection(it,"CANCELED","标签分数已清除，取消此前的换向确认。")};pendingDirection=null
        adjustRangeQueue()
    }
    private fun selectedRange()=PlaybackRange.parse(getSharedPreferences("resonance_settings",MODE_PRIVATE).getString("qq_playback_range",PlaybackRange.LIBRARY.name).orEmpty())

    private fun updateAudioAnalysis(mid: String) {
        val diagnostics = BuildConfig.PLAYBACK_AUDIO_PROBE && playbackSettings.getBoolean(PlaybackAudioProbe.SETTING, false)
        if (audioProbeEnabled != diagnostics) audioProbe.newPlayback()
        audioProbeEnabled = diagnostics
        val automatic = playbackSettings.getBoolean(ModelGenres.SETTING, false)
        val scope = historyScope
        val song = mid.takeIf { it.isNotBlank() && scope.isNotBlank() && QqLibrary.state.accountScope == scope }
            ?.let(QqLibrary::track)
        val manual = song?.let { ModelGenres.manualRequest(this, scope, it) }
        val enabled = automatic || manual != null
        val model = genreModel
        val duration = player.duration
        val work = song?.takeIf { enabled }?.let {
            val autoEligible = automatic && QqLibrary.state.genres[mid]?.labels.orEmpty().size < 2
            ModelGenres.sampleWork(this, scope, it, duration, autoEligible)
        }
        val canSample = QqSessionManager.active != null && player.isPlaying && !excerpt && duration >= GenreSamplePlan.PRIMARY.endMs
        val sampling = work?.takeIf { model != null }
        if (audioProbeWork != sampling) audioProbe.newPlayback()
        audioProbeWork = sampling
        audioProbePlan = sampling?.plan ?: GenreSamplePlan.PRIMARY
        audioProbe.updateActive(canSample && (diagnostics || sampling != null), sampling != null, audioProbePlan)

        if (!enabled) {
            modelGenreStatus = ""
            return
        }
        if (modelJob?.isActive == true) return
        if (model == null) {
            if (SystemClock.elapsedRealtime() < modelRetryAt) return
            modelGenreStatus = "正在准备声音模型…"
            modelJob = modelScope.launch {
                try {
                    genreModel = withContext(Dispatchers.IO) { ModelGenres.ensureModel(noBackupFilesDir) }
                    modelGenreStatus = "声音模型已就绪"
                } catch (error: IOException) {
                    modelRetryAt = SystemClock.elapsedRealtime() + 60_000
                    modelGenreStatus = "模型下载暂未完成，稍后重试"
                }
            }
            return
        }
        if (modelGenreStatus.isBlank()) modelGenreStatus = "声音模型已就绪"
        if (song == null || work == null || excerpt || player.currentPosition < work.plan.endMs) return
        val clips = audioProbe.takeGenreAudio() ?: return
        modelGenreStatus = "正在识别这首歌的曲风…"
        modelGenreAnalyzingMid = mid
        modelJob = modelScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    ModelGenres.classify(model, clips)
                } catch (_: RuntimeException) {
                    null
                } catch (_: LinkageError) {
                    null
                }
            }
            modelGenreAnalyzingMid = ""
            if ((work.manual == null && !playbackSettings.getBoolean(ModelGenres.SETTING, false)) || QqLibrary.state.accountScope != scope ||
                QqSessionManager.active?.let { CatalogueStore.scope(it.userId) } != scope) return@launch
            if (result == null) {
                modelGenreStatus = "这台手机暂时无法使用声音模型"
            } else {
                val retry = result.labels.isEmpty() && work.plan == GenreSamplePlan.PRIMARY && duration >= GenreSamplePlan.LATER.endMs
                if (!ModelGenres.record(this@ResonancePlaybackService, scope, song, work, result, retry)) return@launch
                modelGenreStatus = when (result.assessment) {
                    ModelGenreAssessment.AGREEMENT -> "已补充模型推测，可在歌曲详情查看"
                    ModelGenreAssessment.INSUFFICIENT -> "片段的曲风线索不足"
                    ModelGenreAssessment.DIFFERENT -> "两个片段给出了不同曲风"
                } + if (retry) " · 播到后段会再分析一次" else ""
            }
        }
    }
    private fun adjustRangeQueue() {
        if(player.mediaItemCount==0)return
        val current=player.currentMediaItem?.mediaId.orEmpty().removePrefix("qq:")
        val range=selectedRange()
        if(lastIntent!=QqLibrary.state.intent || lastRange!=range) {
            automaticAvoided=emptySet()
            automaticExcluded=emptySet()
        }
        val pool=PlaybackQueuePolicy.pool(QqLibrary.state,range,batchMids,current).filter {
            it.mid==current || ("qq:${it.mid}" !in automaticExcluded &&
                !GenreEvidence.overlaps(QqLibrary.state.genres[it.mid]?.labels.orEmpty(),automaticAvoided))
        }
        val allowed=pool.map {it.mid}.toSet()+current
        for(index in player.mediaItemCount-1 downTo 0) {
            if(player.getMediaItemAt(index).mediaId.removePrefix("qq:") !in allowed)player.removeMediaItem(index)
        }
        val existing=(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId.removePrefix("qq:")}.toSet()
        val more=pool.filter {it.mid !in existing}.map(CatalogueMediaItems::recommendationItem)
        if(more.isNotEmpty())player.addMediaItems(more)
        automaticQueue=true
        lastRange=range;lastIntent=QqLibrary.state.intent
    }
    private val poll = object : Runnable {
        override fun run() {
            tracker.tick(SystemClock.elapsedRealtime(), player.isPlaying)
            tickListeningTime(player.isPlaying)
            val current = QqSessionManager.active
            if (current != null && historyScope != CatalogueStore.scope(current.userId)) {
                player.stop(); player.clearMediaItems(); tracker.suspend(SystemClock.elapsedRealtime())
                historyScope = CatalogueStore.scope(current.userId)
                scoreEpoch=QqLibrary.state.scoreEpoch
                noticeGate.cancel();directionNotice=null
                pendingDirection=null;automaticAvoided=emptySet();automaticExcluded=emptySet()
            }
            if (current == null && player.mediaItemCount > 0) { noticeGate.cancel();pendingDirection=null;directionNotice=null;player.stop(); player.clearMediaItems(); message = "账号已退出，播放已停止。" }
            val mid = player.currentMediaItem?.mediaId.orEmpty().removePrefix("qq:")
            updateAudioAnalysis(mid)
            applyScoreReset()
            if(lastRange!=selectedRange() || lastIntent!=QqLibrary.state.intent)adjustRangeQueue()
            refreshPreferenceShuffle()
            val confirmation=noticeGate.observe(mid,player.isPlaying,player.currentPosition,excerpt,SystemClock.elapsedRealtime())
            if(confirmation!=null) {
                val title=QqLibrary.track(mid)?.name.orEmpty()
                val prefix = if(confirmation.kind == "GENRE_HINT") directionLabel(confirmation.kind)
                    else "已"+directionLabel(confirmation.kind)
                directionNotice=confirmation.copy(message="$prefix · $title")
                pendingDirection?.let {QqLibrary.updateDirection(it,"PLAYING","已核对目标歌曲身份及连续播放进度。")};pendingDirection=null
            } else if(pendingDirection!=null && !noticeGate.pending) {
                val detail = if (excerpt) "目标只提供试听片段。" else "未在时限内确认播放，目标可能正在加载或已暂停。"
                QqLibrary.updateDirection(pendingDirection!!,"UNCONFIRMED",detail)
                pendingDirection=null;QqLibrary.feedback("尚未确认自动换向，请查看换向记录。")
            }
            if (player.isPlaying && player.currentPosition >= 1500 && confirmedMid != mid) {
                confirmedMid = mid
                message = if (excerpt) "正在播放试听片段，本次不计入偏好。" else "正在播放 · QQ 音乐"
            }
            PlaybackStateBus.publish(PlayerStatus(mid, player.isPlaying, player.playbackState == Player.STATE_BUFFERING,
                player.currentPosition.coerceAtLeast(0), QqLibrary.track(mid)?.durationMs ?: 0, tracker.fastSkips, message, excerpt, player.playWhenReady,
                directionNotice?.takeIf {SystemClock.elapsedRealtime()-it.createdAt<3500},
                PlaybackMode.fromPlayer(player.repeatMode,player.shuffleModeEnabled).name,player.mediaItemCount,selectedRange().name,listeningTime.totalMs,
                if (audioProbeEnabled && audioProbePlan == GenreSamplePlan.PRIMARY) audioProbe.snapshot() else null,
                modelGenreStatus, modelGenreAnalyzingMid))
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        val account = QqSessionManager.refresh()
        QqLibrary.activate(this, account)
        listeningTime=ListeningTimeCounter(ListeningTimeStore.totalMs(this))
        scoreEpoch=QqLibrary.state.scoreEpoch
        historyScope = account?.let { CatalogueStore.scope(it.userId) }.orEmpty()
        database = StandaloneDatabaseProvider(this)
        cache = SimpleCache(File(cacheDir, "qq-audio"), LeastRecentlyUsedCacheEvictor(100L * 1024 * 1024), database)
        val upstream = DataSource.Factory {
            QqStreamDataSource(DefaultHttpDataSource.Factory().setUserAgent(QqAccountProtocol.WEB_USER_AGENT)
                .setConnectTimeoutMs(12000).setReadTimeoutMs(12000).createDataSource())
        }
        val dataSource = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val builder = ExoPlayer.Builder(this, AudioProbeRenderers(this, audioProbe))
        player = builder.setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15000, 30000, 1500, 3000).build())
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        val savedMode=PlaybackMode.parse(getSharedPreferences("resonance_settings",MODE_PRIVATE).getString("qq_playback_mode",PlaybackMode.SEQUENTIAL.name).orEmpty())
        player.shuffleModeEnabled=savedMode==PlaybackMode.SHUFFLE
        player.repeatMode=when(savedMode){PlaybackMode.SINGLE->Player.REPEAT_MODE_ONE;PlaybackMode.LOOP,PlaybackMode.SHUFFLE->Player.REPEAT_MODE_ALL;else->Player.REPEAT_MODE_OFF}
        player.addListener(object : Player.Listener {
            private fun saveMode(){getSharedPreferences("resonance_settings",MODE_PRIVATE).edit()
                .putString("qq_playback_mode",PlaybackMode.fromPlayer(player.repeatMode,player.shuffleModeEnabled).name).apply();adjustRangeQueue()}
            override fun onRepeatModeChanged(repeatMode:Int){saveMode()}
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled:Boolean){saveMode();if(shuffleModeEnabled){preferenceShuffle.reset();shuffleKey=null;refreshPreferenceShuffle()}}
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) audioProbe.updateActive(false)
                tracker.tick(SystemClock.elapsedRealtime(), isPlaying)
                tickListeningTime(isPlaying)
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                    audioProbe.newPlayback()
                    noticeGate.resetProgress()
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                audioProbe.newPlayback()
                if (genreModel != null && modelJob?.isActive != true) modelGenreStatus = "声音模型已就绪"
                val mid = mediaItem?.mediaId?.removePrefix("qq:")
                if(mid!=null)preferenceShuffle.visit(mid,previousRequested && reason==Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                previousRequested=false
                applyScoreReset()
                if (mid != null) QqLibrary.prioritize(mid)
                val kind = if (reason in setOf(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)) TrackTransition.NATURAL else nextTransition
                if(kind==TrackTransition.USER || kind==TrackTransition.NATURAL) {
                    noticeGate.cancel()
                    pendingDirection?.let {QqLibrary.updateDirection(it,"CANCELED","被后续切歌操作取消，未宣称成功。")};pendingDirection=null
                }
                nextTransition = TrackTransition.OTHER
                tracker.directionCooldownMs = DirectionCooldown.milliseconds(getSharedPreferences("resonance_settings", MODE_PRIVATE)
                    .getInt("qq_direction_cooldown_seconds", 0))
                val transitionAt = SystemClock.elapsedRealtime()
                val event = tracker.transition(mid, QqLibrary.track(mid.orEmpty())?.durationMs ?: 0, kind, transitionAt)
                // Playback can stay active across the transition, with no onIsPlayingChanged callback.
                tracker.tick(transitionAt, player.isPlaying)
                excerpt = false; confirmedMid = ""
                message = if (mid == null) "播放队列已清空。" else "正在连接音源…"
                if(mid!=null && selectedRange()!=PlaybackRange.BATCH)adjustRangeQueue()
                if (!automaticQueue && mid != null) {
                    val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId.removePrefix("qq:") }
                    val following = PlaybackQueueWindow.following(queue, QqLibrary.state.allTracks.map { it.mid }, player.currentMediaItemIndex)
                    val items = following.mapNotNull(QqLibrary::track).map(CatalogueMediaItems::item)
                    if (items.isNotEmpty()) player.addMediaItems(items)
                }
                if (event != null && historyScope.isNotBlank()) {
                    CatalogueStore.record(this@ResonancePlaybackService, historyScope, event.mid, event.heardMs, event.durationMs, event.category, event.reason)
                    QqLibrary.refreshListening()
                    refreshPreferenceShuffle()
                    if (event.changeDirection && getSharedPreferences("resonance_settings", MODE_PRIVATE).getBoolean("qq_auto_direction", true)) {
                        val requestedScope=historyScope
                        val requestedEpoch=QqLibrary.state.scoreEpoch
                        handler.post {
                            if(historyScope!=requestedScope || QqLibrary.state.accountScope!=requestedScope ||
                                QqSessionManager.active==null || QqLibrary.state.scoreEpoch!=requestedEpoch ||
                                player.currentMediaItem?.mediaId?.removePrefix("qq:")!=mid || !player.playWhenReady)return@post
                            val range = selectedRange()
                            val plan = QqLibrary.direction(mid.orEmpty(), range)
                            val items = plan.picks.mapNotNull { QqLibrary.track(it.track.mediaId.removePrefix("qq:")) }.map(CatalogueMediaItems::recommendationItem)
                            if (items.isNotEmpty()) {
                                nextTransition = TrackTransition.AUTOMATIC
                                automaticQueue = true
                                batchMids=items.map {it.mediaId.removePrefix("qq:")}
                                automaticAvoided=plan.rejectedGenres
                                automaticExcluded=plan.directionExcludedIds
                                lastRange = range
                                lastIntent = QqLibrary.state.intent
                                val first=items.first().mediaId.removePrefix("qq:")
                                pendingDirection=QqLibrary.beginDirection(mid.orEmpty(),first,plan)
                                preferenceShuffle.reset();shuffleKey=null
                                noticeGate.request(first,QqLibrary.state.genres[first]?.labels.orEmpty(),SystemClock.elapsedRealtime(),plan.directionKind)
                                player.setMediaItems(items,0,0)
                                player.prepare()
                                player.play()
                                message = "连续快跳，已请求切换推荐方向。"
                            } else {
                                QqLibrary.beginDirection(mid.orEmpty(), null, plan)
                                QqLibrary.feedback("暂无合适的新候选，继续原队列。")
                            }
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
                    refreshPreferenceShuffle()
                    val mid = player.currentMediaItem?.mediaId.orEmpty().removePrefix("qq:")
                    if (tracker.currentMid != mid) tracker.transition(mid, QqLibrary.track(mid)?.durationMs ?: 0,
                        TrackTransition.OTHER, SystemClock.elapsedRealtime())
                    excerpt = QqAccountProtocol.appearsToBeExcerpt(QqLibrary.track(mid)?.durationMs ?: 0, player.duration)
                    tracker.setExcerpt(excerpt)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                audioProbe.newPlayback()
                noticeGate.cancel()
                pendingDirection?.let { id ->
                    val detail = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<QqAudioResolutionException>()
                        .firstOrNull()?.message ?: when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "音源连接中断或超时。"
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "音源服务器拒绝了播放请求。"
                        else -> "播放器未能加载目标歌曲。"
                    }
                    QqLibrary.updateDirection(id, "FAILED", detail)
                }
                pendingDirection=null
                QqLibrary.feedback("这首歌暂时无法播放。")
                message = "这首歌暂时无法播放，请检查账号权益或稍后重试。"
                tracker.suspend(SystemClock.elapsedRealtime())
                nextTransition = TrackTransition.OTHER
            }
        })
        val activity = PendingIntent.getActivity(this, 0, Intent(this, QqAccountActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val sessionPlayer = PreviousTrackPlayer(player, previousFromHistory = { current ->
            QqLibrary.signals().asReversed().firstNotNullOfOrNull { event ->
                if (event.mediaId == current) null else QqLibrary.track(event.mediaId.removePrefix("qq:"))
            }?.let(CatalogueMediaItems::recommendationItem)
        }, onTrackSwitch = {
            previousRequested = true
            nextTransition = TrackTransition.USER
        })
        mediaSession = MediaSession.Builder(this, sessionPlayer).setSessionActivity(activity).setCallback(object : MediaSession.Callback {
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
            override fun onSetMediaItems(session:MediaSession,controller:MediaSession.ControllerInfo,mediaItems:MutableList<MediaItem>,startIndex:Int,startPositionMs:Long):ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                return try {
                    val validated=Futures.getDone(onAddMediaItems(session,controller,mediaItems))
                    automaticAvoided=emptySet()
                    automaticExcluded=emptySet()
                    preferenceShuffle.reset();shuffleKey=null
                    batchMids=validated.map {it.mediaId.removePrefix("qq:")}
                    if(validated.isEmpty()) {
                        nextTransition=TrackTransition.OTHER
                        Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(validated,0,0))
                    } else {
                        val selected=validated.getOrElse(startIndex){validated.first()}.mediaId.removePrefix("qq:")
                        val range=selectedRange()
                        val pool=PlaybackQueuePolicy.pool(QqLibrary.state,range,batchMids,selected)
                        val initial=validated.mapNotNull {QqLibrary.track(it.mediaId.removePrefix("qq:"))}
                        val normal=validated.first().mediaMetadata.extras?.getBoolean(CatalogueMediaItems.FIXED_QUEUE)!=true
                        val expanded=if(range!=PlaybackRange.BATCH && normal) (if(pool.any {it.mid==selected})pool else initial.filter {it.mid==selected}+pool).distinctBy {it.mid}
                            else PlaybackQueuePolicy.build(initial,pool,selected)
                        val items=expanded.map(CatalogueMediaItems::recommendationItem).toMutableList()
                        automaticQueue=true;lastRange=range;lastIntent=QqLibrary.state.intent
                        Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items,expanded.indexOfFirst {it.mid==selected}.coerceAtLeast(0),startPositionMs.coerceAtLeast(0)))
                    }
                } catch(e:Exception){Futures.immediateFailedFuture(e)}
            }
            @Suppress("DEPRECATION")
            override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
                if (playerCommand in setOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_MEDIA_ITEM))
                    nextTransition = TrackTransition.USER
                return androidx.media3.session.SessionResult.RESULT_SUCCESS
            }
        }).build()
        handler.post(poll)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession
    override fun onDestroy() {
        modelScope.cancel()
        handler.removeCallbacksAndMessages(null)
        tickListeningTime(false,true)
        pendingDirection?.let {QqLibrary.updateDirection(it,"UNCONFIRMED","播放服务已停止，未确认目标播放。")};pendingDirection=null;noticeGate.cancel()
        tracker.suspend(SystemClock.elapsedRealtime())
        mediaSession?.release(); player.release(); cache.release(); database.close()
        mediaSession = null
        PlaybackStateBus.publish(PlayerStatus(message = "播放服务已停止。",listeningTimeMs=listeningTime.totalMs))
        super.onDestroy()
    }
}
