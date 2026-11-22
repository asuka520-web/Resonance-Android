package com.resonance.app

import org.junit.Assert.*
import org.junit.Test

class StyleRecommendationsTest {
    private fun song(id: String, vararg genres: String, artist: String = "artist", recording: String = id) =
        StyleTrack(id, "song-$id", artist, recording, genres.toSet())

    @Test fun similarRequiresGenreOverlapAndNeverUsesArtistAsGenre() {
        val tracks = listOf(song("anchor", "funk"), song("same-artist", "metal"),
            song("different-artist", "funk", artist = "someone else"), song("unknown"))
        val plan = StyleRecommendations.similar("anchor", tracks, emptyList())
        assertEquals(listOf("different-artist"), plan.picks.map { it.track.mediaId })
        assertTrue(plan.picks.single().reason.contains("funk"))
    }

    @Test fun unknownAnchorIsBlockedRatherThanInferred() {
        assertTrue(StyleRecommendations.similar("anchor", listOf(song("anchor"), song("other", "pop")), emptyList()).picks.isEmpty())
    }
    @Test fun unknownGenreUsesOnlyExplicitQqRelationsWithoutFabricatingLabels() {
        val tracks=listOf(song("anchor"),song("related"),song("unrelated"),song("tagged","pop"))
        val plan=StyleRecommendations.similar("anchor",tracks,emptyList(),relatedIds=setOf("related"))
        assertEquals(listOf("related"),plan.picks.map {it.track.mediaId})
        assertTrue(plan.picks.single().track.genres.isEmpty())
        assertTrue(plan.picks.single().reason.contains("QQ 相关歌曲"))
        assertFalse(plan.picks.single().reason.contains("共同曲风"))
    }
    @Test fun relatedFallbackDoesNotOverrideKnownGenreConflictOrRecentDislikes() {
        val tracks=listOf(song("anchor","pop"),song("unknown"),song("conflict","metal"),song("rejected"),song("good","pop"))
        val plan=StyleRecommendations.similar("anchor",tracks,listOf(ListeningSignal("rejected","fast")),
            relatedIds=setOf("unknown","conflict","rejected"))
        assertEquals(setOf("unknown","good"),plan.picks.map {it.track.mediaId}.toSet())
    }
    @Test fun directionChangeNeverUsesUnknownGenreAsAnOppositeDirection() {
        val skipped=(1..4).map {song("skip$it","pop")}
        val events=skipped.map {ListeningSignal(it.mediaId,"fast")}
        val plan=StyleRecommendations.changeDirection("current",skipped+listOf(song("unknown"),song("other","rock")),events)
        assertEquals(listOf("other"),plan.picks.map {it.track.mediaId})
    }

    @Test fun excludesCurrentRecordingDuplicatesAndRecentNegativeSongs() {
        val tracks = listOf(song("anchor", "pop"), song("alias", "pop", recording = "anchor"),
            song("rejected", "pop"), song("fine", "pop"), song("fine-alias", "pop", recording = "fine"))
        val plan = StyleRecommendations.similar("anchor", tracks, listOf(ListeningSignal("rejected", "fast")))
        assertEquals(1, plan.picks.size)
        assertEquals("fine", plan.picks.single().track.recordingId)
    }

    @Test fun ranksStrongerGenreOverlapAbovePartialOverlap() {
        val tracks = listOf(song("a", "funk", "soul"), song("partial", "soul", "rock"), song("close", "funk", "soul"))
        assertEquals("close", StyleRecommendations.similar("a", tracks, emptyList()).picks.first().track.mediaId)
    }

    @Test fun subgenreMatchLeadsEvenWhenABroadMatchIsAFavorite() {
        val tracks = listOf(song("anchor", "pop", "alternative r&b"), song("familiar", "pop"),
            song("closer", "contemporary r&b"))
        val history = List(12) { ListeningSignal("familiar", "completed") }

        val plan = StyleRecommendations.similar("anchor", tracks, history)

        assertEquals(listOf("closer", "familiar"), plan.picks.map { it.track.mediaId })
        assertTrue(plan.picks.first().reason.contains("r&b"))
        assertTrue(plan.picks.first().unfamiliar)
    }

    @Test fun aRecentlyRejectedRecordingDoesNotReturnUnderAnotherTrackId() {
        val tracks = listOf(song("anchor", "pop"), song("skipped", "pop", recording = "same"),
            song("another-edition", "pop", recording = "same"), song("different", "pop"))
        val plan = StyleRecommendations.similar("anchor", tracks, listOf(ListeningSignal("skipped", "fast")))
        assertEquals(listOf("different"), plan.picks.map { it.track.mediaId })
    }

    @Test fun usesSevenThreeMixWhenEnoughActualCandidatesExist() {
        val tracks = listOf(song("anchor", "pop")) + (1..14).map { song("known$it", "pop") } +
            (1..6).map { song("new$it", "pop") }
        val events = (1..14).map { ListeningSignal("known$it", "completed") }
        val picks = StyleRecommendations.similar("anchor", tracks, events).picks
        assertEquals(10, picks.size)
        assertEquals(3, picks.count { it.unfamiliar })
        assertEquals(7, picks.count { !it.unfamiliar })
    }

    @Test fun quotaFallbackUsesOnlyAvailableRealCandidates() {
        val tracks = listOf(song("anchor", "pop")) + (1..12).map { song("known$it", "pop") }
        val events = (1..12).map { ListeningSignal("known$it", "completed") }
        val plan = StyleRecommendations.similar("anchor", tracks, events)
        assertEquals(10, plan.picks.size)
        assertEquals(0, plan.picks.count { it.unfamiliar })
        assertTrue(plan.explanation.contains("尚无记录 0 首"))
    }

    @Test fun fourSkipsAvoidCommonStyleButKeepOtherDirections() {
        val events = (1..4).map { ListeningSignal("skip$it", "fast") }
        val tracks = (1..3).map { song("skip$it", "rock") } + song("skip4", "pop") +
            song("current", "jazz") + song("still-rock", "rock", "soul") + song("other", "soul")
        val plan = StyleRecommendations.changeDirection("current", tracks, events)
        assertEquals(setOf("rock"), plan.rejectedGenres)
        assertEquals(listOf("other"), plan.picks.map { it.track.mediaId })
    }

    @Test fun missingRejectedFeaturesAndMixedStylesDoNotInventDirection() {
        val events = (1..4).map { ListeningSignal("skip$it", "fast") }
        assertTrue(StyleRecommendations.changeDirection("now", listOf(song("other", "rock")), events).picks.isEmpty())
        val tracks = (1..4).map { song("skip$it", "different-$it") } + song("other", "rock")
        val plan = StyleRecommendations.changeDirection("now", tracks, events)
        assertTrue(plan.picks.isEmpty())
        assertTrue(plan.heading.contains("混杂"))
    }

    @Test fun fourSkipsMustBeConsecutiveAndNegativeSongIsNotAnAnchor() {
        val events = listOf("fast", "completed", "fast", "fast").mapIndexed { i, category -> ListeningSignal("s$i", category) }
        assertTrue(StyleRecommendations.changeDirection("now", listOf(song("other", "rock")), events).picks.isEmpty())
    }
}
