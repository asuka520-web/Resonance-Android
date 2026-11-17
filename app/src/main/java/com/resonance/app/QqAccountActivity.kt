package com.resonance.app

import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebStorage
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** First real-account acceptance gate: login -> actual QQ library -> native foreground audio. */
class QqAccountActivity : ComponentActivity() {
    private var nativePlayer: MediaPlayer? = null
    private var prepared = false
    private var excerpt = false
    private var playbackEpoch = 0L
    private var focusRequest: AudioFocusRequest? = null
    private var playbackNotice: (String) -> Unit = {}

    private fun session(): QqAccountSession? = QqAccountProtocol.session(
        CookieManager.getInstance().getCookie("https://y.qq.com/").orEmpty())

    private fun stopAudio() {
        playbackEpoch++
        prepared = false
        excerpt = false
        nativePlayer?.setOnPreparedListener(null)
        nativePlayer?.setOnErrorListener(null)
        nativePlayer?.release()
        nativePlayer = null
        focusRequest?.let { (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun beginAudio(url: String, epoch: Long, expectedDurationMs: Long) {
        if (epoch != playbackEpoch || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change < 0 && prepared) {
                    nativePlayer?.pause()
                    playbackNotice("其他应用取得音频焦点，已暂停。")
                }
            }.build()
        focusRequest = focus
        val player = MediaPlayer()
        nativePlayer = player
        player.setAudioAttributes(attributes)
        player.setOnPreparedListener {
            if (epoch != playbackEpoch || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@setOnPreparedListener
            prepared = true
            excerpt = QqAccountProtocol.appearsToBeExcerpt(expectedDurationMs, it.duration.toLong())
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                playbackNotice("未获得音频焦点，未开始播放。")
            } else {
                it.start()
                playbackNotice("音源已准备，等待实际播放进度确认。")
            }
        }
        player.setOnErrorListener { _, what, extra ->
            playbackNotice("音源加载或解码失败（$what / $extra），这首歌尚未通过播放验证。")
            stopAudio()
            true
        }
        player.setOnCompletionListener { playbackNotice("本首已播放结束。") }
        try {
            player.setDataSource(this, Uri.parse(url), mapOf("Referer" to "https://y.qq.com/"))
            player.prepareAsync()
        } catch (_: Exception) {
            stopAudio()
            playbackNotice("音源未能打开，尚未通过实际播放验证。")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scope = rememberCoroutineScope()
            var account by remember { mutableStateOf(session()) }
            var showLogin by remember { mutableStateOf(false) }
            var webView by remember { mutableStateOf<WebView?>(null) }
            var busy by remember { mutableStateOf(false) }
            var requestJob by remember { mutableStateOf<Job?>(null) }
            var message by remember { mutableStateOf("先登录 QQ 音乐，再读取真实歌单与歌曲。") }
            var playlists by remember { mutableStateOf<List<QqCataloguePlaylist>>(emptyList()) }
            var tracks by remember { mutableStateOf<List<QqCatalogueTrack>>(emptyList()) }
            var current by remember { mutableStateOf<QqCatalogueTrack?>(null) }
            var position by remember { mutableLongStateOf(0) }
            var confirmedEpoch by remember { mutableLongStateOf(-1) }
            playbackNotice = { message = it }
            LaunchedEffect(account?.userId) {
                requestJob?.cancel()
                stopAudio()
                busy = false
                tracks = emptyList()
                playlists = emptyList()
                current = null
                position = 0
            }
            fun load(action: suspend (QqAccountSession) -> Unit) {
                val active = session()
                account = active
                if (active == null) { message = "尚未检测到 QQ 网页账号，请先完成官方登录。"; return }
                busy = true
                requestJob = scope.launch {
                    try { action(active) }
                    catch (e: CancellationException) { throw e }
                    catch (e: QqConnectionException) { message = e.publicReason }
                    catch (_: Exception) { message = "网络或 QQ 响应暂不可用，这项接入尚未通过。" }
                    finally { busy = false }
                }
            }
            LaunchedEffect(Unit) {
                var previousPosition = 0L
                while (true) {
                    if (prepared) {
                        val player = nativePlayer
                        val now = runCatching { player?.currentPosition?.toLong() ?: 0 }.getOrDefault(0)
                        position = now
                        if (player?.isPlaying == true && now >= 1500 && now > previousPosition && confirmedEpoch != playbackEpoch) {
                            confirmedEpoch = playbackEpoch
                            message = if (excerpt) "「${current?.name.orEmpty()}」正在播放，但音源时长明显较短，可能是试听片段；不算完整歌曲播放通过。" else
                                "已观察到「${current?.name.orEmpty()}」在共鸣内实际播放，进度持续推进。"
                        }
                        previousPosition = now
                    }
                    delay(500)
                }
            }
            BackHandler(showLogin) { showLogin = false; account = session() }
            MaterialTheme {
                Scaffold { inner ->
                    Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("共鸣 · QQ 账号播放器接入验证", style = MaterialTheme.typography.titleLarge)
                        Text("本轮验证登录、真实曲库、前台实际播放。账号凭据仅由本机登录页保存，不进入记录导出。")
                        Text(if (account == null) "账号状态：未检测到网页会话" else
                            "账号状态：检测到网页会话；${if (account!!.playbackKey.isBlank()) "播放票据尚未取得" else "存在播放票据，仍需实际请求验证"}")
                        Button(enabled = !busy, onClick = {
                            showLogin = !showLogin
                            if (showLogin) stopAudio() else account = session()
                        }) {
                            Text(if (showLogin) "返回共鸣并检查账号" else "登录 QQ 音乐（官方网页）")
                        }
                        if (showLogin) {
                            Text("在下面的 QQ 官方页面完成登录。网页账号登录、歌单可读和实际播放分别验证。")
                            OutlinedButton(onClick = { webView?.loadUrl("https://y.qq.com/n/ryqq/player") }) { Text("打开官方播放器页完成播放授权") }
                            AndroidView(modifier = Modifier.fillMaxWidth().height(460.dp), factory = { context ->
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
                                                    .onFailure { message = "未能唤起 QQ，请尝试官方网页里的其他登录方式。" }
                                            } else message = "该跳转未接通，请尝试官方网页里的其他登录方式。"
                                            return true
                                        }
                                        override fun onPageFinished(view: WebView, url: String) {
                                            CookieManager.getInstance().flush()
                                            account = session()
                                        }
                                    }
                                    view.loadUrl("https://y.qq.com/n/ryqq/profile")
                                }
                            }, onRelease = { view ->
                                if (webView === view) webView = null
                                view.stopLoading()
                                view.destroy()
                            })
                        }
                        Text(message)
                        Button(enabled = !busy && !showLogin, onClick = {
                            load { active ->
                                tracks = QqAccountClient.tracks(active, null)
                                message = "已读取我的喜欢：本页 ${tracks.size} 首（最多 50 首），尚未验证音源播放。"
                            }
                        }) { Text(if (busy) "请求中…" else "读取我的喜欢") }
                        OutlinedButton(enabled = !busy && !showLogin, onClick = {
                            load { active ->
                                playlists = QqAccountClient.playlists(active)
                                message = "已读取自建歌单 ${playlists.size} 个（首批最多 50 个）。"
                            }
                        }) { Text("读取我的自建歌单") }
                        playlists.forEach { playlist ->
                            OutlinedButton(enabled = !busy, onClick = {
                                load { active ->
                                    tracks = QqAccountClient.tracks(active, playlist.id)
                                    message = "「${playlist.name}」：本页 ${tracks.size} 首（最多 50 首）。"
                                }
                            }) { Text(playlist.name) }
                        }
                        current?.let {
                            Text("共鸣播放器：${it.name} · ${it.artist}")
                            Text("播放进度：${position / 1000} 秒 / ${it.durationMs / 1000} 秒")
                            Button(enabled = prepared, onClick = {
                                val player = nativePlayer ?: return@Button
                                if (player.isPlaying) { player.pause(); message = "已暂停。" } else { player.start(); message = "继续播放，等待进度。" }
                            }) { Text("暂停 / 继续") }
                        }
                        Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            tracks.forEach { song ->
                                OutlinedButton(enabled = !busy && !showLogin, onClick = {
                                    stopAudio()
                                    val epoch = playbackEpoch
                                    current = song
                                    position = 0
                                    confirmedEpoch = -1
                                    message = "正在请求「${song.name}」的播放资源…"
                                    load { active ->
                                        val url = QqAccountClient.playbackUrl(active, song)
                                        if (epoch == playbackEpoch) beginAudio(url, epoch, song.durationMs)
                                    }
                                }) { Text("${song.name} · ${song.artist}\n${song.album} · 在共鸣播放") }
                            }
                        }
                        Text("这是接入验证版：前台单曲播放，离开页面停止。后台播放、完整队列和自动推荐待接入通过后推进。QQ 网页接入为第三方兼容实现，需真机确认。")
                        OutlinedButton(enabled = !showLogin, onClick = {
                            requestJob?.cancel()
                            stopAudio()
                            busy = true
                            current = null
                            tracks = emptyList()
                            playlists = emptyList()
                            WebStorage.getInstance().deleteAllData()
                            CookieManager.getInstance().removeAllCookies {
                                CookieManager.getInstance().flush()
                                account = null
                                busy = false
                                message = "已清除共鸣内的 QQ 网页会话。"
                            }
                        }) { Text("退出共鸣内的 QQ 登录") }
                        TextButton(onClick = { startActivity(Intent(this@QqAccountActivity, MainActivity::class.java)) }) { Text("打开旧版监听页面") }
                    }
                }
            }
        }
    }
    override fun onStop() {
        if (nativePlayer != null) playbackNotice("已离开播放页面，前台验证播放已停止。")
        stopAudio()
        super.onStop()
    }
    override fun onDestroy() { stopAudio(); playbackNotice = {}; super.onDestroy() }
}
