package com.resonance.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SurpriseRecommendationsTest {
    private fun track(id:String,known:Boolean=true)=StyleTrack(id,id,"artist",id,if(known)setOf("pop") else emptySet())
    @Test fun oneUnknownReplacesItsOwnFamiliarityGroupWithoutInventingGenres() {
        val tracks=(1..7).map {track("f$it")}+(1..3).map {track("n$it")}+track("unknown",false)
        val favorites=(1..7).map {"f$it"}.toSet()+"unknown"
        val base=StylePlan("similar","evidence",tracks.take(10).map {StylePick(it,"genre",it.mediaId !in favorites)})
        val plan=SurpriseRecommendations.compose(base,tracks,emptyList(),favorites,"anchor",true,"unknown")
        assertEquals(10,plan.picks.size)
        assertEquals(7,plan.picks.count {!it.unfamiliar})
        assertEquals(3,plan.picks.count {it.unfamiliar})
        assertEquals(1,plan.picks.count {it.surprise})
        assertTrue(plan.picks.single {it.surprise}.track.genres.isEmpty())
        assertFalse(plan.picks.single {it.surprise}.unfamiliar)
    }
    @Test fun excludesCurrentRecentListeningAndRecordingDuplicates() {
        val anchor=track("anchor",false)
        val alias=anchor.copy(mediaId="alias")
        val pool=listOf(anchor,alias,track("skipped",false),track("heard",false),track("fine",false),
            track("new-edition",false).copy(recordingId="skipped"))
        val events=listOf(ListeningSignal("skipped","fast"),ListeningSignal("heard","completed"))
        val plan=SurpriseRecommendations.compose(StylePlan("",""),pool,events,emptySet(),"anchor",true)
        assertEquals("fine",plan.picks.single().track.mediaId)
    }
    @Test fun atMostOneUnknownAndPreferredChoiceSurvivesBackgroundRefreshes() {
        val tracks=(1..8).map {track("u$it",false)}
        val base=StylePlan("related","QQ",tracks.map {StylePick(it,"QQ relation",true)})
        val plan=SurpriseRecommendations.compose(base,tracks,emptyList(),emptySet(),"anchor",true,"u5",Random(10))
        assertEquals(listOf("u5"),plan.picks.map {it.track.mediaId})
        assertEquals(base,SurpriseRecommendations.compose(base,tracks,emptyList(),emptySet(),"anchor",false))
    }
    @Test fun emptyPoolNeverFabricatesASongOrAStyle() {
        val plan=SurpriseRecommendations.compose(StylePlan("惊喜一首",""),listOf(track("known")),emptyList(),emptySet(),"anchor",true)
        assertTrue(plan.picks.isEmpty())
        assertTrue(plan.rejectedGenres.isEmpty())
    }

    @Test fun aDirectQqCandidateKeepsItsReasonWhenSurpriseIsEnabled() {
        val tracks = listOf(track("anchor"), track("related", false), track("random", false))
        val base = StyleRecommendations.similar("anchor", tracks, emptyList(), relatedIds = setOf("related"))
        val plan = SurpriseRecommendations.compose(base, tracks, emptyList(), emptySet(), "anchor", true, "random")

        assertEquals("related", plan.picks.single().track.mediaId)
        assertTrue(plan.picks.single().reason.contains("QQ 相关歌曲"))
        assertFalse(plan.picks.single().surprise)
    }
}
