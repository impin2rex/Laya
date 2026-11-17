package com.music.laya.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.laya.data.model.Song
import com.music.laya.data.recognition.MusicRecognizer
import com.music.laya.data.recognition.RecognitionError
import com.music.laya.data.recognition.RecognitionHistory
import com.music.laya.data.recognition.RecognitionShortcutHint
import com.music.laya.data.recognition.RecognitionState
import com.music.laya.data.recognition.RecognizedTrack
import com.music.laya.data.recognition.findOnLaya
import com.music.laya.platform.currentTimeMillis
import com.music.laya.sharedui.resources.*
import com.music.laya.ui.components.liquidGlassRim
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The icon every recognition entry point wears. */
val RecognitionIcon = Icons.Rounded.GraphicEq

/** Where this platform lets recognition sit outside the app, for the card that points it out. */
enum class RecognitionShortcut {
    /** Android's tile: [RecognitionScreen]'s onAddShortcut asks the system to add it. */
    QUICK_SETTINGS,

    /** iOS 18's control: no app may add it, so the card shows how. */
    CONTROL_CENTER,
}

/**
 * Music recognition: the listen button, the song it found, and every song
 * found before. [onListen] starts a session the platform's way (permission
 * first, a foreground service on Android); [onPlay] plays a matched song.
 * [shortcut]: a card invites adding recognition there, until added or closed.
 */
@Composable
fun RecognitionScreen(
    onListen: () -> Unit,
    onPlay: (Song) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    shortcut: RecognitionShortcut? = null,
    onAddShortcut: () -> Unit = {},
) {
    val state by MusicRecognizer.state.collectAsStateWithLifecycle()
    val history by RecognitionHistory.entries.collectAsStateWithLifecycle()
    val hintDismissed by RecognitionShortcutHint.dismissed.collectAsStateWithLifecycle()
    var showSteps by remember { mutableStateOf(false) }
    if (showSteps) {
        AlertDialog(
            onDismissRequest = { showSteps = false },
            title = { Text(stringResource(Res.string.recognize_shortcut_cc)) },
            text = { Text(stringResource(Res.string.recognize_shortcut_steps)) },
            confirmButton = {
                TextButton(onClick = {
                    showSteps = false
                    RecognitionShortcutHint.dismiss()
                }) { Text(stringResource(Res.string.recognize_shortcut_ok)) }
            },
        )
    }
    // A finished result is for this visit; the next one starts clean.
    DisposableEffect(Unit) { onDispose { MusicRecognizer.dismiss() } }

    val scope = rememberCoroutineScope()
    var finding by remember { mutableStateOf<String?>(null) }
    var missing by remember { mutableStateOf(setOf<String>()) }
    val play: (RecognizedTrack) -> Unit = { track ->
        if (finding == null) {
            finding = track.id
            scope.launch {
                val song = runCatching { findOnLaya(track) }.getOrNull()
                finding = null
                if (song != null) onPlay(song) else missing = missing + track.id
            }
        }
    }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "listen") {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 24.dp),
            ) {
                ListenOrb(
                    active = state.isActive,
                    onClick = { if (state.isActive) MusicRecognizer.cancel() else onListen() },
                )
                Spacer(Modifier.height(24.dp))
                AnimatedContent(
                    targetState = state,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    contentKey = { it::class },
                ) { s ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = statusText(s),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                        if (s.isActive) {
                            TextButton(onClick = MusicRecognizer::cancel) { Text(stringResource(Res.string.recognize_cancel)) }
                        } else if (s == RecognitionState.NotRecognized || s is RecognitionState.Failed) {
                            Button(onClick = onListen, modifier = Modifier.padding(top = 12.dp)) {
                                Text(stringResource(Res.string.recognize_try_again))
                            }
                        }
                    }
                }
            }
        }
        if (shortcut != null && !hintDismissed) {
            item(key = "shortcut") {
                ShortcutCard(
                    text = stringResource(
                        if (shortcut == RecognitionShortcut.QUICK_SETTINGS) Res.string.recognize_shortcut_qs
                        else Res.string.recognize_shortcut_cc,
                    ),
                    onTry = { if (shortcut == RecognitionShortcut.CONTROL_CENTER) showSteps = true else onAddShortcut() },
                    onClose = RecognitionShortcutHint::dismiss,
                )
            }
        }
        (state as? RecognitionState.Recognized)?.track?.let { track ->
            item(key = "result:${track.id}") {
                ResultCard(
                    track = track,
                    finding = finding == track.id,
                    missing = track.id in missing,
                    onPlay = { play(track) },
                )
            }
        }
        if (history.isNotEmpty()) {
            item(key = "history:header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 24.dp),
                ) {
                    Text(
                        stringResource(Res.string.recognize_history),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = RecognitionHistory::clear) { Text(stringResource(Res.string.recognize_history_clear)) }
                }
            }
            items(history, key = { "history:${it.id}" }) { track ->
                HistoryRow(
                    track = track,
                    finding = finding == track.id,
                    missing = track.id in missing,
                    onClick = { play(track) },
                    onRemove = { RecognitionHistory.remove(track) },
                )
            }
        }
    }
}

/** "Add … — Try it now", closable: the way into recognition without opening the app. */
@Composable
private fun ShortcutCard(text: String, onTry: () -> Unit, onClose: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onTry)
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        ) {
            Icon(RecognitionIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                stringResource(Res.string.recognize_shortcut_try),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.recognize_shortcut_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun statusText(state: RecognitionState): String = when (state) {
    RecognitionState.Idle, is RecognitionState.Recognized -> stringResource(Res.string.recognize_tap_to_listen)
    RecognitionState.Listening -> stringResource(Res.string.recognize_listening)
    RecognitionState.Identifying -> stringResource(Res.string.recognize_identifying)
    RecognitionState.NotRecognized -> stringResource(Res.string.recognize_not_found)
    is RecognitionState.Failed -> when (state.error) {
        RecognitionError.MICROPHONE_PERMISSION -> stringResource(Res.string.recognize_error_permission)
        RecognitionError.MICROPHONE_UNAVAILABLE -> stringResource(Res.string.recognize_error_microphone)
        RecognitionError.NETWORK -> stringResource(Res.string.recognize_error_network)
        RecognitionError.SERVICE -> stringResource(Res.string.recognize_error_service)
    }
}

/** The listen button: rings ripple out of it while the microphone is open. */
@Composable
private fun ListenOrb(active: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
        if (active) {
            val transition = rememberInfiniteTransition()
            repeat(3) { i ->
                val progress by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        tween(durationMillis = 1800, delayMillis = i * 600, easing = LinearEasing),
                        RepeatMode.Restart,
                    ),
                )
                Box(
                    Modifier
                        .size(140.dp)
                        .graphicsLayer {
                            val scale = 1f + progress * 0.55f
                            scaleX = scale
                            scaleY = scale
                            alpha = (1f - progress) * 0.35f
                        }
                        .background(color, CircleShape),
                )
            }
        }
        val pulse = if (active) {
            rememberInfiniteTransition().animateFloat(
                initialValue = 0.96f,
                targetValue = 1.04f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
            ).value
        } else {
            1f
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(140.dp)
                .graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                }
                .clip(CircleShape)
                .background(color)
                .liquidGlassRim(CircleShape)
                .clickable(onClick = onClick),
        ) {
            Icon(
                RecognitionIcon,
                contentDescription = stringResource(Res.string.recognize_title),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

@Composable
private fun ResultCard(track: RecognizedTrack, finding: Boolean, missing: Boolean, onPlay: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    ) {
        AsyncImage(
            model = track.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            track.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            track.artist,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        val details = listOfNotNull(track.album, track.releaseYear).joinToString(" · ")
        if (details.isNotEmpty()) {
            Text(
                details,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            recognizedAgo(track.recognizedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onPlay, enabled = !finding, modifier = Modifier.height(48.dp)) {
            if (finding) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(10.dp))
                Text(stringResource(Res.string.recognize_finding))
            } else {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(Res.string.recognize_play))
            }
        }
        if (missing) {
            Text(
                stringResource(Res.string.recognize_not_on_laya),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun HistoryRow(
    track: RecognizedTrack,
    finding: Boolean,
    missing: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !finding, onClick = onClick)
            .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        AsyncImage(
            model = track.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (missing) stringResource(Res.string.recognize_not_on_laya) else "${track.artist} · ${recognizedAgo(track.recognizedAt)}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (finding) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(12.dp).size(20.dp))
        } else {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.recognize_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun recognizedAgo(at: Long): String {
    val minutes = ((currentTimeMillis() - at) / 60_000).toInt()
    return when {
        minutes < 1 -> stringResource(Res.string.recognize_just_now)
        minutes < 60 -> stringResource(Res.string.recognize_minutes_ago, minutes)
        minutes < 24 * 60 -> stringResource(Res.string.recognize_hours_ago, minutes / 60)
        else -> stringResource(Res.string.recognize_days_ago, minutes / (24 * 60))
    }
}

