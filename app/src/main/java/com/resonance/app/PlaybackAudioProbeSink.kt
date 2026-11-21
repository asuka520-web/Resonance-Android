package com.resonance.app

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

@UnstableApi
internal class AudioProbeRenderers(context: Context, private val probe: PlaybackAudioProbe) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
        super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)?.let { PlaybackAudioProbeSink(it, probe) }
}

@UnstableApi
private class PlaybackAudioProbeSink(sink: AudioSink, private val probe: PlaybackAudioProbe) : ForwardingAudioSink(sink) {
    private var rate = 0
    private var channels = 0
    private var streamOffsetUs = C.TIME_UNSET
    private var pending: ByteBuffer? = null
    private var bufferStart = 0

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        val supported = inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW && inputFormat.pcmEncoding == C.ENCODING_PCM_16BIT
        rate = if (supported) inputFormat.sampleRate.coerceAtLeast(0) else 0
        channels = if (supported) inputFormat.channelCount.coerceAtLeast(0) else 0
        probe.clearWindows()
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
        if (streamOffsetUs != outputStreamOffsetUs) probe.clearWindows()
        streamOffsetUs = outputStreamOffsetUs
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val start = buffer.position()
        if (buffer !== pending) bufferStart = start
        val finished = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        pending = if (finished) null else buffer
        val end = buffer.position()
        if (probe.active && rate > 0 && channels > 0 && end > start &&
            streamOffsetUs != C.TIME_UNSET && presentationTimeUs != C.TIME_UNSET
        ) {
            val frameBytes = channels * 2
            val skippedFrames = (start - bufferStart + frameBytes - 1) / frameBytes
            val alignedStart = bufferStart + skippedFrames * frameBytes
            val positionUs = presentationTimeUs - streamOffsetUs + skippedFrames * 1_000_000L / rate
            probe.consume(buffer, alignedStart, end, positionUs, rate, channels)
        }
        return finished
    }

    override fun flush() {
        pending = null
        probe.clearWindows()
        super.flush()
    }

    override fun reset() {
        pending = null
        rate = 0
        channels = 0
        streamOffsetUs = C.TIME_UNSET
        probe.clearWindows()
        super.reset()
    }
}
