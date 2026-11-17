package com.resonance.app

import org.junit.Assert.*
import org.junit.Test

class QqMetadataResolverTest {
    @Test fun parsesDeviceVerifiedCompositeField() {
        val song = QqCompositeArtistParser.parse("5293866", "特别的人-方大同", "危险世界")!!
        assertEquals("5293866", song.mediaId)
        assertEquals("特别的人", song.name)
        assertEquals("方大同", song.artist)
        assertEquals("危险世界", song.album)
    }

    @Test fun handlesWhitespaceAndSingleUnicodeSeparator() {
        for (separator in listOf("-", "－", "–", "—")) {
            val song = QqCompositeArtistParser.parse(" id ", " 歌曲 $separator 歌手 ", " 专辑 ")!!
            assertEquals(ParsedQqSong("id", "歌曲", "歌手", "专辑"), song)
        }
        assertEquals("", QqCompositeArtistParser.parse("id", "歌曲-歌手", "")!!.album)
    }

    @Test fun rejectsUnknownIdentityAmbiguousNamesAndCredits() {
        for (raw in listOf("", "方大同", "歌名-", "-歌手", "歌名-歌手-版本", "歌名-AC-DC",
            "曲：方大同-其他", "歌名-作词：某人", "歌词\n下一行-歌手", "歌名－歌手—其他")) {
            assertNull(raw, QqCompositeArtistParser.parse("id", raw, "专辑"))
        }
        assertNull(QqCompositeArtistParser.parse(null, "歌曲-歌手", "专辑"))
        assertNull(QqCompositeArtistParser.parse(" ", "歌曲-歌手", "专辑"))
        assertNull(QqCompositeArtistParser.parse("id", "歌曲-歌手", "专辑\n歌词"))
    }

    @Test fun requiresThreeSamplesSpanningAtLeast2400ms() {
        val resolver = QqMetadataResolver()
        assertNull(resolver.observe("id", "歌曲-歌手", "专辑", 0).song)
        assertNull(resolver.observe("id", "歌曲-歌手", "专辑", 1200).song)
        assertNull(resolver.observe("id", "歌曲-歌手", "专辑", 2399).song)
        assertEquals("歌曲", resolver.observe("id", "歌曲-歌手", "专辑", 2400).song!!.name)
    }

    @Test fun metadataSettlesBeforeCachingAfterTransition() {
        val resolver = QqMetadataResolver()
        resolver.observe("new-id", "上一首-旧歌手", "旧专辑", 0)
        assertNull(resolver.observe("new-id", "新歌-新歌手", "新专辑", 1200).song)
        assertNull(resolver.observe("new-id", "新歌-新歌手", "新专辑", 2400).song)
        assertEquals("新歌", resolver.observe("new-id", "新歌-新歌手", "新专辑", 3600).song!!.name)
    }

    @Test fun unstableIdentityInvalidatesOnlyAutomaticRecordAndBlocksUntilNewId() {
        val resolver = QqMetadataResolver()
        for (at in listOf(0L, 1200L, 2400L)) resolver.observe("a", "歌曲-歌手", "专辑", at)
        val changed = resolver.observe("a", "变化的歌词-歌手", "专辑", 3600)
        assertNull(changed.song)
        assertEquals("a", changed.invalidateMediaId)
        assertNull(resolver.observe("a", "歌曲-歌手", "专辑", 10000).song)
        assertNull(resolver.observe("b", "下一首-歌手", "专辑", 11000).song)
        resolver.observe("b", "下一首-歌手", "专辑", 12200)
        assertEquals("下一首", resolver.observe("b", "下一首-歌手", "专辑", 13400).song!!.name)
    }

    @Test fun missingCompositeDoesNotRetainAutomaticMetadata() {
        val resolver = QqMetadataResolver()
        val result = resolver.observe("id", "", "专辑", 1000)
        assertNull(result.song)
        assertEquals("id", result.invalidateMediaId)
    }

    @Test fun albumCanArriveLaterWithoutChangingIdentity() {
        val resolver = QqMetadataResolver()
        for (at in listOf(0L, 1200L, 2400L)) resolver.observe("id", "歌曲-歌手", "", at)
        assertNull(resolver.observe("id", "歌曲-歌手", "专辑", 3600).song)
        resolver.observe("id", "歌曲-歌手", "专辑", 4800)
        assertEquals("专辑", resolver.observe("id", "歌曲-歌手", "专辑", 6000).song!!.album)
    }

    @Test fun missingIdAndResetRestartObservation() {
        val resolver = QqMetadataResolver()
        resolver.observe("a", "歌曲-歌手", "", 0)
        resolver.observe("a", "歌曲-歌手", "", 1200)
        resolver.observe(null, "歌曲-歌手", "", 2000)
        assertNull(resolver.observe("a", "歌曲-歌手", "", 2400).song)
        resolver.reset()
        assertNull(resolver.observe("a", "歌曲-歌手", "", 6000).song)
    }

    @Test fun manualAndLegacyLabelsAlwaysTakePriority() {
        val auto = SongInfoResolver.encode(SongInfo("id", "自动歌名", "自动歌手", "自动专辑", "qq-session-artist", 10)).toString()
        val manual = SongInfoResolver.encode(SongInfo("id", "人工歌名", "人工歌手", savedAt = 20)).toString()
        assertEquals("人工歌名", SongInfoResolver.resolve("id", manual, "旧标签", auto)!!.name)
        val legacy = SongInfoResolver.resolve("id", null, "旧标签", auto)!!
        assertEquals("旧标签", legacy.name)
        assertEquals("legacy-manual", legacy.source)
        assertFalse(legacy.automatic)
        val resolvedAuto = SongInfoResolver.resolve("id", null, null, auto)!!
        assertTrue(resolvedAuto.automatic)
        assertEquals("自动歌名", resolvedAuto.name)
        assertEquals(resolvedAuto, SongInfoResolver.decode("id", SongInfoResolver.encode(resolvedAuto).toString()))
    }

    @Test fun corruptEntriesFallBackWithoutRewritingOriginals() {
        val broken = "{broken"
        assertEquals("旧标签", SongInfoResolver.resolve("id", broken, "旧标签", null)!!.name)
        assertNull(SongInfoResolver.resolve("id", null, null, "{\"name\":\"unknown source\"}"))
        assertNull(SongInfoResolver.decode("id", broken))
        assertEquals("{broken", broken)
    }
}
