package dev.lumenara.tv

import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.FlagSet
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** No network, repository, codec, DPAD or long playback fixture is needed for this contract. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Wp14HiddenUiWorkTest {
    @get:Rule val screen = createComposeRule()

    @Test fun hiddenClockAndPlayerHaveNoPeriodicWritesAndTracksRefreshOnEvents() {
        val positionReads = AtomicInteger()
        val trackReads = AtomicInteger()
        val compositions = AtomicInteger()
        val listeners = mutableListOf<Player.Listener>() // Accessed only on Main.
        var position = 0L
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Wp14FakePlayer"
                else -> {
                    check(Looper.myLooper() == Looper.getMainLooper())
                    when (method.name) {
                        "getCurrentPosition" -> { positionReads.incrementAndGet(); position }
                        "getDuration" -> 60_000L
                        "getCurrentTracks" -> { trackReads.incrementAndGet(); Tracks.EMPTY }
                        "isCurrentMediaItemLive", "isCurrentMediaItemSeekable" -> false
                        "addListener" -> { listeners += args!![0] as Player.Listener; null }
                        "removeListener" -> { listeners.remove(args!![0] as Player.Listener); null }
                        else -> error("Unexpected Player call: ${method.name}")
                    }
                }
            }
        } as Player
        var hiddenClock: State<Long>? = null
        var visible: MutableState<Boolean>? = null
        var snapshot: State<PlayerUiState>? = null
        screen.setContent {
            val showing = remember { mutableStateOf(false) }
            val clock = rememberWp14VisibleClock(false)
            val ui = rememberPlayerUiState(player, showing.value)
            BasicText("${clock.value} ${ui.value.position}")
            SideEffect {
                hiddenClock = clock
                visible = showing
                snapshot = ui
                compositions.incrementAndGet()
            }
        }
        var clockValue = 0L
        var initialReads = 0
        var initialCompositions = 0
        screen.runOnIdle {
            clockValue = hiddenClock!!.value
            initialReads = positionReads.get()
            initialCompositions = compositions.get()
            assertEquals(1, trackReads.get())
        }
        // Main uses the real wall clock, independent of Compose's virtual frame clock.
        val observeUntil = SystemClock.elapsedRealtime() + 1100
        screen.waitUntil(3000) { SystemClock.elapsedRealtime() >= observeUntil }
        screen.runOnIdle {
            assertEquals(clockValue, hiddenClock!!.value)
            assertEquals(initialReads, positionReads.get())
            assertEquals(initialCompositions, compositions.get())
            position = 1000
            listeners.single().onEvents(player, Player.Events(
                FlagSet.Builder().add(Player.EVENT_TRACKS_CHANGED).build()))
            assertEquals(2, trackReads.get())
            assertEquals(1000L, snapshot!!.value.position)
            visible!!.value = true
        }
        screen.waitForIdle()
        val afterOpening = positionReads.get()
        val tracksAfterOpening = trackReads.get()
        screen.waitUntil(2000) { positionReads.get() > afterOpening }
        screen.runOnIdle {
            assertEquals(tracksAfterOpening, trackReads.get())
        }
    }
}
