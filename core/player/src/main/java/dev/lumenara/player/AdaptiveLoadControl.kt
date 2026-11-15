package dev.lumenara.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator

/** One instance per player. All sizing and allocator updates run on the playback thread. */
@OptIn(UnstableApi::class)
internal class AdaptiveLoadControl(
    buffers: LiveBuffers,
    private val memoryClassMb: Int,
    private val lowRam: Boolean,
    private val live: Boolean,
    private val multi: Boolean,
) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    buffers.minMs, buffers.minMs,
    buffers.maxMs, buffers.maxMs,
    buffers.playMs, buffers.playMs,
    buffers.rebufferMs, buffers.rebufferMs,
    C.LENGTH_UNSET, // Let onTracksSelected call our sizing hook and update the allocator.
    false, false, // Enforce byte targets for network AND local playback.
    0, false,
) {
    override fun calculateTargetBufferBytes(
        parameters: LoadControl.Parameters,
        trackSelectionArray: Array<out ExoTrackSelection?>,
    ): Int {
        var bitrate = 0L
        var unknown = false
        var videoHeight = -1
        for (selection in trackSelectionArray) {
            if (selection == null || selection.trackGroup.type !in listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)) continue
            var trackBitrate = 0L
            // ABR can switch formats without another onTracksSelected callback. Budget for
            // the highest bitrate in the selection, rather than just its current variant.
            for (index in 0 until selection.length()) {
                val format = selection.getFormat(index)
                val rate = maxOf(format.bitrate, format.peakBitrate, format.averageBitrate)
                if (rate <= 0) unknown = true
                trackBitrate = maxOf(trackBitrate, rate.toLong())
                if (selection.trackGroup.type == C.TRACK_TYPE_VIDEO) {
                    videoHeight = maxOf(videoHeight, format.height)
                }
            }
            bitrate += trackBitrate
        }
        // An audio bitrate alone cannot size a video stream with missing metadata.
        return targetBufferBytes(memoryClassMb, lowRam, live, multi, if (unknown) -1 else bitrate, videoHeight)
    }
}
