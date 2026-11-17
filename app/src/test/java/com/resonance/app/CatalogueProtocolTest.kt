package com.resonance.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogueProtocolTest {
    @Test fun favoritePagingAdvancesByRawRowsAndStopsAtTotal() {
        val raw="""{"code":0,"req_0":{"code":0,"data":{"total_song_num":52,"songlist":[{"mid":"a","name":"A"},{"mid":"b","name":"B"}]}}}"""
        val page=QqAccountProtocol.trackPage(raw,true,50,50)
        assertEquals(52,page.nextOffset);assertFalse(page.hasMore);assertEquals(2,page.tracks.size)
        val pending=QqAccountProtocol.trackPage(raw,true,0,2)
        assertTrue(pending.hasMore)
    }
    @Test(expected=QqConnectionException::class) fun repeatedPageIsNotAnInfiniteDownloadLoop() {
        val song=QqCatalogueTrack("a","a","A","artist","album",200000)
        val page=QqTrackPage(listOf(song),1,100,1,true)
        CataloguePageAccumulator().apply {append(page);append(page)}
    }
    @Test fun onlyExactSongGenreFieldIsUsedNotSingerGenresOrNumericCodes() {
        val raw="""{"code":0,"req_0":{"code":0,"data":{"track_info":{"mid":"003cSLOO35W3yP","genre":1},"info":{"genre":{"title":"歌曲流派","content":[{"id":40,"value":"Pop"},{"value":""},{"value":"123"}]},"singer":{"genre":"rock"}}}}}"""
        assertEquals(setOf("Pop"),QqAccountProtocol.songGenres(raw,"003cSLOO35W3yP"))
    }
    @Test(expected=QqConnectionException::class) fun mismatchedSongCannotAcquireAnotherSongsGenre() {
        QqAccountProtocol.songGenres("""{"code":0,"req_0":{"code":0,"data":{"track_info":{"mid":"other"},"info":{"genre":{"content":[{"value":"Pop"}]}}}}}""","target")
    }
    @Test fun parsesBothRelatedSongResponseShapesWithoutInventingCandidates() {
        val raw="""{"code":0,"req_0":{"code":0,"data":{"retcode":0,"vecSong":[{"track":{"id":123,"mid":"a","name":"A","album":{"mid":"cover"}}}],"vecSongNew":[{"songs":[{"track":{"mid":"b","name":"B"}}]}]}}}"""
        val songs=QqAccountProtocol.similarSongs(raw)
        assertEquals(listOf("a","b"),songs.map{it.mid});assertEquals("123",songs.first().songId)
        assertEquals("cover",songs.first().albumMid)
    }
    @Test fun cataloguePersistenceContainsMetadataAndSourceButNoAccountCookiesOrUrls() {
        val song=QqCatalogueTrack("a","m","A","artist","album",200000,"123","cover")
        assertEquals(song,CatalogueCodec.track(CatalogueCodec.track(song)))
        val genre=CatalogueGenre("a",setOf("pop"),"qq-song-detail","QQ 单曲流派",10,20)
        assertEquals(genre,CatalogueCodec.genres(CatalogueCodec.genres(genre)))
        val keys=CatalogueCodec.track(song).keys().asSequence().toSet()
        assertFalse("cookie" in keys);assertFalse("url" in keys);assertFalse("userId" in keys)
        assertNotEquals(CatalogueStore.scope("123456"),CatalogueStore.scope("654321"))
    }
    @Test fun favoritesAreFamiliarEvenBeforeLocalPlaybackHistoryExists() {
        val tracks=(0..12).map{StyleTrack("qq:$it","song$it","artist","qq:$it",setOf("pop"))}
        val familiar=(0..9).map{"qq:$it"}.toSet()
        val picks=StyleRecommendations.similar("qq:0",tracks,emptyList(),familiar).picks
        assertEquals(10,picks.size);assertEquals(3,picks.count{it.unfamiliar})
    }
}
