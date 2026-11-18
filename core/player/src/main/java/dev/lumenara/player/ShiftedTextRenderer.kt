package dev.lumenara.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.SubtitleDecoder
import androidx.media3.extractor.text.SubtitleDecoderException
import androidx.media3.extractor.text.SubtitleInputBuffer

/** Decodes ahead without advancing the display clock. Works with extracted and legacy text. */
@UnstableApi
internal class ShiftedTextRenderer(private val output: SubtitleShiftOutput) : BaseRenderer(C.TRACK_TYPE_TEXT) {
    private val holder = androidx.media3.exoplayer.FormatHolder()
    private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private val cueDecoder = CueDecoder()
    private var format: Format? = null
    private var decoder: SubtitleDecoder? = null
    private var input: SubtitleInputBuffer? = null
    private var inputEnded = false
    private var outputEnded = false
    private var readThroughUs = Long.MIN_VALUE

    override fun getName() = "ShiftedTextRenderer"
    override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
        if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES || SubtitleDecoderFactory.DEFAULT.supportsFormat(format)) {
            if (format.cryptoType == C.CRYPTO_TYPE_NONE) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_DRM
        } else if (MimeTypes.isText(format.sampleMimeType)) C.FORMAT_UNSUPPORTED_SUBTYPE else C.FORMAT_UNSUPPORTED_TYPE
    )
    override fun isReady() = true
    override fun isEnded() = outputEnded

    override fun onStreamChanged(formats: Array<Format>, startPositionUs: Long, offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId) {
        configure(formats[0])
    }

    private fun configure(value: Format) {
        decoder?.release()
        decoder = null
        input = null
        format = value
        output.clear(value.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES &&
            value.cueReplacementBehavior == Format.CUE_REPLACEMENT_BEHAVIOR_MERGE)
        if (value.sampleMimeType != MimeTypes.APPLICATION_MEDIA3_CUES)
            decoder = SubtitleDecoderFactory.DEFAULT.createDecoder(value)
        inputEnded = false
        outputEnded = false
        readThroughUs = Long.MIN_VALUE
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
        inputEnded = false
        outputEnded = false
        readThroughUs = Long.MIN_VALUE
        input = null
        decoder?.flush()
        decoder?.setOutputStartTimeUs(positionUs - 10_000_000L)
        output.clear(format?.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES &&
            format?.cueReplacementBehavior == Format.CUE_REPLACEMENT_BEHAVIOR_MERGE)
    }

    override fun onDisabled() {
        decoder?.release()
        decoder = null
        input = null
        format = null
        output.clear()
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        try {
            renderText(positionUs)
        } catch (error: SubtitleDecoderException) {
            // A malformed subtitle must not tear down otherwise valid audio/video playback.
            androidx.media3.common.util.Log.w(name, "Subtitle decoding failed", error)
            decoder?.release()
            decoder = null
            input = null
            inputEnded = true
            outputEnded = true
            output.clear()
        }
    }

    private fun renderText(positionUs: Long) {
        val horizon = positionUs + 10_000_000L
        val decoder = decoder
        decoder?.setPositionUs(horizon)
        // Decoders have a small buffer pool: copy events, then release each output immediately.
        if (decoder != null) repeat(32) {
            val decoded = decoder.dequeueOutputBuffer() ?: return@repeat
            try {
                if (decoded.isEndOfStream) outputEnded = true
                else for (index in 0 until decoded.eventTimeCount) {
                    val start = decoded.getEventTime(index)
                    val end = if (index + 1 < decoded.eventTimeCount) decoded.getEventTime(index + 1) else C.TIME_UNSET
                    output.add(start - streamOffsetUs, if (end == C.TIME_UNSET) C.TIME_UNSET else end - start,
                        decoded.getCues(start).toList())
                }
            } finally { decoded.release() }
        }
        if (inputEnded || readThroughUs > horizon) return
        repeat(32) {
            val target = if (decoder != null) {
                input ?: decoder.dequeueInputBuffer()?.also { input = it } ?: return
            } else buffer
            target.clear()
            when (readSource(holder, target, 0)) {
                C.RESULT_FORMAT_READ -> {
                    val next = holder.format ?: return
                    if (next != format) configure(next)
                    return
                }
                C.RESULT_BUFFER_READ -> {
                    if (target.isEndOfStream) {
                        inputEnded = true
                        if (decoder != null) { decoder.queueInputBuffer(input!!); input = null }
                        else outputEnded = true
                        return
                    }
                    readThroughUs = target.timeUs
                    if (decoder != null) {
                        input!!.subsampleOffsetUs = format!!.subsampleOffsetUs
                        target.flip()
                        decoder.queueInputBuffer(input!!)
                        input = null
                    } else {
                        target.flip()
                        val data = target.data ?: return
                        val bytes = ByteArray(data.remaining())
                        data.get(bytes)
                        val cues = cueDecoder.decode(target.timeUs - streamOffsetUs, bytes, 0, bytes.size)
                        output.add(cues.startTimeUs, cues.durationUs, cues.cues)
                    }
                    if (readThroughUs > horizon) return
                }
                else -> return
            }
        }
    }
}
