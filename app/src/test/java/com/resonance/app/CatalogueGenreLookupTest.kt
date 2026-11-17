package com.resonance.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CatalogueGenreLookupTest {
    private val song=QqCatalogueTrack("song","song","title","artist","album",200000)
    private val recording="12345678-1234-1234-1234-123456789abc"
    private class Sources:CatalogueGenreSources {
        var qqCalls=0;var extraCalls=0
        var primary:Set<String> = emptySet()
        var failure:Exception?=null
        var extraFailure:Exception?=null
        var extra=SupplementaryGenres(emptySet())
        override suspend fun qq(mid:String):Set<String>{qqCalls++;failure?.let {throw it};return primary}
        override suspend fun supplementary(song:QqCatalogueTrack):SupplementaryGenres{extraCalls++;extraFailure?.let {throw it};return extra}
    }
    @Test fun existingPrimaryTagsNeverTriggerUnnecessarySupplementaryRequests()=runBlocking {
        val source=Sources().apply {primary=setOf("Pop","Soul")}
        val result=CatalogueGenreLookup(source).fetch(song,1000)
        assertEquals(setOf("pop","soul"),result.labels)
        assertEquals(0,source.extraCalls)
    }
    @Test fun missingPrimaryTagsAndPrimaryErrorsBothTrySupplementarySource()=runBlocking {
        for(fails in listOf(false,true)) {
            val source=Sources().apply {if(fails)failure=IllegalStateException();extra=SupplementaryGenres(setOf("Jazz"),recording,"unique match")}
            val result=CatalogueGenreLookup(source).fetch(song,1000)
            assertEquals(setOf("jazz"),result.labels)
            assertEquals("musicbrainz-recording",result.source)
            assertEquals(1,source.extraCalls)
        }
    }
    @Test fun supplementaryOnlySkipsPrimaryAndHonorsDisabledOnlineSupplement()=runBlocking {
        val source=Sources().apply {extra=SupplementaryGenres(setOf("Rock"),recording)}
        val lookup=CatalogueGenreLookup(source)
        assertEquals(setOf("rock"),lookup.fetch(song,1000,onlySupplement=true).labels)
        assertEquals(0,source.qqCalls)
        assertEquals(GenreLookupStatus.RETRY,lookup.fetch(song,1000,onlySupplement=true,supplements=false).status)
        assertEquals(1,source.extraCalls)
    }
    @Test fun unavailableIsDistinctFromTransientFailureAndUnverifiedTagsAreRejected()=runBlocking {
        val source=Sources().apply {extra=SupplementaryGenres(setOf("pop"),"")}
        val lookup=CatalogueGenreLookup(source)
        assertEquals(GenreLookupStatus.UNAVAILABLE,lookup.fetch(song,1000).status)
        source.extraFailure=IllegalStateException()
        val failed=lookup.fetch(song,1000)
        assertEquals(GenreLookupStatus.RETRY,failed.status)
        assertEquals(1000+15*60*1000L,failed.retryAt)
    }
    @Test fun cancellationDoesNotStartAnotherProvider()=runBlocking {
        val source=Sources().apply {failure=CancellationException("account changed")}
        try {CatalogueGenreLookup(source).fetch(song,1000);fail("must cancel")}
        catch(_:CancellationException){}
        assertEquals(0,source.extraCalls)
    }
    @Test fun missingFreshTagsNeverErasePreviouslyObtainedTags() {
        val old=CatalogueGenre("song",setOf("pop"),"qq-song-detail","",1,2)
        val missing=CatalogueGenre("song",emptySet(),"pending","error",100,200)
        val retained=GenreLookupPolicy.preserveKnown(old,missing)
        assertEquals(setOf("pop"),retained.labels)
        assertEquals(1L,retained.updatedAt)
        assertEquals(200L,retained.retryAt)
        assertEquals(GenreLookupStatus.READY,retained.status)
    }
}
