package com.resonance.app

import android.webkit.CookieManager

object QqSessionManager {
    @Volatile var active: QqAccountSession? = null
        private set
    fun refresh(): QqAccountSession? {
        active = QqAccountProtocol.session(CookieManager.getInstance().getCookie("https://y.qq.com/").orEmpty())
        return active
    }
    fun clear() { active = null }
}
