package com.resonance.app

import org.junit.Assert.*
import org.junit.Test

class TagEnrichmentTest {
    private val id="12345678-1234-1234-1234-123456789012"
    @Test fun recordingDescriptorsRequireSupportedNamesTwoVotesAndExactRecording() {
        val raw="""{"id":"$id","tags":[{"name":"happy","count":2},{"name":"piano","count":3},{"name":"sad","count":1},{"name":"my favourites","count":99},{"name":"rock","count":3}]}"""
        assertEquals(setOf("mood:bright","instrument:piano"),RecordingDescriptors.parse(raw,id))
        assertTrue(MusicBrainzJson.genres(raw,id).isEmpty())
        assertThrows(IllegalArgumentException::class.java){RecordingDescriptors.parse(raw,"wrong")}
    }
    @Test fun descriptorsRoundTripSeparatelyWithoutPretendingTheyAreGenres() {
        val song=QqCatalogueTrack("a","a","歌曲","歌手","专辑",200000)
        val old=CatalogueGenre("a",setOf("pop"),"qq-song-detail","",0,1000)
        val enriched=GenreEnrichment.merge(old,song,SupplementaryGenres(emptySet(),id,descriptors=setOf("mood:calm")),2000)
        assertEquals(setOf("pop"),enriched.labels);assertEquals(setOf("mood:calm"),enriched.descriptors)
        assertEquals(enriched,CatalogueCodec.genres(CatalogueCodec.genres(enriched)))
        assertEquals(enriched.descriptors,GenreLookupPolicy.preserveKnown(enriched,old).descriptors)
        assertTrue(GenreEnrichment.merge(enriched,song.copy(name="另一版本"),null,3000).descriptors.isEmpty())
    }
    @Test fun synonymousChineseAndEnglishTagsDoNotFalselyIncreaseCoverage() {
        assertEquals("pop",CatalogueCodec.normalizeGenre("流行"))
        assertEquals("r&b",CatalogueCodec.normalizeGenre("节奏布鲁斯"))
        assertEquals("mandopop",CatalogueCodec.normalizeGenre("mandopop"))
    }
    @Test fun automaticEnrichmentCoversAllEligibleFavoritesAndResumesPastCachedResults() {
        val songs=(0 until 278).map {QqCatalogueTrack("s$it","s$it","歌曲$it","歌手","专辑",200000)}
        val genres=songs.associate {it.mid to CatalogueGenre(it.mid,setOf("pop"),"qq-song-detail","",0,999)}
        assertEquals(278,CatalogueEnrichmentPlan.candidates(songs,genres,null,true,1000).size)
        val cached=genres.mapValues {(_,value)->value.copy(enrichRetryAt=2000)}.toMutableMap()
        cached["s277"]=genres.getValue("s277")
        assertEquals(listOf("s277"),CatalogueEnrichmentPlan.candidates(songs,cached,null,true,1000).map {it.mid})
        assertEquals(278,CatalogueEnrichmentPlan.candidates(songs,cached,null,false,1000).size)
    }
    @Test fun manualFeedbackIsSeparateFromListeningAndAffectsVisibleAndRankingScores() {
        val track=StyleTrack("qq:a","a","artist","a",setOf("pop"))
        val score=GenrePreferenceScores.calculate(listOf(track),emptyList(),mapOf("pop" to 2.0)).single()
        assertEquals(0,score.observations);assertEquals(0.0,score.recent,0.0);assertEquals(2.0,score.combined,0.0)
        assertEquals(6.0,GenrePreferenceScores.calculate(listOf(track),emptyList(),mapOf("pop" to 100.0)).single().manual,0.0)
    }
}
