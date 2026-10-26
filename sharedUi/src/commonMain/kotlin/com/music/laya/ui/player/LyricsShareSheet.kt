package com.music.laya.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import com.music.laya.sharedui.resources.*
import com.music.laya.ui.haptics.Haptic
import com.music.laya.ui.haptics.rememberHaptics
import com.music.laya.ui.replay.cmp.ShareAction
import com.music.laya.ui.share.LocalImageSharer
import com.music.laya.ui.share.rememberPen
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The picked lines, drawn, with the things you can do to the picture: the phone's
 * `AndroidLyricsShareSheet`, on the player's own drawer. The preview is a plain image of the
 * bitmap that Save and Share then hand to the platform.
 */
@Composable
fun LyricsShareSheetContent(
    hazeState: HazeState,
    request: LyricsShareRequest,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    val sharer = LocalImageSharer.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val pen = rememberPen()
    val logo = painterResource(Res.drawable.ic_logo)

    var image by remember(request) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(request) { mutableStateOf(false) }
    var saved by remember(request) { mutableStateOf(false) }
    var story by remember(request) { mutableStateOf(false) }

    LaunchedEffect(request, story) {
        image = null
        failed = false
        saved = false
        image = runCatching { renderLyricsShareCard(context, pen, logo, request, story) }
            .onFailure { failed = true; println("LyricsShare: ${it.message ?: it}") }
            .getOrNull()
    }

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(Res.string.lyrics_share_title),
        onDismiss = onDismiss,
        modifier = modifier,
        titleGap = 2.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Res.string.lyrics_share_description),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            )

            val bitmap = image
            val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: (9f / 16f)
            val preview = Modifier
                .fillMaxWidth((0.72f * ratio).coerceIn(0.36f, 0.92f))
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(18.dp))
            if (bitmap == null) {
                Box(modifier = preview.background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.5.dp,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            } else {
                Image(bitmap = bitmap, contentDescription = stringResource(Res.string.your_lyrics_card), modifier = preview)
            }

            ShareAction(
                label = stringResource(if (story) Res.string.lyrics_share_story_revert else Res.string.lyrics_share_story),
                icon = if (story) Icons.AutoMirrored.Rounded.Undo else Icons.Rounded.PhoneIphone,
                accent = false,
                enabled = image != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    haptics.play(Haptic.Tap)
                    story = !story
                },
            )

            if (failed) {
                Text(
                    text = stringResource(Res.string.couldnt_draw_picture),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShareAction(
                    label = stringResource(Res.string.save),
                    icon = Icons.Rounded.Download,
                    accent = false,
                    enabled = image != null && sharer != null,
                    modifier = Modifier.weight(1f),
                    saved = saved,
                    savedLabel = stringResource(Res.string.saved_bang),
                    onClick = {
                        val drawn = image
                        if (drawn != null) {
                            haptics.play(Haptic.Tap)
                            scope.launch {
                                saved = sharer?.saveToPhotos(drawn, "$LYRICS_FILE_PREFIX-${request.song.title}") == true
                            }
                        }
                    },
                )
                ShareAction(
                    label = stringResource(Res.string.share),
                    icon = Icons.Rounded.IosShare,
                    accent = true,
                    enabled = image != null && sharer != null,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val drawn = image
                        if (drawn != null) {
                            haptics.play(Haptic.Tap)
                            scope.launch {
                                if (sharer?.share(drawn, LYRICS_FILE_NAME) != true) {
                                    failed = true
                                    saved = false
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/** Different from the Replay's, so a lyrics share and a replay share cannot overwrite each other. */
private const val LYRICS_FILE_NAME = "lyrics.png"
private const val LYRICS_FILE_PREFIX = "laya-lyrics"
