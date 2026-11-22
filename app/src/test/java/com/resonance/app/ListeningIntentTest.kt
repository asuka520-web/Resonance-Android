package com.resonance.app

import org.junit.Assert.*
import org.junit.Test

class ListeningIntentTest {
    private fun track(id:String,vararg tags:String)=StyleTrack(id,id,"artist",id,tags.toSet())
    @Test fun checksEveryLabelWithAnyOrAllMatchingAndExclusionPriority() {
        val any=ListeningIntent(setOf("jazz","electronic"),setOf("rock"))
        assertTrue(any.accepts(setOf("pop","jazz")))
        assertFalse(any.accepts(setOf("jazz","rock")))
        assertFalse(any.accepts(emptySet()))
        assertFalse(any.copy(matchAll=true).accepts(setOf("jazz")))
        assertTrue(any.copy(matchAll=true).accepts(setOf("jazz","electronic","pop")))
        assertTrue(ListeningIntent().accepts(emptySet()))
    }
    @Test fun exclusionOnlyAlsoRequiresEvidenceAndAliasesNormalizeWithoutGeneratingTags() {
        val intent=ListeningIntent(setOf("Hip-Hop","Pop"),setOf(" pop ")).normalized()
        assertEquals(setOf("hip hop"),intent.wanted)
        assertEquals(setOf("pop"),intent.excluded)
        assertFalse(ListeningIntent(excluded=setOf("rock")).accepts(emptySet()))
    }
    @Test fun explicitWantedTagsCanChangeDirectionAwayFromTheCurrentAnchor() {
        val songs=listOf(track("anchor","pop"),track("jazz","jazz","soul"),track("blocked","jazz","rock"),track("unknown"))
        val plan=StyleRecommendations.similar("anchor",songs,emptyList(),intent=ListeningIntent(setOf("jazz"),setOf("rock")))
        assertEquals(listOf("jazz"),plan.picks.map {it.track.mediaId})
        assertTrue(plan.picks.single().reason.contains("符合想听标签"))
    }
    @Test fun automaticDirectionUsesASpecificSharedTagInsteadOfExcludingAllPop() {
        val skipped=(1..4).map {track("skip$it","pop","r&b")}
        val songs=skipped+(1..10).map {track("pop$it","pop")}+track("new","pop","jazz")
        val plan=StyleRecommendations.changeDirection("current",songs,skipped.map {ListeningSignal(it.mediaId,"fast")},
            intent=ListeningIntent(wanted=setOf("jazz")))
        assertEquals(setOf("r&b"),plan.rejectedGenres)
        assertEquals(listOf("new"),plan.picks.map {it.track.mediaId})
    }
    @Test fun surpriseAndDiscoveryNeverBypassExplicitIntentWithUnknownLabels() {
        val intent=ListeningIntent(excluded=setOf("rock"))
        val plan=SurpriseRecommendations.compose(StylePlan("",""),listOf(track("unknown")),emptyList(),emptySet(),"anchor",true,intent=intent)
        assertTrue(plan.picks.isEmpty())
        val song=QqCatalogueTrack("unknown","unknown","unknown","artist","",200000)
        assertTrue(DiscoveryBrowser.entries(LibraryState(discoveries=listOf(song),intent=intent)).isEmpty())
    }
    @Test fun playbackModesCoverRepeatAndShuffleAndRecoverFromInvalidPreferences() {
        assertEquals(PlaybackMode.SEQUENTIAL,PlaybackMode.parse("bad value"))
        assertEquals(PlaybackMode.SHUFFLE,PlaybackMode.fromPlayer(2,true))
        assertEquals(PlaybackMode.SINGLE,PlaybackMode.fromPlayer(1,false))
        assertEquals(PlaybackMode.LOOP,PlaybackMode.fromPlayer(2,false))
        assertEquals(PlaybackMode.SINGLE,PlaybackMode.SEQUENTIAL.next())
        assertEquals(PlaybackMode.LOOP,PlaybackMode.SINGLE.next())
        assertEquals(PlaybackMode.SHUFFLE,PlaybackMode.LOOP.next())
        assertEquals(PlaybackMode.SINGLE,PlaybackMode.SHUFFLE.next())
    }
}
