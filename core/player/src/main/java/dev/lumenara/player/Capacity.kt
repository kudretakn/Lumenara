package dev.lumenara.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import dev.lumenara.data.backgroundWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val capacity = DecoderCapacity {
    val start = android.os.SystemClock.elapsedRealtime()
    try { probeDecoderSlots() } finally {
        android.util.Log.i("LumenaraPerf", "decoder_capacity ms=${android.os.SystemClock.elapsedRealtime() - start}")
    }
}

/** Only requested by multiview, off Main; vendor capabilities are queried once per process. */
suspend fun decoderSlots(): Int = withContext(Dispatchers.IO) {
    backgroundWork.run { capacity.get() }
}

private fun probeDecoderSlots(): Int {
    return MediaCodecList(MediaCodecList.REGULAR_CODECS)
        .codecInfos
        .asSequence()
        .filter {
            !it.isEncoder &&
                hardware(it) &&
                it.supportedTypes.any { mime -> mime.equals("video/avc", true) }
        }
        .mapNotNull {
            runCatching { it.getCapabilitiesForType("video/avc").maxSupportedInstances }.getOrNull()
        }
        .maxOrNull()
        ?.coerceIn(1, 4) ?: 1
}

private fun hardware(codec: MediaCodecInfo): Boolean =
    if (Build.VERSION.SDK_INT >= 29) codec.isHardwareAccelerated
    else !codec.name.startsWith("OMX.google.") && !codec.name.startsWith("c2.android.")
