package com.resonance.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

class NetEaseAccountActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            finish()
            return
        }
        if (!profileInitialized) {
            // Separate WebView storage keeps QQ sign-out from clearing this login.
            WebView.setDataDirectorySuffix("netease")
            profileInitialized = true
        }
        enableEdgeToEdge()
        setContent {
            ResonanceTheme {
                var browser by remember { mutableStateOf<WebView?>(null) }
                var loading by remember { mutableStateOf(true) }
                var failed by remember { mutableStateOf(false) }
                SheetScaffold(::finish, rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    Modifier.fillMaxHeight(.96f)) {
                    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = Space.x4)) {
                        AccountSheetHeader("music.163.com", loading, failed, false, ::finish, {},
                            onRetry = { browser?.loadUrl(ACCOUNT_URL) }, title = "网易云音乐账号")
                        AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
                            WebView(context).also { view ->
                                browser = view
                                view.settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    userAgentString = QqAccountProtocol.WEB_USER_AGENT
                                }
                                view.webChromeClient = WebChromeClient()
                                view.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        val url = request.url
                                        if (url.scheme != "https" || url.userInfo != null || url.port !in listOf(-1, 443)) return true
                                        val host = url.host.orEmpty()
                                        return request.isForMainFrame && host != "music.163.com" && !host.endsWith(".music.163.com")
                                    }

                                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                        loading = true
                                        failed = false
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        loading = false
                                        CookieManager.getInstance().flush()
                                    }

                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                        if (request.isForMainFrame) {
                                            loading = false
                                            failed = true
                                        }
                                    }

                                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                        if (request.isForMainFrame) {
                                            loading = false
                                            failed = true
                                        }
                                    }
                                }
                                view.loadUrl(ACCOUNT_URL)
                            }
                        }, onRelease = { view ->
                            view.stopLoading()
                            CookieManager.getInstance().flush()
                            view.destroy()
                        })
                    }
                }
            }
        }
    }

    companion object {
        private const val ACCOUNT_URL = "https://music.163.com/#/my/"
        private var profileInitialized = false

        internal fun open(activity: QqAccountActivity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                activity.startActivity(Intent(activity, NetEaseAccountActivity::class.java))
            } else {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ACCOUNT_URL)))
                } catch (_: ActivityNotFoundException) {
                    QqLibrary.notice("请先安装浏览器，再打开网易云音乐登录页。")
                }
            }
        }
    }
}
