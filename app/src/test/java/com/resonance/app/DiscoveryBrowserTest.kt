package com.resonance.app

import org.junit.Assert.*
import org.junit.Test

class DiscoveryBrowserTest {
    private fun song(mid:String,artist:String="artist")=QqCatalogueTrack(mid,mid,"title-$mid",artist,"album",200000)
    @Test fun hidesFavoritesExactRecordingAliasesAndRecentDislikes() {
        val favorite=song("favorite")
        val state=LibraryState(favorites=listOf(favorite),discoveries=listOf(favorite,song("alias"),song("skipped"),song("fine")),
            genres=listOf("favorite","alias").associateWith {CatalogueGenre(it,setOf("pop"),"musicbrainz-recording","",0,0,"recording")},
            listening=listOf(ListeningSignal("qq:skipped","fast")))
        assertEquals(listOf("fine"),DiscoveryBrowser.entries(state).map {it.song.mid})
    }
    @Test fun searchGenreAndLocalHistoryFiltersApplyTogether() {
        val state=LibraryState(discoveries=listOf(song("heard"),song("jazz"),song("other")),
            genres=mapOf("jazz" to CatalogueGenre("jazz",setOf("jazz"),"qq-song-detail","",0,0)),
            listening=listOf(ListeningSignal("qq:heard","completed")))
        assertEquals(setOf("jazz","other"),DiscoveryBrowser.entries(state,unheardOnly=true).map {it.song.mid}.toSet())
        assertEquals(listOf("jazz"),DiscoveryBrowser.entries(state,"title",true,"jazz").map {it.song.mid})
        assertTrue(DiscoveryBrowser.entries(state,"absent").isEmpty())
    }
    @Test fun spreadsArtistsAndShowsOnlyRealSourceRelationshipTitles() {
        val state=LibraryState(favorites=listOf(song("seed")),discoveries=listOf(song("a1","same"),song("a2","same"),song("b","different")),
            discoverySources=mapOf("a1" to listOf("seed")))
        val entries=DiscoveryBrowser.entries(state)
        assertEquals(listOf("a1","b","a2"),entries.map {it.song.mid})
        assertTrue(entries.first().reason.contains("title-seed"))
        assertFalse(entries.last().reason.contains("title-seed"))
    }
}
