package dev.lumenara.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val failedWindow = DecoderCapacity { 4 }
private val capacities = object : LinkedHashMap<Wp18VideoLoad, DecoderCapacity>(16, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Wp18VideoLoad, DecoderCapacity>?): Boolean = size > 16
}

/** Called only on explicit multiview entry. The app supplies its background-work scheduling. */
suspend fun decoderSlots(load: Wp18VideoLoad? = null): Int = withContext(Dispatchers.IO) {
    if (load == null || wp18DecoderWindow(4, load) == 1) return@withContext 1
    val capacity = synchronized(capacities) {
        capacities.getOrPut(load) { DecoderCapacity { probeDecoderSlots(load) } }
    }
    minOf(failedWindow.get(), wp18DecoderWindow(capacity.get(), load))
}

/** A real decoder failure is stronger evidence than the vendor's advertised instance count. */
fun decoderWindowFailed(activeWindows: Int): Int {
    return failedWindow.failed(activeWindows)
}

private fun probeDecoderSlots(load: Wp18VideoLoad): Int {
    val start = android.os.SystemClock.elapsedRealtime()
    return try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asSequence()
            .filter { !it.isEncoder && hardware(it) }
            .mapNotNull { codec ->
                val mime = codec.supportedTypes.firstOrNull { it.equals(load.mime, true) } ?: return@mapNotNull null
                runCatching {
                    val caps = codec.getCapabilitiesForType(mime)
                    val supported = caps.videoCapabilities?.areSizeAndRateSupported(
                        load.width, load.height, load.frameRate.toDouble()) == true
                    if (supported) caps.maxSupportedInstances else 1
                }.getOrDefault(1)
            }
            // Different vendor decoders may be chosen at runtime; never take the most optimistic one.
            .minOrNull()?.coerceIn(1, 4) ?: 1
    } finally {
        android.util.Log.i("LumenaraPerf", "decoder_capacity ms=${android.os.SystemClock.elapsedRealtime() - start}")
    }
}

private fun hardware(codec: MediaCodecInfo): Boolean =
    if (Build.VERSION.SDK_INT >= 29) codec.isHardwareAccelerated
    else !codec.name.startsWith("OMX.google.") && !codec.name.startsWith("c2.android.")
