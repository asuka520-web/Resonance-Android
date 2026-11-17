package com.resonance.app

import org.junit.Assert.*
import org.junit.Test

class PlayerImprovementsTest {
    private fun song(id:String)=QqCatalogueTrack(id,id,"歌曲$id","歌手$id","专辑",200000)
    private fun track(id:String,tags:Set<String>)=StyleTrack("qq:$id",id,id,"qq:$id",tags)
    @Test fun libraryShuffleScopeIncludesAll278FavoritesAndOutsideCandidates() {
        val songs=(0 until 278).map {song("s$it")}
        val state=LibraryState(favorites=songs,discoveries=listOf(song("outside")))
        val pool=PlaybackQueuePolicy.pool(state,PlaybackRange.LIBRARY,listOf("s0"),"s0")
        assertEquals(279,pool.size)
        assertEquals(278,PlaybackQueuePolicy.pool(state,PlaybackRange.FAVORITES,emptyList(),"s0").size)
        assertEquals(listOf("s0"),PlaybackQueuePolicy.pool(state,PlaybackRange.BATCH,listOf("s0"),"s0").map {it.mid})
        val surprise=PlaybackQueuePolicy.surprise(song("outside"),state)
        assertEquals("outside",surprise.first().mid);assertTrue(surprise.size>1)
    }
    @Test fun explicitIntentFiltersContinuationButPreservesManuallySelectedCurrentSong() {
        val state=LibraryState(favorites=listOf(song("pop"),song("jazz"),song("unknown")),
            genres=mapOf("pop" to CatalogueGenre("pop",setOf("pop"),"qq","",0,0),"jazz" to CatalogueGenre("jazz",setOf("jazz"),"qq","",0,0)),
            intent=ListeningIntent(wanted=setOf("jazz")))
        assertEquals(listOf("pop","jazz"),PlaybackQueuePolicy.pool(state,PlaybackRange.LIBRARY,emptyList(),"pop").map {it.mid})
    }
    @Test fun allKnownTagsHaveScoresAndMultiTagFeedbackIsShared() {
        val tracks=listOf(track("multi",setOf("pop","soul")),track("rock",setOf("rock")),track("zero",setOf("jazz")))
        val scores=GenrePreferenceScores.calculate(tracks,listOf(ListeningSignal("qq:multi","completed"),ListeningSignal("qq:rock","fast"))).associateBy {it.label}
        assertEquals(4,scores.size)
        assertEquals(1.0,scores.getValue("pop").recent,0.0001)
        assertEquals(1.0,scores.getValue("soul").combined,0.0001)
        assertEquals(-3.0,scores.getValue("rock").combined,0.0001)
        assertEquals(0,scores.getValue("jazz").observations)
        assertEquals(0.0,scores.getValue("jazz").combined,0.0001)
    }
    @Test fun historyUsesRecent20AndNormalizesLongTerm500() {
        val events=List(100){ListeningSignal("qq:a",if(it<80)"completed" else "fast")}
        val row=GenrePreferenceScores.calculate(listOf(track("a",setOf("pop"))),events).single()
        assertEquals(-60.0,row.recent,0.001);assertEquals(100.0,row.longTerm,0.001)
        assertEquals(-36.0,row.combined,0.001)
    }
    @Test fun scoresActuallyChangeSimilarCandidateRanking() {
        val tracks=listOf(track("anchor",setOf("pop")),track("a",setOf("pop","rock")),track("z",setOf("pop","soul")),track("liked",setOf("soul")))
        val events=listOf(ListeningSignal("qq:liked","completed"))
        val plan=StyleRecommendations.similar("qq:anchor",tracks,events,tracks.map {it.mediaId}.toSet())
        assertEquals("qq:z",plan.picks.first().track.mediaId)
    }
    @Test fun unknownGenreFallbackChangesBatchWithoutClaimingGenreOrRepeatingRecentTracks() {
        val tracks=(0..15).map {track("$it",emptySet())}
        val events=(0..3).map {ListeningSignal("qq:$it","fast")}
        val original=StylePlan("资料不足","未知曲风")
        val result=DirectionFallback.plan(original,tracks,events,emptySet(),"qq:4")
        assertEquals("BATCH",result.directionKind);assertTrue(result.rejectedGenres.isEmpty())
        assertTrue(result.picks.isNotEmpty());assertTrue(result.picks.all {it.track.mediaId !in (0..4).map {id->"qq:$id"}})
        assertEquals(original,DirectionFallback.plan(original,tracks,events.drop(1),emptySet(),"qq:4"))
    }
    @Test fun directionWaitsForAsynchronousQueueTransitionAndConfirmsExactTargetOnly() {
        val gate=DirectionNoticeGate();gate.request("target",emptySet(),0,"BATCH")
        assertNull(gate.observe("previous",true,8000,false,1000));assertTrue(gate.pending)
        assertNull(gate.observe("target",true,1000,false,2000))
        assertEquals("BATCH",gate.observe("target",true,2000,false,3000)!!.kind)
        assertFalse(gate.pending)
    }
    @Test fun directionHistoryRoundTripsEvidenceAndIsBoundedTo20() {
        val rows=(0L..24L).map {DirectionSwitch(it,it,"from","target","目标歌","BATCH","资料不足",state="PLAYING",detail="身份与进度已确认",scores=mapOf("pop" to -4.5))}
        assertEquals(rows.takeLast(20),DirectionSwitchCodec.decode(DirectionSwitchCodec.encode(rows)))
        assertTrue(DirectionSwitchCodec.decode("old-invalid").isEmpty())
    }
    @Test fun enrichmentPreservesQQAndAddsOnlyVerifiedRecordingGenres() {
        val song=song("a")
        val old=CatalogueGenre("a",setOf("pop"),"qq-song-detail","QQ",0,999)
        val id="12345678-1234-1234-1234-123456789012"
        val merged=GenreEnrichment.merge(old,song,SupplementaryGenres(setOf("pop","soul"),id),1000)
        assertEquals(setOf("pop","soul"),merged.labels)
        assertEquals(setOf("pop"),merged.providers()["qq-song-detail"])
        assertEquals(setOf("pop","soul"),merged.providers()["musicbrainz-recording"])
        assertEquals(merged,CatalogueCodec.genres(CatalogueCodec.genres(merged)))
        val failed=GenreEnrichment.merge(merged,song,null,2000,true)
        assertEquals(merged.labels,failed.labels);assertEquals(GenreLookupStatus.READY,failed.status)
        assertEquals("RETRY",failed.enrichmentState)
        val unverified=GenreEnrichment.merge(old,song,SupplementaryGenres(setOf("rock"),"invalid"),1000)
        assertEquals(setOf("pop"),unverified.labels)
    }
    @Test fun metadataChangeInvalidatesOldSupplementaryLabelsAndDuplicateLabelDoesNotInventMore() {
        val song=song("a");val id="12345678-1234-1234-1234-123456789012"
        val old=CatalogueGenre("a",setOf("pop"),"qq-song-detail","QQ",0,999)
        val same=GenreEnrichment.merge(old,song,SupplementaryGenres(setOf("pop"),id),1000)
        assertEquals(1,same.labels.size);assertTrue(same.enrichmentNote.contains("未增加"))
        val enriched=GenreEnrichment.merge(old,song,SupplementaryGenres(setOf("soul"),id),1000)
        val changed=GenreEnrichment.merge(enriched,song.copy(name="另一录音"),null,2000,true)
        assertEquals(setOf("pop"),changed.labels);assertEquals("",changed.recordingId)
    }
}
