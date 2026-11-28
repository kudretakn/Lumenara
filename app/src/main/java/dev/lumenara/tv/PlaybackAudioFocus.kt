package dev.lumenara.tv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** VLC's focus owner; Media3 continues to use its built-in focus manager. Main only. */
internal class PlaybackAudioFocus(
    private val request: () -> Int,
    private val abandon: () -> Unit,
    private val playback: (Boolean) -> Unit,
    private val intentChanged: (Boolean) -> Unit,
) {
    private var requested = false
    private var granted = false
    private var closed = false
    var wantsPlay = false
        private set
    val canPlay: Boolean get() = !closed && wantsPlay && granted

    fun play() {
        if (closed) return
        wantsPlay = true
        intentChanged(true)
        if (!requested) {
            requested = true
            when (request()) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> granted = true
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> granted = false
                else -> { requested = false; granted = false }
            }
        }
        playback(canPlay)
    }

    fun pause() {
        if (closed) return
        wantsPlay = false
        intentChanged(false)
        playback(false)
        release()
    }

    fun onFocusChange(change: Int) {
        if (closed || !requested) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> { granted = true; playback(canPlay) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                granted = false
                playback(false)
            }
            AudioManager.AUDIOFOCUS_LOSS -> pause()
        }
    }

    private fun release() {
        val held = requested
        requested = false
        granted = false
        if (held) abandon()
    }

    /** Native end/error has already stopped output; a live retry retains play intent. */
    fun stopped(keepPlayIntent: Boolean = true) {
        if (closed) return
        if (!keepPlayIntent) { wantsPlay = false; intentChanged(false) }
        release()
    }

    fun close() {
        if (closed) return
        closed = true
        playback(false)
        release()
    }

    companion object {
        fun create(context: Context, playback: (Boolean) -> Unit, intentChanged: (Boolean) -> Unit): PlaybackAudioFocus {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            lateinit var owner: PlaybackAudioFocus
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setWillPauseWhenDucked(true)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener({ owner.onFocusChange(it) }, Handler(Looper.getMainLooper()))
                .build()
            owner = PlaybackAudioFocus({ manager.requestAudioFocus(focusRequest) },
                { manager.abandonAudioFocusRequest(focusRequest); Unit }, playback, intentChanged)
            return owner
        }
    }
}
