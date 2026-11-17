package com.resonance.app

import org.junit.Assert.*
import org.junit.Test

class QqAccountProtocolTest {
    @Test fun accountCookieDoesNotImplyPlaybackAuthorizationAndDoesNotPrintCredentials() {
        val session = QqAccountProtocol.session("uin=o00123456; p_skey=dummy-test-value")!!
        assertEquals("123456", session.userId)
        assertEquals("", session.playbackKey)
        assertFalse(session.toString().contains("dummy-test-value"))
        assertFalse(session.toString().contains("123456"))
        assertNull(QqAccountProtocol.session("uin=123456\r\nInjected: header"))
        assertNull(QqAccountProtocol.session("uin=unknown"))
    }
    @Test fun wechatAndMusicPlaybackTicketsAreParsedWithoutClaimingMembership() {
        val session = QqAccountProtocol.session("uin=111111; login_type=2; wxuin=222222; wxskey=dummy-test-value")!!
        assertEquals("222222", session.userId)
        assertEquals("dummy-test-value", session.playbackKey)
    }
    @Test fun playlistsRequireRealSuccessfulResponseAndValidIds() {
        val rows = QqAccountProtocol.playlists("""{"code":0,"data":{"disslist":[{"dissid":"123","diss_name":"我的歌单"},{"dissid":"../bad"}]}}""")
        assertEquals(listOf(QqCataloguePlaylist("123", "我的歌单")), rows)
    }
    @Test(expected = QqConnectionException::class) fun missingLibraryIsNotAnEmptySuccess() {
        QqAccountProtocol.playlists("""{"code":0}""")
    }
    @Test fun mapsOldAndNewTrackFieldsWithoutInventingGenres() {
        val songs = QqAccountProtocol.tracks("""{"code":0,"cdlist":[{"songlist":[{"songmid":"abc123","songname":"歌曲","singer":[{"name":"歌手"}],"albumname":"专辑","interval":259,"file":{"media_mid":"file123"}}]}]}""", false)
        assertEquals(QqCatalogueTrack("abc123", "file123", "歌曲", "歌手", "专辑", 259000), songs.single())
        val liked = QqAccountProtocol.tracks("""{"code":0,"req_0":{"code":0,"data":{"songlist":[{"track_info":{"mid":"abc123","name":"歌曲"}}]}}}""", true)
        assertEquals("abc123", liked.single().mid)
    }
    @Test(expected = QqConnectionException::class) fun accountErrorIsNotTreatedAsPlaylistSuccess() {
        QqAccountProtocol.tracks("""{"code":0,"req_0":{"code":1000,"data":{"songlist":[]}}}""", true)
    }
    @Test(expected = QqConnectionException::class) fun nestedPermissionErrorIsNotAnEmptyLibrarySuccess() {
        QqAccountProtocol.tracks("""{"code":0,"req_0":{"code":0,"data":{"subcode":1000,"songlist":[]}}}""", true)
    }
    @Test fun streamMustMatchExactRequestedSongAndUseTrustedHttpsAudioHost() {
        val raw = """{"code":0,"req_0":{"code":0,"data":{"sip":["http://ws.stream.qqmusic.qq.com/"],"midurlinfo":[{"songmid":"abc","purl":"M500test.mp3?vkey=dummy"}]}}}"""
        assertEquals("https://ws.stream.qqmusic.qq.com/M500test.mp3?vkey=dummy", QqAccountProtocol.playbackUrl(raw, "abc"))
        assertFalse(QqAccountProtocol.allowedAudioUrl("https://ws.stream.qqmusic.qq.com.evil.test/file"))
        assertFalse(QqAccountProtocol.allowedAudioUrl("https://user:password@ws.stream.qqmusic.qq.com/file"))
        assertFalse(QqAccountProtocol.allowedAudioUrl("http://ws.stream.qqmusic.qq.com/file"))
        assertFalse(QqAccountProtocol.allowedLoginUrl("https://qq.com.evil.test/login"))
        assertTrue(QqAccountProtocol.allowedLoginUrl("https://xui.ptlogin2.qq.com/cgi-bin/xlogin"))
    }
    @Test(expected = QqConnectionException::class) fun unrelatedStreamIsNotAcceptedAsTheRequestedTrack() {
        QqAccountProtocol.playbackUrl("""{"code":0,"req_0":{"code":0,"data":{"sip":["https://ws.stream.qqmusic.qq.com/"],"midurlinfo":[{"songmid":"other","purl":"other.mp3"}]}}}""", "target")
    }
    @Test fun shortPreviewIsNotAFullTrackSuccessAndUnknownDurationStaysUnknown() {
        assertTrue(QqAccountProtocol.appearsToBeExcerpt(259000, 30000))
        assertFalse(QqAccountProtocol.appearsToBeExcerpt(259000, 258500))
        assertFalse(QqAccountProtocol.appearsToBeExcerpt(0, 30000))
        assertFalse(QqAccountProtocol.appearsToBeExcerpt(259000, 0))
    }
}
