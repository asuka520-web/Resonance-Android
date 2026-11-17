package com.resonance.app

import org.junit.Assert.*
import org.junit.Test

class DiscoveryPoolTest {
    private fun song(mid: String) = QqCatalogueTrack(mid, mid, "song-$mid", "artist", "album", 200000, "123")
    private fun genre(mid: String, label: String) = CatalogueGenre(mid, setOf(label), "qq-song-detail", "", 0, 0)

    @Test fun relationshipsSurviveRestartWithoutTransitiveOrReverseInference() {
        val pool = DiscoveryPool().add(DiscoveryBatch("a", 1000, listOf(song("b"), song("a"), song("b"))))
            .add(DiscoveryBatch("b", 2000, listOf(song("c"))))
        val saved = DiscoveryPool.decode(pool.encode())
        assertEquals(setOf("b"), saved.related("a"))
        assertEquals(setOf("c"), saved.related("b"))
        assertTrue(saved.related("c").isEmpty())
        assertEquals(setOf("b", "c"), saved.tracks.map { it.mid }.toSet())
        assertFalse(pool.encode().contains("cookie"))
    }
    @Test fun metadataCacheExpiresAndHasBoundedPersistentBatchesIncludingEmptyResponses() {
        var pool = DiscoveryPool().add(DiscoveryBatch("empty", 1000, emptyList()))
        assertTrue(pool.fresh("empty", 1001))
        assertFalse(pool.fresh("empty", 1000 + 24*60*60*1000L))
        for (i in 0..30) pool = pool.add(DiscoveryBatch("seed$i", 2000+i.toLong(), listOf(song("track$i"))))
        assertEquals(24, pool.batches.size)
        assertTrue(pool.related("seed0").isEmpty())
        assertEquals(24, DiscoveryPool.decode(pool.encode()).batches.size)
        assertTrue(DiscoveryPool.decode("bad JSON").tracks.isEmpty())
    }
    @Test fun seedChoiceSpreadsGenresExcludesRecentSkipsAndUsesPositiveFeedback() {
        val tracks = listOf("pop1", "pop2", "rock1", "jazz1", "skip", "liked").map(::song)
        val genres = listOf(genre("pop1","pop"),genre("pop2","pop"),genre("rock1","rock"),
            genre("jazz1","jazz"),genre("skip","metal"),genre("liked","pop")).associateBy {it.mid}
        val events = listOf(ListeningSignal("qq:skip","fast"),ListeningSignal("qq:liked","completed"))
        val chosen = DiscoverySeedPolicy.select(tracks, genres, events, 3).map {it.mid}
        assertEquals(listOf("liked","rock1","jazz1"), chosen)
    }
    @Test fun retryPreservesKnownGenresAndMigratesExistingUnknownRecords() {
        val ready = genre("ready","pop")
        assertEquals(ready, GenreLookupPolicy.retry(ready))
        val old = """{"mid":"old","labels":[],"source":"pending","evidence":"network error","updatedAt":1,"retryAt":999}"""
        val parsed = CatalogueCodec.genres(old)!!
        assertEquals(GenreLookupStatus.RETRY, GenreLookupPolicy.status(parsed))
        val retried = GenreLookupPolicy.retry(parsed)
        assertEquals(0L, retried.retryAt)
        assertEquals(GenreLookupStatus.WAITING, CatalogueCodec.genres(CatalogueCodec.genres(retried))!!.status)
        assertEquals(GenreLookupStatus.UNAVAILABLE, GenreLookupPolicy.status(CatalogueGenre("empty",emptySet(),"qq-song-detail","",1,999)))
    }
    @Test fun favoriteStatusCountsPartitionTheActualCollectionWithoutCountingDiscoveryTags() {
        val favorites=(0 until 278).map {song("s$it")}
        val labels=(0 until 241).associate {"s$it" to genre("s$it","pop")} + mapOf(
            "s241" to CatalogueGenre("s241",emptySet(),"pending","",0,999),
            "s242" to CatalogueGenre("s242",emptySet(),"qq-song-detail","",0,999),
            "outside" to genre("outside","rock"))
        val state=LibraryState(favorites=favorites,discoveries=listOf(song("outside")),genres=labels)
        assertEquals(241,state.taggedCount)
        assertEquals(35,state.statusCount(GenreLookupStatus.WAITING))
        assertEquals(1,state.statusCount(GenreLookupStatus.RETRY))
        assertEquals(1,state.statusCount(GenreLookupStatus.UNAVAILABLE))
        assertEquals(278,GenreLookupStatus.entries.sumOf {state.statusCount(it)})
        assertEquals(1,state.outsideCount)
    }
}
