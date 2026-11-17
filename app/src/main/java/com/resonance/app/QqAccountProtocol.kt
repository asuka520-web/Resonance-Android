package com.resonance.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class QqAccountSession(val userId: String, val cookieHeader: String, val playbackKey: String) {
    override fun toString() = "QqAccountSession(redacted)"
}
data class QqCataloguePlaylist(val id: String, val name: String)
data class QqCatalogueTrack(val mid: String, val mediaMid: String, val name: String,
                            val artist: String, val album: String, val durationMs: Long)
class QqConnectionException(val publicReason: String) : Exception(publicReason)

/** Independent protocol mapping; see docs/ACCOUNT_PLAYER_DIRECTION.md for reference/limitations. */
object QqAccountProtocol {
    const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    fun session(cookie: String): QqAccountSession? {
        if (cookie.contains('\r') || cookie.contains('\n')) return null
        val values = cookie.split(';').mapNotNull {
            val parts = it.trim().split('=', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
        val wechat = values["login_type"] == "2" || !values["wxopenid"].isNullOrBlank()
        val rawId = if (wechat) values["wxuin"] ?: values["uin"] else values["uin"] ?: values["qqmusic_uin"] ?: values["wxuin"] ?: values["p_uin"]
        val id = rawId.orEmpty().removePrefix("o").trimStart('0')
        if (!id.matches(Regex("[0-9]{5,20}"))) return null
        val key = listOf("qm_keyst", "qqmusic_key", "music_key", "wxskey").firstNotNullOfOrNull {
            values[it]?.takeIf(String::isNotBlank)
        }.orEmpty()
        return QqAccountSession(id, cookie, key)
    }
    fun playlists(raw: String): List<QqCataloguePlaylist> {
        val obj = JSONObject(raw)
        checkCode(obj)
        val rows = obj.optJSONObject("data")?.optJSONArray("disslist")
            ?: throw QqConnectionException("QQ 未返回歌单列表，账号接入尚未通过。")
        return (0 until rows.length()).mapNotNull {
            val item = rows.optJSONObject(it) ?: return@mapNotNull null
            val id = item.optString("dissid").ifBlank { item.optString("tid") }
            if (!id.matches(Regex("[0-9]+"))) null else QqCataloguePlaylist(id,
                item.optString("diss_name").ifBlank { item.optString("name", "未命名歌单") })
        }.distinctBy { it.id }
    }
    fun tracks(raw: String, liked: Boolean): List<QqCatalogueTrack> {
        val obj = JSONObject(raw)
        checkCode(obj)
        val container = if (liked) obj.optJSONObject("req_0")?.also(::checkCode)?.optJSONObject("data")
        else obj.optJSONArray("cdlist")?.optJSONObject(0)
        if (container?.has("code") == true) checkCode(container)
        if (container != null && container.optInt("subcode") != 0)
            throw QqConnectionException("QQ 曲库返回 ${container.optInt("subcode")}，歌曲读取尚未通过。")
        val rows = container?.optJSONArray("songlist")
            ?: throw QqConnectionException("QQ 未返回歌曲列表；请检查授权或歌单权限。")
        return parseTracks(rows)
    }
    private fun parseTracks(rows: JSONArray): List<QqCatalogueTrack> = (0 until rows.length()).mapNotNull {
        val raw = rows.optJSONObject(it) ?: return@mapNotNull null
        val song = raw.optJSONObject("track_info") ?: raw
        val mid = song.optString("mid").ifBlank { song.optString("songmid") }
        val name = song.optString("name").ifBlank { song.optString("songname") }
        if (!mid.matches(Regex("[A-Za-z0-9]{1,80}")) || name.isBlank()) return@mapNotNull null
        val singers = song.optJSONArray("singer") ?: JSONArray()
        val artist = (0 until singers.length()).mapNotNull { singers.optJSONObject(it)?.optString("name") }.joinToString(" / ")
        QqCatalogueTrack(mid, song.optJSONObject("file")?.optString("media_mid").orEmpty()
            .ifBlank { song.optString("strMediaMid") }.ifBlank { song.optString("media_mid") }.ifBlank { mid },
            name, artist, song.optJSONObject("album")?.optString("name").orEmpty().ifBlank { song.optString("albumname") },
            song.optLong("interval") * 1000L)
    }.distinctBy { it.mid }

    fun playbackUrl(raw: String, expectedMid: String): String {
        val obj = JSONObject(raw)
        checkCode(obj)
        val block = obj.optJSONObject("req_0") ?: throw QqConnectionException("QQ 未返回播放授权。")
        checkCode(block)
        val data = block.optJSONObject("data") ?: throw QqConnectionException("QQ 未返回音源资料。")
        val rows = data.optJSONArray("midurlinfo") ?: JSONArray()
        val path = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .firstOrNull { it.optString("songmid") == expectedMid && it.optString("purl").isNotBlank() }?.optString("purl")
            ?: throw QqConnectionException("QQ 未授权这首歌的播放地址，可能需要重新登录或具备相应权益。")
        val bases = data.optJSONArray("sip") ?: throw QqConnectionException("QQ 未返回播放服务器。")
        for (i in 0 until bases.length()) {
            val base = bases.optString(i).replaceFirst(Regex("^http://"), "https://")
            val url = try { URI(base).resolve(path).toString() } catch (_: Exception) { continue }
            if (allowedAudioUrl(url)) return url
        }
        throw QqConnectionException("QQ 未返回受支持的 HTTPS 音源。")
    }
    fun allowedAudioUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            uri.host?.lowercase()?.endsWith(".stream.qqmusic.qq.com") == true
    } catch (_: Exception) { false }
    fun allowedLoginUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            (uri.host == "qq.com" || uri.host?.endsWith(".qq.com") == true)
    } catch (_: Exception) { false }
    fun appearsToBeExcerpt(expectedMs: Long, actualMs: Long): Boolean = expectedMs > 40_000 &&
        actualMs > 0 && actualMs < expectedMs - 10_000
    private fun checkCode(obj: JSONObject) {
        if (obj.optInt("code", -1) != 0) throw QqConnectionException("QQ 接口返回 ${obj.optInt("code", -1)}，该项接入尚未通过。")
    }
}
