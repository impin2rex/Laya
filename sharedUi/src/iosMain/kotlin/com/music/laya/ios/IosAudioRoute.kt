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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.laya.data.NerdStats
import com.music.laya.ui.player.AudioOutputDevice
import com.music.laya.ui.player.AudioOutputKind
import com.music.laya.ui.player.PlayerDrawer
import dev.chrisbanes.haze.HazeState
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVKit.AVRoutePickerView
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIButton
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIView

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
    return listOf(AudioOutputDevice(0, if (kind == AudioOutputKind.PHONE) "" else r.name, kind, isActive = true))
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
 * Opens the system route picker (Bluetooth, AirPlay, speaker). iOS has no API to present it directly:
 * the documented way is an AVRoutePickerView, whose button is pressed here once instead of being shown.
 */
private var routePicker: AVRoutePickerView? = null

@OptIn(ExperimentalForeignApi::class)
internal fun showRoutePicker() {
    val window = UIApplication.sharedApplication.keyWindow ?: return
    // One invisible picker kept in the window; its button is what presents the system sheet.
    val picker = routePicker ?: AVRoutePickerView().apply { alpha = 0.01 }.also { routePicker = it }
    if (picker.superview == null) window.addSubview(picker)
    fun UIView.firstButton(): UIButton? =
        (this as? UIButton) ?: subviews.filterIsInstance<UIView>().firstNotNullOfOrNull { it.firstButton() }
    picker.firstButton()?.sendActionsForControlEvents(UIControlEventTouchUpInside)
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
            Line("Device name", outputs.firstOrNull()?.let { it.name.ifBlank { "iPhone Speaker" } } ?: none)
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
