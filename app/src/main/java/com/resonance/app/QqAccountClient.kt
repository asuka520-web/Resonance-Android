package com.resonance.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Experimental, read-only QQ web protocol adapter. Cookies are supplied in memory, never persisted here. */
object QqAccountClient {
    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8")
    suspend fun playlists(session: QqAccountSession): List<QqCataloguePlaylist> = withContext(Dispatchers.IO) {
        val id = encoded(session.userId)
        QqAccountProtocol.playlists(request("https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss?hostUin=0&hostuin=$id&sin=0&size=50&g_tk=5381&loginUin=$id&format=json&inCharset=utf8&outCharset=utf-8&platform=yqq.json&needNewCode=0", session))
    }
    suspend fun tracks(session: QqAccountSession, playlistId: String?): List<QqCatalogueTrack> =
        trackPage(session, playlistId, 0).tracks
    suspend fun trackPage(session: QqAccountSession, playlistId: String?, offset: Int): QqTrackPage = withContext(Dispatchers.IO) {
        require(offset >= 0)
        if (playlistId == null) {
            val params = JSONObject().put("disstid", 0).put("dirid", 201).put("tag", 1)
                .put("song_begin", offset).put("song_num", 50).put("userinfo", 1).put("orderlist", 1)
            val body = JSONObject().put("comm", JSONObject().put("ct", 24).put("cv", 0))
                .put("req_0", JSONObject().put("module", "music.srfDissInfo.DissInfo").put("method", "CgiGetDiss").put("param", params))
            QqAccountProtocol.trackPage(request("https://u.y.qq.com/cgi-bin/musicu.fcg", session, body), true, offset, 50)
        } else {
            require(playlistId.matches(Regex("[0-9]+")))
            QqAccountProtocol.trackPage(request("https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&utf8=1&disstid=${encoded(playlistId)}&song_begin=$offset&song_num=50&loginUin=${encoded(session.userId)}&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0", session), false, offset, 50)
        }
    }
    suspend fun songGenres(mid: String): Set<String> = withContext(Dispatchers.IO) {
        require(mid.matches(Regex("[A-Za-z0-9]{1,80}")))
        val body = JSONObject().put("comm", JSONObject().put("ct", 24).put("cv", 0))
            .put("req_0", JSONObject().put("module", "music.pf_song_detail_svr").put("method", "get_song_detail_yqq")
                .put("param", JSONObject().put("song_mid", mid).put("song_type", 0)))
        QqAccountProtocol.songGenres(request("https://u.y.qq.com/cgi-bin/musicu.fcg", null, body), mid)
    }
    suspend fun similarSongs(songId: String): List<QqCatalogueTrack> = withContext(Dispatchers.IO) {
        require(songId.matches(Regex("[0-9]{1,15}")) && songId.toLong() > 0)
        val body = JSONObject().put("comm", JSONObject().put("ct", 24).put("cv", 0))
            .put("req_0", JSONObject().put("module", "music.recommend.TrackRelationServer").put("method", "GetSimilarSongs")
                .put("param", JSONObject().put("songid", songId.toLong())))
        QqAccountProtocol.similarSongs(request("https://u.y.qq.com/cgi-bin/musicu.fcg", null, body))
    }
    suspend fun playbackUrl(session: QqAccountSession, song: QqCatalogueTrack): String = withContext(Dispatchers.IO) {
        if (session.playbackKey.isBlank()) throw QqConnectionException("已检测到网页账号态，但尚无 QQ 音乐播放授权；请在官方播放器页完成授权。")
        require(song.mid.matches(Regex("[A-Za-z0-9]{1,80}")) && song.mediaMid.matches(Regex("[A-Za-z0-9]{1,80}")))
        val params = JSONObject().put("guid", (10000000L..99999999L).random().toString())
            .put("songmid", JSONArray(listOf(song.mid))).put("songtype", JSONArray(listOf(0)))
            .put("uin", session.userId).put("loginflag", 1).put("platform", "20")
            .put("filename", JSONArray(listOf("M500${song.mediaMid}.mp3")))
        val body = JSONObject().put("comm", JSONObject().put("uin", session.userId).put("format", "json")
            .put("ct", 19).put("cv", 0).put("authst", session.playbackKey))
            .put("req_0", JSONObject().put("module", "vkey.GetVkeyServer").put("method", "CgiGetVkey").put("param", params))
        QqAccountProtocol.playbackUrl(request("https://u.y.qq.com/cgi-bin/musicu.fcg", session, body), song.mid)
    }
    private fun request(url: String, session: QqAccountSession?, body: JSONObject? = null): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Referer", "https://y.qq.com/")
            connection.setRequestProperty("User-Agent", QqAccountProtocol.WEB_USER_AGENT)
            if (session != null) connection.setRequestProperty("Cookie", session.cookieHeader)
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode != 200) throw QqConnectionException("QQ 请求未完成（HTTP ${connection.responseCode}）。")
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (output.size() + size > 2_097_152) throw QqConnectionException("曲库响应过大，已停止读取。")
                    output.write(buffer, 0, size)
                }
                output.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }
}
