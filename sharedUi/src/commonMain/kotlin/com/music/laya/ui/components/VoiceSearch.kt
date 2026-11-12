package com.music.laya.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.laya.data.VoiceLanguages
import com.music.laya.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource

/** The shared list (see [VoiceLanguages]): the same on Android, iOS and macOS. */
internal val VOICE_LANGUAGES = VoiceLanguages.all

/** What "Auto" listens in: the device's own language (see [VoiceLanguages.auto]). */
internal fun autoVoiceLanguage(deviceTag: String): String = VoiceLanguages.auto(deviceTag)

/**
 * The search field's mic, handing off to the platform's own speech
 * recognizer — nothing is bundled, so the app stays the size it is. Words
 * heard so far go to [onPartial] where the platform reports them as they
 * come; the final transcript goes to [onResult], which runs the search.
 * Draws nothing where the platform has no recognizer to hand off to.
 */
@Composable
expect fun VoiceSearchButton(onResult: (String) -> Unit, onPartial: (String) -> Unit = {})

/**
 * The mic itself, shared by every platform: a tap listens (or stops
 * listening), a long press picks the language. [language] null is Auto.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VoiceMic(
    listening: Boolean,
    language: String?,
    onLanguageChange: (String?) -> Unit,
    onClick: () -> Unit,
    /** Only what this platform's recognizer has (Apple has no Bangla, for one). */
    languages: List<Pair<String, String>> = VOICE_LANGUAGES,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.voiceWaves(listening, MaterialTheme.colorScheme.primary)) {
        Icon(
            Icons.Rounded.Mic,
            contentDescription = stringResource(Res.string.voice_search),
            tint = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                    onLongClickLabel = stringResource(Res.string.voice_search_language),
                )
                .padding(6.dp),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            (listOf(null to stringResource(Res.string.voice_search_language_auto)) + languages)
                .forEach { (tag, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        trailingIcon = {
                            if (tag == language) Icon(Icons.Rounded.Check, contentDescription = null)
                        },
                        onClick = {
                            onLanguageChange(tag)
                            menuOpen = false
                        },
                    )
                }
        }
    }
}

/**
 * Rings spreading out from the mic, like a drop in water, while it listens;
 * nothing otherwise.
 */
@Composable
internal fun Modifier.voiceWaves(listening: Boolean, color: Color): Modifier {
    if (!listening) return this
    val phase by rememberInfiniteTransition(label = "voice").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "waves",
    )
    return drawBehind {
        repeat(3) { ring ->
            val p = (phase + ring / 3f) % 1f
            drawCircle(color.copy(alpha = 0.35f * (1f - p)), radius = size.minDimension / 2f * (0.5f + 0.9f * p))
        }
    }
}
