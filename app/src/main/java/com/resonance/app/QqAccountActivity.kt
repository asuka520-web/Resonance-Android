package com.resonance.app

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/** UI owns controls only. The session service owns audio, queue, focus and background lifetime. */
@androidx.annotation.OptIn(UnstableApi::class)
class QqAccountActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private fun refreshAccount() {
        val account = QqSessionManager.refresh()
        val key = account?.let { CatalogueStore.scope(it.userId) }.orEmpty()
        if (key != QqLibrary.state.accountScope) { controller?.stop(); controller?.clearMediaItems() }
        QqLibrary.activate(this, account)
    }
    private fun play(song: QqCatalogueTrack) {
        val active = controller ?: run { QqLibrary.notice("播放器正在准备，请稍后再试。"); return }
        if (QqSessionManager.refresh() == null) { QqLibrary.notice("请先连接 QQ 音乐账号。"); return }
        val tracks = QqLibrary.state.allTracks
        val index = tracks.indexOfFirst { it.mid == song.mid }
        if (index < 0) return
        val start = (index - 10).coerceAtLeast(0)
        val queue = tracks.subList(start, minOf(start + 100, tracks.size))
        active.setMediaItems(queue.map(CatalogueMediaItems::item), index - start, 0)
        active.prepare()
        active.play()
        QqLibrary.prioritize(song.mid)
    }
    private fun playRecommendation(selectedMid: String?) {
        playBatch(QqLibrary.state.recommendation?.picks.orEmpty().map {it.track.mediaId.removePrefix("qq:")},selectedMid)
    }
    private fun playBatch(mids: List<String>, selectedMid: String?) {
        val active = controller ?: run { QqLibrary.notice("播放器正在准备，请稍后再试。"); return }
        if (QqSessionManager.refresh() == null) { QqLibrary.notice("请先连接 QQ 音乐账号。"); return }
        val songs = mids.distinct().take(100).mapNotNull(QqLibrary::track)
        if (songs.isEmpty()) { QqLibrary.notice("这批推荐尚未准备好。"); return }
        val index = selectedMid?.let { mid -> songs.indexOfFirst { it.mid == mid }.takeIf { it >= 0 } } ?: 0
        active.setMediaItems(songs.map(CatalogueMediaItems::recommendationItem), index, 0)
        active.prepare(); active.play()
        QqLibrary.prioritize(songs[index].mid)
    }
    private fun previous() {
        val active=controller?:return
        if(active.hasPreviousMediaItem()){active.seekToPreviousMediaItem();return}
        val current=active.currentMediaItem?.mediaId.orEmpty()
        val previous=QqLibrary.signals().asReversed().firstNotNullOfOrNull {event ->
            if(event.mediaId==current)null else QqLibrary.track(event.mediaId.removePrefix("qq:"))}
        if(previous==null){active.seekTo(0);QqLibrary.notice("已经是队列第一首，已回到开头。");return}
        val item=if(active.currentMediaItem?.mediaMetadata?.extras?.getBoolean(CatalogueMediaItems.FIXED_QUEUE)==true)
            CatalogueMediaItems.recommendationItem(previous) else CatalogueMediaItems.item(previous)
        active.addMediaItem(0,item);active.seekTo(0,0)
    }
    private fun setPlaybackMode(name:String) {
        val mode=PlaybackMode.parse(name)
        getSharedPreferences("resonance_settings",MODE_PRIVATE).edit().putString("qq_playback_mode",mode.name).apply()
        controller?.let {active ->
            active.shuffleModeEnabled=mode==PlaybackMode.SHUFFLE
            active.repeatMode=when(mode){PlaybackMode.SINGLE->Player.REPEAT_MODE_ONE;PlaybackMode.LOOP,PlaybackMode.SHUFFLE->Player.REPEAT_MODE_ALL;else->Player.REPEAT_MODE_OFF}
        }
    }
    @SuppressLint("SetJavaScriptEnabled")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(0xFF111610.toInt()))
        refreshAccount()
        setContent {
            var library by remember { mutableStateOf(QqLibrary.state) }
            var player by remember { mutableStateOf(PlaybackStateBus.value) }
            var showLogin by remember { mutableStateOf(false) }
            var webView by remember { mutableStateOf<WebView?>(null) }
            var loginHost by remember { mutableStateOf("y.qq.com") }
            DisposableEffect(Unit) {
                val libraryListener: (LibraryState) -> Unit = { library = it }
                val playerListener: (PlayerStatus) -> Unit = { player = it }
                QqLibrary.subscribe(libraryListener)
                PlaybackStateBus.subscribe(playerListener)
                val token = SessionToken(this@QqAccountActivity, ComponentName(this@QqAccountActivity, ResonancePlaybackService::class.java))
                val future = MediaController.Builder(this@QqAccountActivity, token).buildAsync()
                future.addListener({
                    runCatching { controller = future.get() }.onFailure { QqLibrary.notice("播放器暂未连接，请重新打开应用。") }
                }, ContextCompat.getMainExecutor(this@QqAccountActivity))
                onDispose {
                    QqLibrary.unsubscribe(libraryListener); PlaybackStateBus.unsubscribe(playerListener)
                    MediaController.releaseFuture(future); controller = null
                }
            }
            fun closeLogin() { showLogin = false; CookieManager.getInstance().flush(); refreshAccount() }
            BackHandler(showLogin) { closeLogin() }
            ResonanceScreen(library, player,
                onLogin = { controller?.pause(); showLogin = true }, onSync = { QqLibrary.syncFavorites() }, onPlay = ::play,
                onToggle = {
                    controller?.let {
                        if (it.playbackState == Player.STATE_IDLE && it.mediaItemCount > 0) { it.prepare(); it.play() }
                        else if (it.isPlaying || it.playWhenReady) it.pause() else {
                            if (it.playbackState == Player.STATE_ENDED) it.seekToDefaultPosition()
                            it.prepare(); it.play()
                        }
                    }
                }, onNext = { controller?.seekToNextMediaItem() }, onPrevious = ::previous,
                onSeek = { fraction -> controller?.seekTo((player.durationMs * fraction).toLong()) },
                onLogout = {
                    controller?.stop(); controller?.clearMediaItems(); QqSessionManager.clear()
                    QqLibrary.activate(this@QqAccountActivity, null)
                    WebStorage.getInstance().deleteAllData()
                    CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
                }, onLegacy = { startActivity(Intent(this@QqAccountActivity, MainActivity::class.java)) },
                onPlaylists = { QqLibrary.loadPlaylists() }, onPlaylist = { QqLibrary.openPlaylist(it) },
                onPlayRecommendation = ::playRecommendation, onPlayDiscovery = ::playBatch,
                onSurprise={if(QqLibrary.surprise(PlaybackStateBus.value.mid).picks.isNotEmpty())playRecommendation(null)},onPlaybackMode=::setPlaybackMode)
            if (showLogin) ResonanceTheme {
                ModalBottomSheet(onDismissRequest = { closeLogin() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    modifier = Modifier.fillMaxHeight(.96f)) {
                    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("QQ 音乐账号", style = MaterialTheme.typography.titleLarge)
                            TextButton(onClick = { closeLogin() }) { Text("完成") }
                        }
                        Text("官方页面 · $loginHost", fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
                        if (QqSessionManager.active?.playbackKey.isNullOrBlank()) {
                            TextButton(onClick = { webView?.loadUrl("https://y.qq.com/n/ryqq/player") }) { Text("打开官方播放器页继续授权") }
                        }
                        AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
                            WebView(context).also { view ->
                                webView = view
                                view.settings.javaScriptEnabled = true
                                view.settings.domStorageEnabled = true
                                view.settings.allowFileAccess = false
                                view.settings.allowContentAccess = false
                                view.settings.useWideViewPort = true
                                view.settings.loadWithOverviewMode = true
                                view.settings.setSupportZoom(true)
                                view.settings.builtInZoomControls = true
                                view.settings.displayZoomControls = false
                                view.settings.userAgentString = QqAccountProtocol.WEB_USER_AGENT
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                                view.webChromeClient = WebChromeClient()
                                view.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        if (QqAccountProtocol.allowedLoginUrl(request.url.toString())) return false
                                        if (request.hasGesture() && request.url.scheme in setOf("mqqapi", "mqqopensdkapi")) {
                                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url).setPackage("com.tencent.mobileqq")) }
                                                .onFailure { QqLibrary.notice("未能唤起 QQ，请尝试其他网页登录方式。") }
                                        }
                                        return true
                                    }
                                    override fun onPageFinished(view: WebView, url: String) {
                                        loginHost = android.net.Uri.parse(url).host.orEmpty()
                                        CookieManager.getInstance().flush()
                                        QqSessionManager.refresh()
                                    }
                                }
                                view.loadUrl("https://y.qq.com/n/ryqq/profile")
                            }
                        }, onRelease = { view -> if (webView === view) webView = null; view.stopLoading(); view.destroy() })
                    }
                }
            }
        }
    }
    override fun onResume() { super.onResume(); refreshAccount() }
}
