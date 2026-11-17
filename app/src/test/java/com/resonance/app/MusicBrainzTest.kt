package com.resonance.app

import org.junit.Assert.*
import org.junit.Test

class MusicBrainzTest {
    private val id = "0347d102-b653-4c6c-a0b2-cc9a58c5c585"

    @Test fun searchKeepsVersionDurationAndCreditsForHumanMatching() {
        val result = MusicBrainzJson.search("""{"recordings":[{"id":"$id","title":"Get Lucky","length":294000,"disambiguation":"album version","artist-credit":[{"name":"Daft Punk","joinphrase":" feat. "},{"name":"Pharrell Williams"}],"releases":[{"title":"Random Access Memories"}]}]}""")
        val recording = result.single()
        assertEquals("Daft Punk feat. Pharrell Williams", recording.artist)
        assertEquals(294000L, recording.durationMs)
        assertEquals("album version", recording.version)
        assertEquals("Random Access Memories", recording.releases)
    }

    @Test fun onlyPositiveRecordingGenresAreUsedNotFreeFormMoodTags() {
        val result = MusicBrainzJson.genres("""{"id":"$id","genres":[{"name":"funk","count":1},{"name":"rock","count":-1}],"tags":[{"name":"happy","count":10}]}""", id)
        assertEquals(setOf("funk"), result)
        assertTrue(MusicBrainzJson.genres("""{"id":"$id"}""", id).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsWrongRecordingResponse() {
        MusicBrainzJson.genres("""{"id":"different","genres":[]}""", id)
    }

    @Test fun malformedIdsCannotBeUsedAsRequestPaths() {
        assertTrue(MusicBrainzJson.search("""{"recordings":[{"id":"../../private","title":"x"}]}""").isEmpty())
        assertFalse(MusicBrainzJson.validId("$id?extra=1"))
    }

    @Test fun searchEscapesUserTermsInsideQuotedLuceneValues() {
        assertEquals("recording:\"a\\\" OR *:*\" AND artist:\"b\\\\c\"", MusicBrainzJson.query("a\" OR *:*", "b\\c"))
    }

    @Test fun featureRoundTripAndIdentityChangeInvalidateOldMapping() {
        val feature = TrackFeatures("qq123", "歌名", "歌手", id, setOf("funk"), 1000)
        assertEquals(feature, TrackFeatureCodec.decode(TrackFeatureCodec.encode(feature).toString()))
        assertTrue(TrackFeatureCodec.matches(feature, SongInfo("qq123", "歌名", "歌手")))
        assertFalse(TrackFeatureCodec.matches(feature, SongInfo("qq123", "其他歌", "歌手")))
        assertFalse(TrackFeatureCodec.matches(feature, SongInfo("qq456", "歌名", "歌手")))
        assertFalse(TrackFeatureCodec.matches(feature, SongInfo("qq123", "歌名", "其他歌手")))
        assertFalse(TrackFeatureCodec.matches(feature, SongInfo("qq123", "歌名", "歌手", "不同专辑版本")))
    }
}
