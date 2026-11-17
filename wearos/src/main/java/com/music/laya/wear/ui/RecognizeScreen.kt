package com.music.laya.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.music.laya.wear.R
import com.music.laya.wear.data.Recognition
import com.music.laya.wear.data.Recognizer

/**
 * "What's playing?": tap the mark to listen through the watch's microphone,
 * the phone names the song. [startNow]: opened from the tile, so listening
 * begins without a tap. [onPlayed]: called once the phone's player has moved
 * to the song after Play ([playingId] changed), not before — the phone may yet
 * answer that Laya hasn't got it.
 */
@Composable
fun RecognizeScreen(recognizer: Recognizer, startNow: Boolean, playingId: String?, onPlayed: () -> Unit) {
    val context = LocalContext.current
    val state by recognizer.state.collectAsStateWithLifecycle()
    // The phone's track when Play was tapped; a different one means it's playing.
    var playedOver by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(false) }
    LaunchedEffect(playingId, waiting) {
        if (waiting && playingId != null && playingId != playedOver) onPlayed()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recognizer.start()
    }
    val listen = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            recognizer.start()
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    DisposableEffect(Unit) {
        if (startNow) listen()
        onDispose { if (recognizer.state.value.isActive) recognizer.cancel() else recognizer.dismiss() }
    }

    ScreenScaffold {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val result = state as? Recognition.Recognized
            if (result != null) {
                Result(
                    result,
                    onPlay = {
                        playedOver = playingId
                        waiting = true
                        recognizer.play(result)
                    },
                    onAgain = { waiting = false; listen() },
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Orb(active = state.isActive, onClick = { if (state.isActive) recognizer.cancel() else listen() })
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(
                            when (state) {
                                Recognition.Listening -> R.string.recognize_listening
                                Recognition.Identifying -> R.string.recognize_identifying
                                Recognition.NotFound -> R.string.recognize_not_found
                                Recognition.Failed -> R.string.recognize_failed
                                Recognition.NoPhone -> R.string.no_phone_title
                                Recognition.NoMicrophone -> R.string.recognize_no_microphone
                                else -> R.string.recognize_tap
                            },
                        ),
                        style = WearType.subtitle,
                        color = Secondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 28.dp),
                    )
                }
            }
        }
    }
}

/** The Laya mark as the listen button; rings ripple out while it listens. */
@Composable
private fun Orb(active: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
        if (active) {
            val transition = rememberInfiniteTransition()
            repeat(2) { i ->
                val progress by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1600, delayMillis = i * 800), RepeatMode.Restart),
                )
                Box(
                    Modifier
                        .size(96.dp)
                        .graphicsLayer {
                            val scale = 0.7f + 0.5f * progress
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - progress
                        }
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f)),
                )
            }
        }
        Box(
            Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_notification),
                contentDescription = stringResource(R.string.recognize),
                tint = Color.Black,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

@Composable
private fun Result(result: Recognition.Recognized, onPlay: () -> Unit, onAgain: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    ) {
        result.art?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(result.title, style = WearType.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(result.artist, style = WearType.rowSub, color = Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        if (result.notOnLaya) {
            Text(stringResource(R.string.recognize_not_on_laya), style = WearType.rowSub, color = Secondary, textAlign = TextAlign.Center)
        } else {
            Pill(text = stringResource(R.string.play), onClick = onPlay)
        }
        Text(
            stringResource(R.string.recognize_again),
            style = WearType.button,
            color = Secondary,
            modifier = Modifier.padding(top = 6.dp).clickable(role = Role.Button, onClick = onAgain).padding(6.dp),
        )
    }
}
