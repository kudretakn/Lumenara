package dev.streamora.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

fun decoderSlots(): Int {
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
