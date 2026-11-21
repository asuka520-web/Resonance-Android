package com.resonance.app

import java.nio.ByteBuffer
import kotlin.math.log10
import kotlin.math.roundToInt

data class PlaybackAudioMeasurement(val firstDbfs: Int? = null, val secondDbfs: Int? = null) {
    internal val complete get() = firstDbfs != null && secondDbfs != null
}

internal data class PlaybackGenreAudio(val first: ShortArray, val second: ShortArray)

/** Measures two windows of PCM sent to output and optionally samples them in memory. */
internal class PlaybackAudioProbe {
    private data class Request(val generation: Long, val active: Boolean, val capture: Boolean = false)
    private data class Result(val generation: Long, val values: PlaybackAudioMeasurement)
    private data class Captured(val generation: Long, val audio: PlaybackGenreAudio)

    private class Window(val startSeconds: Long) {
        var frames = 0L
        var endFrame = 0L
        var sampleCount = 0L
        var squared = 0.0
        var audio: ShortArray? = null

        fun measure(buffer: ByteBuffer, from: Int, until: Int, positionUs: Long, rate: Int, channels: Int,
                    capture: Boolean): Int? {
            val frameBytes = channels * 2
            val position = (positionUs * rate + 500_000) / 1_000_000
            val start = maxOf(position, startSeconds * rate, endFrame)
            val end = minOf(position + (until - from) / frameBytes, (startSeconds + 15) * rate)
            if (start >= end) return null
            val offset = from + ((start - position) * frameBytes).toInt()
            val limit = from + ((end - position) * frameBytes).toInt()
            for (frame in offset until limit step frameBytes * 8) {
                for (i in frame until frame + frameBytes step 2) {
                    val sample = sampleAt(buffer, i) / 32768.0
                    squared += sample * sample
                    sampleCount++
                }
            }
            if (capture) copyAudio(buffer, from, position, start, end, rate, channels)
            frames += end - start
            endFrame = end
            if (end < (startSeconds + 15) * rate || frames < rate * 14L) return null
            return (10 * log10((squared / sampleCount).coerceAtLeast(1e-9))).roundToInt()
        }

        private fun copyAudio(buffer: ByteBuffer, from: Int, position: Long, start: Long, end: Long,
                              rate: Int, channels: Int) {
            val samples = audio ?: ShortArray(ModelGenres.RATE * 15).also { audio = it }
            val base = startSeconds * rate
            val first = ((start - base) * ModelGenres.RATE + rate - 1) / rate
            val last = ((end - base) * ModelGenres.RATE + rate - 1) / rate
            for (i in first.toInt() until last.toInt()) {
                val source = base + i.toLong() * rate / ModelGenres.RATE
                val endSource = minOf(end, base + (i + 1L) * rate / ModelGenres.RATE).coerceAtLeast(source + 1)
                var sum = 0L
                for (frame in source until endSource) {
                    val offset = from + ((frame - position) * channels * 2).toInt()
                    for (channel in 0 until channels) sum += sampleAt(buffer, offset + channel * 2)
                }
                samples[i] = (sum / ((endSource - source) * channels)).toShort()
            }
        }

        private fun sampleAt(buffer: ByteBuffer, index: Int): Int =
            (buffer.get(index + 1).toInt() shl 8) or (buffer.get(index).toInt() and 0xff)
    }

    @Volatile private var request = Request(0, false)
    @Volatile private var result: Result? = null
    @Volatile private var captured: Captured? = null
    private var measuredGeneration = Long.MIN_VALUE
    private var first = Window(15)
    private var second = Window(40)
    private var values = PlaybackAudioMeasurement()

    val active get() = request.active
    fun snapshot(): PlaybackAudioMeasurement = result?.takeIf { it.generation == request.generation }?.values
        ?: PlaybackAudioMeasurement()

    fun takeGenreAudio(): PlaybackGenreAudio? {
        val packet = captured?.takeIf { it.generation == request.generation } ?: return null
        captured = null
        return packet.audio
    }

    // These methods are called on the player application thread.
    fun updateActive(active: Boolean, capture: Boolean = request.capture) {
        val old = request
        if (old.capture != capture) {
            request = Request(old.generation + 1, active, capture)
            captured = null
        } else if (old.active != active) request = old.copy(active = active)
    }

    fun newPlayback() {
        request = Request(request.generation + 1, false, request.capture)
        captured = null
    }

    // Audio callbacks run on the playback thread.
    fun clearWindows() {
        measuredGeneration = Long.MIN_VALUE
        first.audio = null
        second.audio = null
        result = null
        captured = null
    }

    fun consume(buffer: ByteBuffer, from: Int, until: Int, positionUs: Long, rate: Int, channels: Int) {
        val current = request
        if (!current.active || positionUs !in 14_000_000L until 55_000_000L || from >= until) return
        if (measuredGeneration != current.generation) {
            measuredGeneration = current.generation
            first = Window(15)
            second = Window(40)
            values = PlaybackAudioMeasurement()
        }
        if (values.complete) return
        val firstValue = values.firstDbfs ?: first.measure(buffer, from, until, positionUs, rate, channels, current.capture)
        val secondValue = values.secondDbfs ?: second.measure(buffer, from, until, positionUs, rate, channels, current.capture)
        if (firstValue != values.firstDbfs || secondValue != values.secondDbfs) {
            values = PlaybackAudioMeasurement(firstValue, secondValue)
            result = Result(current.generation, values)
            if (values.complete && current.capture) {
                val a = first.audio
                val b = second.audio
                if (a != null && b != null && request.generation == current.generation) {
                    captured = Captured(current.generation, PlaybackGenreAudio(a, b))
                }
                first.audio = null
                second.audio = null
            }
        }
    }

    companion object {
        const val SETTING = "preview_playback_audio_probe"
    }
}
