package com.resonance.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class QqAccountSession(val userId: String, val cookieHeader: String, val playbackKey: String) {
    override fun toString() = "QqAccountSession(redacted)"
}
data class QqCataloguePlaylist(val id: String, val name: String)
data class QqCatalogueTrack(val mid: String, val mediaMid: String, val name: String,
                            val artist: String, val album: String, val durationMs: Long,
                            val songId: String = "", val albumMid: String = "")
data class QqTrackPage(val tracks: List<QqCatalogueTrack>, val rawCount: Int, val total: Int?, val nextOffset: Int,
                     val hasMore: Boolean)
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
    fun tracks(raw: String, liked: Boolean): List<QqCatalogueTrack> = trackPage(raw, liked, 0, 50).tracks
    fun trackPage(raw: String, liked: Boolean, offset: Int, limit: Int): QqTrackPage {
        require(offset >= 0 && limit > 0)
        val obj = JSONObject(raw)
        checkCode(obj)
        val container = if (liked) obj.optJSONObject("req_0")?.also(::checkCode)?.optJSONObject("data")
        else obj.optJSONArray("cdlist")?.optJSONObject(0)
        if (container?.has("code") == true) checkCode(container)
        if (container != null && container.optInt("subcode") != 0)
            throw QqConnectionException("QQ 曲库返回 ${container.optInt("subcode")}，歌曲读取尚未通过。")
        val rows = container?.optJSONArray("songlist")
            ?: throw QqConnectionException("QQ 未返回歌曲列表；请检查授权或歌单权限。")
        val reportedTotal = listOf("total_song_num", "songnum", "song_count").firstNotNullOfOrNull { key ->
            if (container.has(key) && container.optInt(key, -1) >= 0) container.optInt(key) else null
        }
        val next = offset + rows.length()
        val total = reportedTotal?.takeIf { it >= next }
        return QqTrackPage(parseTracks(rows), rows.length(), total, next,
            rows.length() > 0 && if (total != null) next < total else rows.length() >= limit)
    }
    private fun parseTracks(rows: JSONArray): List<QqCatalogueTrack> = (0 until rows.length()).mapNotNull {
        val raw = rows.optJSONObject(it) ?: return@mapNotNull null
        val song = raw.optJSONObject("track_info") ?: raw.optJSONObject("track") ?: raw
        val mid = song.optString("mid").ifBlank { song.optString("songmid") }
        val name = song.optString("name").ifBlank { song.optString("songname") }
        if (!mid.matches(Regex("[A-Za-z0-9]{1,80}")) || name.isBlank()) return@mapNotNull null
        val singers = song.optJSONArray("singer") ?: JSONArray()
        val artist = (0 until singers.length()).mapNotNull { singers.optJSONObject(it)?.optString("name") }.joinToString(" / ")
        QqCatalogueTrack(mid, song.optJSONObject("file")?.optString("media_mid").orEmpty()
            .ifBlank { song.optString("strMediaMid") }.ifBlank { song.optString("media_mid") }.ifBlank { mid },
            name, artist, song.optJSONObject("album")?.optString("name").orEmpty().ifBlank { song.optString("albumname") },
            song.optLong("interval") * 1000L,
            song.optString("id").ifBlank { song.optString("songid") },
            song.optJSONObject("album")?.optString("mid").orEmpty().ifBlank { song.optString("albummid") })
    }.distinctBy { it.mid }

    fun similarSongs(raw: String): List<QqCatalogueTrack> {
        val obj = JSONObject(raw)
        checkCode(obj)
        val block = obj.optJSONObject("req_0") ?: throw QqConnectionException("相似候选暂不可用。")
        checkCode(block)
        val data = block.optJSONObject("data") ?: throw QqConnectionException("相似候选资料缺失。")
        if (data.optInt("retcode") != 0) throw QqConnectionException("曲库暂未提供相似候选。")
        val rows = JSONArray()
        data.optJSONArray("vecSong")?.let { source -> for (i in 0 until source.length()) rows.put(source.get(i)) }
        data.optJSONArray("vecSongNew")?.let { groups ->
            for (i in 0 until groups.length()) groups.optJSONObject(i)?.optJSONArray("songs")?.let { songs ->
                for (j in 0 until songs.length()) rows.put(songs.get(j))
            }
        }
        return parseTracks(rows).take(30)
    }

    fun songGenres(raw: String, expectedMid: String): Set<String> {
        val obj = JSONObject(raw)
        checkCode(obj)
        val block = obj.optJSONObject("req_0") ?: throw QqConnectionException("QQ 未返回单曲资料。")
        checkCode(block)
        val data = block.optJSONObject("data") ?: throw QqConnectionException("QQ 单曲资料缺失。")
        if (data.optJSONObject("track_info")?.optString("mid") != expectedMid)
            throw QqConnectionException("曲风对应的歌曲 ID 不匹配，未保存。")
        val genre = data.optJSONObject("info")?.optJSONObject("genre") ?: return emptySet()
        val rows = genre.optJSONArray("content") ?: return emptySet()
        return (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.optString("value")?.trim()
                ?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
        }.toSet()
    }

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
