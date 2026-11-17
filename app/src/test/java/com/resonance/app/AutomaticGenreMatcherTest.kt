package com.resonance.app

import org.junit.Assert.*
import org.junit.Test

class AutomaticGenreMatcherTest {
    private val song=SongInfo("qq:a","Song","Artist","Album")
    private fun match(id:String="a",name:String="Song",artist:String="Artist",version:String="",duration:Long=200000) =
        RecordingMatch(id,name,artist,duration,version,"Album",setOf(artist),setOf("Album"))
    @Test fun oneExactMetadataMatchIsAutomaticButWrongArtistVersionOrDurationIsNot() {
        assertNotNull(AutomaticGenreMatcher.match(song,200000,RecordingSearchResult(listOf(match()),true)).recording)
        for(recording in listOf(match(artist="Cover singer"),match(version="live"),match(duration=210000),match(name="Song remix")))
            assertNull(AutomaticGenreMatcher.match(song,200000,RecordingSearchResult(listOf(recording),true)).recording)
    }
    @Test fun twoPlausibleVersionsOrIncompleteSearchCannotBecomeAnAutomaticMapping() {
        assertNull(AutomaticGenreMatcher.match(song,200000,RecordingSearchResult(listOf(match("a"),match("b")),true)).recording)
        assertNull(AutomaticGenreMatcher.match(song,200000,RecordingSearchResult(listOf(match()),false)).recording)
    }
    @Test fun missingDurationStaysUnknownRatherThanGuessing() {
        assertNull(AutomaticGenreMatcher.match(song,0,RecordingSearchResult(listOf(match()),true)).recording)
    }
}
