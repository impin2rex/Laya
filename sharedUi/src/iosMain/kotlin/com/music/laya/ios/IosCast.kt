package com.music.laya.ios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.laya.ui.player.CastUi
import com.music.laya.ui.player.PlayerDrawer
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Google Cast, implemented in Swift on the Cast SDK (iosApp/iosApp/CastNative.swift) and handed
 * to Kotlin through `MainViewController`. Same split as Android's CastController / CastPlayback:
 * the phone keeps the queue, the receiver owns the clock ([IosPlayer] mirrors it).
 * Main thread only, both ways.
 */
interface CastNative {
    fun setListener(listener: CastNativeListener?)

    /** True while the device list is on screen: scan hard instead of listening. */
    fun discover(active: Boolean)
    fun connect(id: String)
    fun disconnect()

    /** Replace the receiver's queue with [item]. */
    fun load(item: CastItem, positionMs: Long, autoplay: Boolean)

    /** Media ids queued after the track the receiver is on. */
    fun aheadIds(): List<String>
    fun trimAhead(keep: Int)
    fun append(item: CastItem)

    /** Jump to a track the receiver already holds; false when it does not. */
    fun jumpTo(mediaId: String): Boolean
    fun currentMediaId(): String?
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setVolume(level: Float)
}

/** A stream the receiver can open by itself, and what to show for it. */
class CastItem(
    val url: String,
    val mimeType: String,
    val mediaId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val artworkUrl: String?,
)

interface CastNativeListener {
    /** The SDK is up: the Cast row may be drawn. */
    fun onReady()
    fun onDevices(ids: List<String>, names: List<String>)

    /** [state]: 0 none, 1 connecting, 2 connected. */
    fun onSession(state: Int, name: String?, resumed: Boolean)

    /** [state]: 0 idle, 1 playing, 2 paused, 3 buffering, 4 finished, 5 error. */
    fun onStatus(mediaId: String?, positionMs: Long, durationMs: Long, state: Int)
    fun onVolume(level: Float)
}

internal class CastDevice(val id: String, val name: String)

internal object IosCast : CastNativeListener {
    var native: CastNative? = null
    var player: IosPlayer? = null
    val ui = MutableStateFlow(CastUi())
    val devices = MutableStateFlow(emptyList<CastDevice>())

    /** The receiver is the speaker. */
    var active = false
        private set

    /** Set by "Stop casting": the listener wants the music back on this phone, playing. */
    var resumeHere = false

    fun install(cast: CastNative) {
        native = cast
        cast.setListener(this)
    }

    override fun onReady() { ui.value = ui.value.copy(supported = true) }

    override fun onDevices(ids: List<String>, names: List<String>) {
        devices.value = ids.zip(names).map { (id, name) -> CastDevice(id, name) }.sortedBy { it.name.lowercase() }
    }

    override fun onSession(state: Int, name: String?, resumed: Boolean) {
        val was = active
        active = state == 2
        ui.value = ui.value.copy(connectedName = name.takeIf { active }, connecting = state == 1)
        if (active && !was) player?.castBegan(resumed)
        if (!active && was) { player?.castEnded(resumeHere); resumeHere = false }
    }

    override fun onStatus(mediaId: String?, positionMs: Long, durationMs: Long, state: Int) {
        if (active) player?.castStatus(mediaId, positionMs, durationMs, state)
    }

    override fun onVolume(level: Float) { if (active) IosVolume.level.value = level }
}

/** The device picker the output sheet's Cast row opens (Android's CastDialog). */
@Composable
internal fun IosCastDialog(hazeState: HazeState, onDismiss: () -> Unit) {
    val cast by IosCast.ui.collectAsStateWithLifecycle()
    val devices by IosCast.devices.collectAsStateWithLifecycle()
    // Closes itself when the tapped receiver connects, but not when opened while already casting.
    var picked by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        IosCast.native?.discover(true)
        onDispose { IosCast.native?.discover(false) }
    }
    LaunchedEffect(cast.connectedName, picked) { if (picked && cast.connectedName != null) onDismiss() }
    PlayerDrawer(hazeState = hazeState, title = "Cast", onDismiss = onDismiss) {
        Text("Play on a Chromecast or Google Cast speaker", color = Color.White.copy(alpha = 0.7f), fontSize = 12.5.sp)
        Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            if (devices.isEmpty()) {
                Text("Looking for devices…", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.W600)
                Text(
                    "Your phone and the receiver need to be on the same Wi-Fi network.",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
            devices.forEach { d ->
                val here = cast.casting && cast.connectedName == d.name
                Row(Modifier.fillMaxWidth().clickable(enabled = !here) { picked = true; IosCast.native?.connect(d.id) }.padding(vertical = 12.dp)) {
                    Text(d.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.W600, modifier = Modifier.weight(1f))
                    Text(if (here) "Casting" else if (cast.connecting) "Connecting…" else "", color = Color.White.copy(alpha = 0.7f), fontSize = 12.5.sp)
                }
            }
            if (cast.casting) {
                Text(
                    "Stop casting", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.W600,
                    modifier = Modifier.fillMaxWidth().clickable { IosCast.resumeHere = true; IosCast.native?.disconnect(); onDismiss() }.padding(vertical = 12.dp),
                )
            }
        }
    }
}

private val CastUi.casting get() = connectedName != null
