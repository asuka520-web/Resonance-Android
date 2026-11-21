package com.resonance.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlaybackAudioProbeTest {
    private val rate = 8_000
    private val halfScale = ByteBuffer.allocate(rate * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(rate * 2) { putShort(16_384) }
        flip()
    }

    private fun send(probe: PlaybackAudioProbe, seconds: IntRange) {
        for (second in seconds) probe.consume(halfScale, 0, halfScale.limit(), second * 1_000_000L, rate, 2)
    }

    @Test fun measuresBothWindowsWithoutMovingTheBuffer() {
        val probe = PlaybackAudioProbe()
        probe.updateActive(true)
        send(probe, 0..28)
        assertNull(probe.snapshot().firstDbfs)
        send(probe, 29..53)
        assertEquals(PlaybackAudioMeasurement(-6), probe.snapshot())
        send(probe, 54..55)
        assertEquals(PlaybackAudioMeasurement(-6, -6), probe.snapshot())
        assertEquals(0, halfScale.position())
    }

    @Test fun duplicateOrDisabledInputDoesNotFillGaps() {
        val probe = PlaybackAudioProbe()
        probe.updateActive(true)
        repeat(15) { send(probe, 15..15) }
        probe.updateActive(false)
        send(probe, 16..28)
        probe.updateActive(true)
        send(probe, 29..29)
        assertNull(probe.snapshot().firstDbfs)
    }

    @Test fun seeksAndStreamResetsDiscardOldMeasurements() {
        val probe = PlaybackAudioProbe()
        probe.updateActive(true)
        send(probe, 15..29)
        assertEquals(-6, probe.snapshot().firstDbfs)
        probe.newPlayback()
        assertNull(probe.snapshot().firstDbfs)
        probe.updateActive(true)
        send(probe, 40..54)
        assertEquals(PlaybackAudioMeasurement(secondDbfs = -6), probe.snapshot())
        probe.clearWindows()
        assertEquals(PlaybackAudioMeasurement(), probe.snapshot())
    }

    @Test fun capturesBothWindowsAsMonoAtModelRate() {
        val probe = PlaybackAudioProbe()
        probe.updateActive(true, capture = true)
        send(probe, 15..29)
        assertNull(probe.takeGenreAudio())
        send(probe, 40..54)
        val capture = requireNotNull(probe.takeGenreAudio())
        assertEquals(240_000, capture.first.size)
        assertEquals(240_000, capture.second.size)
        assertEquals(true, capture.first.all { it == 16_384.toShort() })
        assertEquals(true, capture.second.all { it == 16_384.toShort() })
        assertNull(probe.takeGenreAudio())
    }

    @Test fun genreSuggestionsRequireAgreementAndRepeatedMusicEvidence() {
        fun windows(vararg genres: Int) = List(5) { FloatArray(521).apply {
            this[132] = .9f
            genres.forEach { this[it] = .8f }
        } }
        val first = windows(221, 211)
        assertEquals(ModelGenreResult(ModelGenreAssessment.AGREEMENT, listOf("R&B")), ModelGenres.assess(first, windows(221, 224)))
        assertEquals(ModelGenreResult(ModelGenreAssessment.DIFFERENT), ModelGenres.assess(first, windows(224)))
        assertEquals(ModelGenreResult(ModelGenreAssessment.INSUFFICIENT), ModelGenres.assess(first, windows(221).take(2)))
        assertEquals(ModelGenreResult(ModelGenreAssessment.INSUFFICIENT), ModelGenres.assess(emptyList(), emptyList()))
    }

    @Test fun laterPassUsesOnlyTheLaterWindows() {
        val probe = PlaybackAudioProbe()
        probe.updateActive(true, capture = true)
        send(probe, 15..54)
        probe.updateActive(true, capture = true, plan = GenreSamplePlan.LATER)
        assertNull(probe.takeGenreAudio())
        send(probe, 60..92)
        assertNull(probe.takeGenreAudio())
        send(probe, 93..94)
        assertEquals(240_000, requireNotNull(probe.takeGenreAudio()).second.size)
    }
}

@RunWith(RobolectricTestRunner::class)
class ModelGenreRetryTest {
    @Test fun manualRequestWorksForAnOldEmptyResultWithAutomaticAnalysisOff() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val song = QqCatalogueTrack("sample", "", "Song", "Artist", "Album", 150_000)
        val scope = "account"
        val fingerprint = listOf(song.name, song.artist, song.album, song.durationMs).hashCode()
        context.getSharedPreferences("resonance_model_genres", Context.MODE_PRIVATE).edit()
            .putString("yamnet-v1/$scope/${song.mid}/$fingerprint", "").apply()
        assertNull(ModelGenres.sampleWork(context, scope, song, song.durationMs, auto = true))
        ModelGenres.request(context, scope, song)
        val first = requireNotNull(ModelGenres.sampleWork(context, scope, song, song.durationMs, auto = false))
        assertEquals(GenreSamplePlan.PRIMARY, first.plan)
        val unclear = ModelGenreResult(ModelGenreAssessment.INSUFFICIENT)
        assertEquals(true, ModelGenres.record(context, scope, song, first, unclear, retryLater = true))
        assertNull(ModelGenres.sampleWork(context, scope, song, 90_000, auto = false))
        val later = requireNotNull(ModelGenres.sampleWork(context, scope, song, song.durationMs, auto = false))
        assertEquals(GenreSamplePlan.LATER, later.plan)
        assertEquals(true, ModelGenres.record(context, scope, song, later, unclear, retryLater = false))
        assertNull(ModelGenres.sampleWork(context, scope, song, song.durationMs, auto = true))
    }

    @Test fun failedManualReanalysisPreservesLabelsAndDismissalCancelsPendingWork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val song = QqCatalogueTrack("saved", "", "Song", "Artist", "Album", 150_000)
        val scope = "account"
        val initial = requireNotNull(ModelGenres.sampleWork(context, scope, song, song.durationMs, auto = true))
        val labeled = ModelGenreResult(ModelGenreAssessment.AGREEMENT, listOf("电子"))
        ModelGenres.record(context, scope, song, initial, labeled, retryLater = false)
        ModelGenres.request(context, scope, song)
        assertEquals(listOf("电子"), ModelGenres.read(context, scope, song).labels)
        assertEquals(false, ModelGenres.record(context, scope, song, initial, labeled, retryLater = false))
        val requested = requireNotNull(ModelGenres.sampleWork(context, scope, song, song.durationMs, auto = false))
        val different = ModelGenreResult(ModelGenreAssessment.DIFFERENT)
        assertEquals(true, ModelGenres.record(context, scope, song, requested, different, retryLater = false))
        assertEquals(listOf("电子"), ModelGenres.read(context, scope, song).labels)
        ModelGenres.request(context, scope, song)
        val pending = requireNotNull(ModelGenres.sampleWork(context, scope, song, song.durationMs, auto = false))
        ModelGenres.dismiss(context, scope, song)
        assertEquals(false, ModelGenres.record(context, scope, song, pending, labeled, retryLater = false))
        assertNull(ModelGenres.sampleWork(context, scope, song, song.durationMs, auto = true))
    }
}
