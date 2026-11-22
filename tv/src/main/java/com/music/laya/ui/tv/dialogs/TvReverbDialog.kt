package com.music.laya.ui.tv.dialogs

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.music.laya.R
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.music.laya.data.settings.AppSettings
import com.music.laya.playback.ReverbPreset
import com.music.laya.playback.ReverbSettings
import com.music.laya.ui.tv.components.TvButton
import com.music.laya.ui.tv.components.TvDialog
import com.music.laya.ui.tv.components.TvListRow
import com.music.laya.ui.tv.components.tvInitialFocus
import com.music.laya.ui.tv.theme.TvGlass
import com.music.laya.ui.tv.theme.TvType
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The reverb as a tvOS sheet, with the phone's controls over the same
 * [ReverbSettings]: on/off, the room, then each parameter as a row that left
 * and right step down and up — a slider is no use to a remote. Select steps
 * up as well. Every step is clamped by [AppSettings.setReverb].
 */
@Composable
fun TvReverbDialog(onDismiss: () -> Unit) {
    val enabled by AppSettings.reverbEnabled.collectAsState()
    val settings by AppSettings.reverb.collectAsState()

    TvDialog(title = stringResource(R.string.tv_reverb), width = 512.dp, onDismissRequest = onDismiss) {
        TvListRow(
            title = stringResource(R.string.tv_reverb),
            value = if (enabled) stringResource(R.string.tv_on) else stringResource(R.string.off),
            modifier = Modifier.tvInitialFocus(),
            onClick = { AppSettings.setReverbEnabled(!enabled) },
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            item {
                val presets = ReverbPreset.entries
                val current = settings.preset
                TvStepRow(
                    title = stringResource(R.string.tv_reverb_room),
                    value = stringResource(current?.label ?: R.string.eq_preset_custom),
                    onStep = { direction ->
                        val at = current?.ordinal ?: if (direction > 0) -1 else 0
                        val next = presets[(at + direction).mod(presets.size)]
                        AppSettings.setReverb(settings.withPreset(next))
                    },
                )
            }
            item {
                TvStepRow(
                    title = stringResource(R.string.tv_reverb_decay),
                    value = String.format(Locale.US, "%.1f s", settings.decayMs / 1_000f),
                    onStep = { AppSettings.setReverb(settings.copy(decayMs = settings.decayMs + it * 100)) },
                )
            }
            item {
                TvStepRow(
                    title = stringResource(R.string.tv_reverb_predelay),
                    value = "${settings.preDelayMs} ms",
                    onStep = { AppSettings.setReverb(settings.copy(preDelayMs = settings.preDelayMs + it * 5)) },
                )
            }
            item {
                TvStepRow(
                    title = stringResource(R.string.tv_reverb_damping),
                    value = String.format(Locale.US, "%.1f kHz", settings.dampingHz / 1_000f),
                    onStep = { AppSettings.setReverb(settings.copy(dampingHz = settings.dampingHz + it * 500)) },
                )
            }
            item {
                TvStepRow(
                    title = stringResource(R.string.tv_reverb_mix),
                    value = "${(settings.mix * 100).roundToInt()}%",
                    onStep = { AppSettings.setReverb(settings.copy(mix = ((settings.mix * 100).roundToInt() + it * 5) / 100f)) },
                )
            }
            item {
                TvStepRow(
                    title = stringResource(R.string.tv_reverb_width),
                    value = "${(settings.width * 100).roundToInt()}%",
                    onStep = { AppSettings.setReverb(settings.copy(width = ((settings.width * 100).roundToInt() + it * 10) / 100f)) },
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tv_reverb_hint),
            style = TvType.Caption,
            color = TvGlass.TextTertiary,
        )
        Spacer(modifier = Modifier.height(14.dp))
        TvButton(text = stringResource(R.string.tv_reset_all), onClick = { AppSettings.setReverb(ReverbSettings.DEFAULT) })
    }
}

/**
 * A row whose value left and right step through. Select is a step up: the
 * phone's sliders have no remote equivalent, and a row that only reacted to
 * left and right would look broken to anyone pressing the centre button.
 */
@Composable
private fun TvStepRow(title: String, value: String, onStep: (Int) -> Unit) {
    TvListRow(
        title = title,
        value = "‹  $value  ›",
        modifier = Modifier.onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { onStep(-1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { onStep(1); true }
                else -> false
            }
        },
        onClick = { onStep(1) },
    )
}

@get:StringRes
private val ReverbPreset.label: Int
    get() = when (this) {
        ReverbPreset.ROOM -> R.string.tv_reverb_room
        ReverbPreset.HALL -> R.string.tv_reverb_hall
        ReverbPreset.PLATE -> R.string.tv_reverb_plate
        ReverbPreset.CATHEDRAL -> R.string.tv_reverb_cathedral
    }
