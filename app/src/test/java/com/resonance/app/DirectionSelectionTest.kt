package com.resonance.app

import org.junit.Assert.*
import org.junit.Test

class DirectionSelectionTest {
    private fun track(mid: String, vararg labels: String, recording: String = "qq:$mid") =
        StyleTrack("qq:$mid", mid, "artist-$mid", recording, labels.toSet())

    private fun events(tracks: List<StyleTrack>) = tracks.map {ListeningSignal(it.mediaId, "fast")}

    @Test fun twoKnownCompatibleRecordingsCanGuideASwitchWithTwoMissingGenres() {
        val skipped = listOf(track("s1", "alternative r&b"), track("s2", "contemporary r&b"), track("s3"), track("s4"))
        val plan = planDirection(LibraryState(listening = events(skipped)),
            skipped + listOf(track("jazz", "jazz"), track("still", "neo soul"), track("unknown")), "qq:current", PlaybackRange.LIBRARY)
        assertEquals("GENRE_HINT", plan.directionKind)
        assertEquals(setOf("r&b"), plan.rejectedGenres)
        assertEquals(listOf("qq:jazz"), plan.picks.map {it.track.mediaId})
    }

    @Test fun disagreedOrDuplicateEvidenceFallsBackAndPrefersDifferentKnownMusic() {
        val skipped = listOf(track("s1", "house"), track("s2", "rock"), track("s3"), track("s4"))
        val candidates = listOf(track("similar", "house"), track("different", "jazz"), track("unknown"))
        val plan = planDirection(LibraryState(listening = events(skipped)), skipped + candidates, "qq:current", PlaybackRange.LIBRARY)
        assertEquals("BATCH", plan.directionKind)
        assertEquals("qq:different", plan.picks.first().track.mediaId)
        val duplicateSkips = listOf(skipped[0], track("alias", "house", recording = skipped[0].recordingId), skipped[2], skipped[3])
        val duplicatePlan = planDirection(LibraryState(listening = events(duplicateSkips)), duplicateSkips + candidates,
            "qq:current", PlaybackRange.LIBRARY)
        assertEquals("BATCH", duplicatePlan.directionKind)
    }

    @Test fun mixedSkipsStartTheBatchWithTheFarthestKnownStyleEvenIfItIsNew() {
        val skipped = listOf(track("s1", "house"), track("s2", "rock"), track("s3"), track("s4"))
        val favorite = QqCatalogueTrack("familiar", "familiar", "收藏", "歌手", "", 200000)
        val plan = planDirection(LibraryState(favorites = listOf(favorite), listening = events(skipped)),
            skipped + listOf(track("familiar", "house"), track("fresh", "folk")), "qq:current", PlaybackRange.LIBRARY)

        assertEquals("BATCH", plan.directionKind)
        assertEquals(listOf("qq:fresh", "qq:familiar"), plan.picks.map { it.track.mediaId })
    }

    @Test fun tenAttemptsNeverReuseTargetsEvenWhenTheyNeverEnterListeningEvents() {
        val skipped = (1..4).map {track("s$it")}
        val candidates = (1..12).map {track("choice$it", "folk")}
        var state = LibraryState(listening = events(skipped))
        val chosen = mutableSetOf<String>()
        repeat(10) { index ->
            val plan = planDirection(state, skipped + candidates, "qq:current", PlaybackRange.LIBRARY)
            val target = plan.picks.first().track
            assertTrue(chosen.add(target.mediaId))
            assertTrue(plan.directionExcludedIds.containsAll(chosen - target.mediaId))
            state = state.copy(directionHistory = state.directionHistory + DirectionSwitch(
                index.toLong(), index.toLong(), "current", target.mediaId.removePrefix("qq:"), target.name,
                plan.directionKind, "", state = "UNCONFIRMED", targetRecordingId = target.recordingId
            ))
        }
    }

    @Test fun persistsRecordingIdentityAndExcludesAnAliasOfARemovedTarget() {
        val skipped = (1..4).map {track("s$it", "pop")}
        val row = DirectionSwitch(1, 1, "current", "gone", "旧歌", "GENRE", "",
            state = "PLAYING", targetRecordingId = "known-recording")
        val restored = DirectionSwitchCodec.decode(DirectionSwitchCodec.encode(listOf(row)))
        val plan = planDirection(LibraryState(listening = events(skipped), directionHistory = restored),
            skipped + listOf(track("newEdition", "jazz", recording = "known-recording"), track("unheard", "folk")),
            "qq:current", PlaybackRange.LIBRARY)
        assertEquals(listOf("qq:unheard"), plan.picks.map {it.track.mediaId})
    }

    @Test fun exhaustedFavoritesRangeKeepsQueueInsteadOfRepeatingOrLeavingScope() {
        val skipped = (1..4).map {track("s$it", "pop")}
        val prior = DirectionSwitch(1, 1, "current", "favorite", "旧歌", "GENRE", "")
        val favorite = QqCatalogueTrack("favorite", "favorite", "旧歌", "歌手", "", 200000)
        val plan = planDirection(LibraryState(favorites = listOf(favorite), listening = events(skipped), directionHistory = listOf(prior)),
            skipped + listOf(track("favorite", "jazz"), track("outside", "jazz")), "qq:current", PlaybackRange.FAVORITES)
        assertTrue(plan.picks.isEmpty())
        assertTrue(plan.explanation.contains("继续原队列"))
    }
}
