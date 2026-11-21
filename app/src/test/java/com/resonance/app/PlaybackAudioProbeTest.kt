package com.resonance.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
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
        assertEquals(listOf("R&B"), ModelGenres.agreedLabels(first, windows(221, 224)))
        assertEquals(emptyList<String>(), ModelGenres.agreedLabels(first, windows(224)))
        assertEquals(emptyList<String>(), ModelGenres.agreedLabels(first, windows(221).take(2)))
    }
}
