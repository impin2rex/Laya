package com.music.laya.ios

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.laya.data.NerdStats
import com.music.laya.ui.player.AudioOutputDevice
import com.music.laya.ui.player.AudioOutputKind
import com.music.laya.ui.player.PlayerDrawer
import dev.chrisbanes.haze.HazeState
import platform.AVKit.AVRoutePickerView
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIColor

/** The audio session's current output route, as the Swift engine reports it. */
internal class RouteInfo(val name: String, val portType: String, val rateHz: Int, val channels: Int, val ioBufferMs: Int)

internal object IosRoute {
    var engine: AudioEngine? = null
    fun info(): RouteInfo? = engine?.outputRoute()?.takeIf { it.size >= 5 }?.let {
        RouteInfo(it[0], it[1], it[2].toIntOrNull() ?: 0, it[3].toIntOrNull() ?: 0, it[4].toIntOrNull() ?: 0)
    }
}

/** iOS cannot list idle outputs, so the list is the one route in use (phone speaker, Bluetooth, AirPlay, ...). */
private fun currentOutputs(): List<AudioOutputDevice> {
    val r = IosRoute.info() ?: return emptyList()
    val kind = when (r.portType) {
        "BluetoothA2DP", "BluetoothHFP", "BluetoothLE", "AirPlay" -> AudioOutputKind.BLUETOOTH
        "Headphones" -> AudioOutputKind.WIRED
        "USBAudio" -> AudioOutputKind.USB
        "HDMI" -> AudioOutputKind.HDMI
        "CarAudio" -> AudioOutputKind.OTHER
        else -> AudioOutputKind.PHONE
    }
    // The built-in speaker is named "Speaker"; blank lets the drawer say "This phone".
    if (kind == AudioOutputKind.PHONE) return listOf(AudioOutputDevice(0, "", kind, isActive = true))
    // As Android: the phone stays listed, unticked, above the output that is playing.
    return listOf(AudioOutputDevice(1, "", AudioOutputKind.PHONE, isActive = false), AudioOutputDevice(0, r.name, kind, isActive = true))
}

/** The route, kept current while composed. */
@Composable
internal fun rememberCurrentOutputs(): List<AudioOutputDevice> {
    var outputs by remember { mutableStateOf(currentOutputs()) }
    DisposableEffect(Unit) {
        val token = NSNotificationCenter.defaultCenter.addObserverForName("AVAudioSessionRouteChangeNotification", null, NSOperationQueue.mainQueue) {
            outputs = currentOutputs()
        }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(token) }
    }
    return outputs
}

/**
 * The system route picker (Bluetooth, AirPlay, speaker). iOS has no API to list or switch outputs, and a hidden
 * picker whose button is "pressed" from code never lays out or presents, so the real, transparent control is
 * placed over the output row and receives the tap itself.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun IosRoutePicker(modifier: Modifier) {
    UIKitView(
        factory = {
            AVRoutePickerView().apply {
                tintColor = UIColor.clearColor
                activeTintColor = UIColor.clearColor
                backgroundColor = UIColor.clearColor
                opaque = false
                alpha = 0.02 // invisible but still hit-testable (< 0.01 is not)
            }
        },
        modifier = modifier,
        // Above the Compose canvas: behind it the view punches a hole that shows the window's white.
        properties = UIKitInteropProperties(placedAsOverlay = true),
    )
}

private fun rateText(hz: Int) = "${((hz / 100.0).toInt() / 10.0).toString().removeSuffix(".0")} kHz"

/** The session's output rate, for the pipeline row and dialog. */
internal fun outputRateHz(): Int? = IosRoute.info()?.rateHz?.takeIf { it > 0 }

internal fun outputSummary(): String = outputRateHz()?.let(::rateText) ?: ""

/** Audio Pipeline on iOS: what the engine and session actually know. Sections that Android fills from Android-only telemetry (decoder, DSP, USB, Bluetooth codec) are left out rather than faked. */
@Composable
internal fun IosAudioPipeline(hazeState: HazeState, onDismiss: () -> Unit) {
    val stats by NerdStats.current.collectAsStateWithLifecycle()
    val outputs = rememberCurrentOutputs()
    val route = IosRoute.info()
    val none = "—"
    val inRate = stats?.sampleRateHz
    val outRate = outputRateHz()
    val lossless = stats?.isLossless == true
    PlayerDrawer(hazeState = hazeState, title = "Audio Pipeline", onDismiss = onDismiss) {
        Section("Track info") {
            Line("Source", stats?.sourceName ?: none)
            Line("Format", NerdStats.codecLabel(stats?.mimeType) ?: stats?.mimeType ?: none)
            Line("Bit depth", if (lossless) stats?.bitDepth?.let { "$it-bit" } ?: none else none)
            Line("Sample rate", inRate?.let(::rateText) ?: none)
            Line("Bitrate", stats?.bitrateKbps?.let { "$it kbps" } ?: none)
            Line("Channels", when (val c = stats?.channels) { null -> none; 1 -> "Mono"; 2 -> "Stereo"; else -> "$c (Surround)" })
        }
        Section("Resampler") {
            Line("I/O rate", if (inRate != null && outRate != null) "${rateText(inRate)} → ${rateText(outRate)}" else none)
        }
        Section("Output device") {
            Line("Device name", outputs.firstOrNull { it.isActive }?.let { it.name.ifBlank { "iPhone Speaker" } } ?: none)
            Line("Route", route?.portType?.ifBlank { null } ?: none)
            Line("Sample rate", outRate?.let(::rateText) ?: none)
            Line("Channels", route?.channels?.takeIf { it > 0 }?.toString() ?: none)
            Line("I/O buffer", route?.ioBufferMs?.takeIf { it > 0 }?.let { "$it ms" } ?: none)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(title.uppercase(), color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.W600)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text("$label: ", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
        Text(value, color = Color.White.copy(alpha = 0.82f), fontSize = 12.5.sp, style = MaterialTheme.typography.bodySmall)
    }
}
