package com.music.laya.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.LocalPlatformContext
import com.music.laya.data.settings.AppSettings
import com.music.laya.sharedui.resources.*
import com.music.laya.ui.haptics.Haptic
import com.music.laya.ui.haptics.rememberHaptics
import com.music.laya.ui.replay.cmp.ShareAction
import com.music.laya.ui.share.LocalImageSharer
import com.music.laya.ui.share.LocalVideoSharer
import com.music.laya.ui.share.rememberPen
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The picked lines, drawn, with the things you can do to them: the phone's
 * `AndroidLyricsShareSheet`, on the player's own drawer.
 *
 * Opens as a 9:16 story video when the lines are synced and the platform has a [LocalVideoSharer]:
 * the picked stretch of the song with each line swept as it is sung, previewed live on the same
 * clock the export uses. Image is the still card. Unlike the phone there is no Canvas background
 * here: the video always sits on the cover's own backdrop.
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
    val videos = LocalVideoSharer.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val pen = rememberPen()
    val logo = painterResource(Res.drawable.ic_logo)
    val showLogo by AppSettings.lyricsCardLogo.collectAsStateWithLifecycle()

    // Synced lines carry where they are sung; without that nothing would move.
    val timeline = remember(request) { shareTimeline(request.lines) }
    val videoPossible = timeline != null && videos != null
    var asVideo by remember(request) { mutableStateOf(true) }
    val isVideo = asVideo && videoPossible

    var frames by remember(request) { mutableStateOf<LyricsCardFrames?>(null) }
    var image by remember(request) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(request) { mutableStateOf(false) }
    // Separate from [failed]: that one is the card's own render, shown as
    // "couldn't draw picture"; this is the save or share step after a picture
    // that drew fine, so it gets the generic error text instead.
    var actionFailed by remember(request) { mutableStateOf(false) }
    var saved by remember(request) { mutableStateOf(false) }
    var saving by remember(request) { mutableStateOf(false) }
    var story by remember(request) { mutableStateOf(true) }

    LaunchedEffect(request, story, showLogo) {
        frames = null
        image = null
        failed = false
        actionFailed = false
        saved = false
        runCatching {
            val laid = lyricsCardFrames(context, pen, logo, request, story, showLogo)
            image = laid.render(null)
            frames = laid
        }.onFailure { failed = true; println("LyricsShare: ${it.message ?: it}") }
    }

    // One export per look, reused by Save and Share alike.
    var exporting by remember(request) { mutableStateOf(false) }
    var silent by remember(request) { mutableStateOf(false) }
    var exported by remember(request) { mutableStateOf<Pair<Any, String>?>(null) }
    suspend fun videoFile(): String? {
        val laid = frames ?: return null
        val clock = timeline ?: return null
        val encoder = videos ?: return null
        val look = listOf(story, showLogo)
        exported?.takeIf { it.first == look }?.let { return it.second }
        exporting = true
        return try {
            encodeLyricsVideo(encoder, request, laid, clock)?.also { exported = look to it; silent = encoder.lastEncodeSilent() }
        } finally {
            exporting = false
        }
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

            if (videoPossible) {
                ShareChoice(
                    options = listOf(stringResource(Res.string.lyrics_share_video), stringResource(Res.string.lyrics_share_image)),
                    selected = if (asVideo) 0 else 1,
                    onSelect = {
                        haptics.play(Haptic.Tap)
                        asVideo = it == 0
                    },
                )
            }

            val bitmap = image
            val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: (9f / 16f)
            val preview = Modifier
                .fillMaxWidth((0.72f * ratio).coerceIn(0.36f, 0.92f))
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(18.dp))
            if (bitmap == null) {
                Box(modifier = preview.background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                    Spinner()
                }
            } else {
                Box(modifier = preview, contentAlignment = Alignment.Center) {
                    CardPreview(frames, bitmap, timeline.takeIf { isVideo }, Modifier.matchParentSize())
                    if (exporting) Spinner()
                }
            }

            ShareAction(
                label = stringResource(if (story) Res.string.lyrics_share_story_revert else Res.string.lyrics_share_story),
                icon = if (story) Icons.AutoMirrored.Rounded.Undo else Icons.Rounded.PhoneIphone,
                accent = false,
                enabled = image != null && !exporting,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    haptics.play(Haptic.Tap)
                    story = !story
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        haptics.play(Haptic.Tap)
                        AppSettings.setLyricsCardLogo(!showLogo)
                    }
                    .padding(start = 18.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.lyrics_share_show_logo),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = showLogo, onCheckedChange = null)
            }

            if (failed) {
                Text(
                    text = stringResource(Res.string.couldnt_draw_picture),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            if (actionFailed) {
                Text(
                    text = stringResource(Res.string.something_went_wrong),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            if (isVideo && exporting) {
                Text(
                    text = stringResource(Res.string.lyrics_share_making_video),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else if (isVideo && silent) {
                Text(
                    text = stringResource(Res.string.lyrics_share_video_silent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            val ready = image != null && !exporting && !saving && (if (isVideo) frames != null else sharer != null)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShareAction(
                    label = stringResource(if (saving) Res.string.lyrics_share_saving else Res.string.save),
                    icon = Icons.Rounded.Download,
                    accent = false,
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                    saved = saved,
                    savedLabel = stringResource(Res.string.saved_bang),
                    onClick = {
                        val drawn = image ?: return@ShareAction
                        haptics.play(Haptic.Tap)
                        saved = false
                        actionFailed = false
                        saving = true
                        scope.launch {
                            val name = "$LYRICS_FILE_PREFIX-${request.song.title}"
                            // A failed export or save must land here as `false`, not as an
                            // exception that skips `saving = false` and leaves the sheet stuck.
                            val done = try {
                                if (isVideo) {
                                    val path = videoFile()
                                    path != null && videos?.save(path, name) == true
                                } else {
                                    sharer?.saveToPhotos(drawn, name) == true
                                }
                            } catch (e: Exception) {
                                println("LyricsShare: save failed: ${e.message ?: e}")
                                false
                            } finally {
                                saving = false
                            }
                            actionFailed = !done
                            saved = done
                        }
                    },
                )
                ShareAction(
                    label = stringResource(Res.string.share),
                    icon = Icons.Rounded.IosShare,
                    accent = true,
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val drawn = image ?: return@ShareAction
                        haptics.play(Haptic.Tap)
                        actionFailed = false
                        scope.launch {
                            val sent = try {
                                if (isVideo) {
                                    videoFile()?.let { videos?.share(it) } == true
                                } else {
                                    sharer?.share(drawn, LYRICS_FILE_NAME) == true
                                }
                            } catch (e: Exception) {
                                println("LyricsShare: share failed: ${e.message ?: e}")
                                false
                            }
                            if (!sent) {
                                actionFailed = true
                                saved = false
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * The card as an MP4 through [encoder]: one frame per 1/[VIDEO_FPS] s of [timeline], each drawn
 * at the moment of the song it plays, with the song cut to the same stretches underneath.
 * H.264 wants even sides, so an odd card height loses its last row.
 */
internal suspend fun encodeLyricsVideo(
    encoder: com.music.laya.ui.share.VideoSharer,
    request: LyricsShareRequest,
    frames: LyricsCardFrames,
    timeline: ShareTimeline,
): String? {
    val width = frames.width and 1.inv()
    val height = frames.height and 1.inv()
    val target = ImageBitmap(frames.width, frames.height)
    val canvas = Canvas(target)
    val draw = CanvasDrawScope()
    val size = Size(frames.width.toFloat(), frames.height.toFloat())
    return encoder.encode(
        song = request.song,
        width = width,
        height = height,
        fps = VIDEO_FPS,
        frames = timeline.frameCount(VIDEO_FPS),
        segments = timeline.segments,
    ) { index, argb ->
        withContext(Dispatchers.Default) {
            draw.draw(Density(1f), LayoutDirection.Ltr, canvas, size) {
                frames.drawOn(this, timeline.trackMsAtFrame(index, VIDEO_FPS))
            }
            target.readPixels(argb, width = width, height = height)
        }
    }
}

/** Two choices as a pair of pills, the selected one solid. */
@Composable
private fun ShareChoice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) Color.White.copy(alpha = 0.92f) else Color.Transparent)
                    .clickable { if (!on) onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.W600,
                    color = if (on) Color(0xFF121212) else Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun Spinner() {
    CircularProgressIndicator(
        modifier = Modifier.size(26.dp),
        strokeWidth = 2.5.dp,
        color = Color.White.copy(alpha = 0.75f),
    )
}

/**
 * The card as the video will play it: [frames] drawn every frame at the moment of the song the
 * loop has reached. The still [card] when there is no vocal to follow, or until it is laid out.
 */
@Composable
private fun CardPreview(frames: LyricsCardFrames?, card: ImageBitmap, timeline: ShareTimeline?, modifier: Modifier) {
    val description = stringResource(Res.string.your_lyrics_card)
    if (frames == null || timeline == null) {
        Image(bitmap = card, contentDescription = description, modifier = modifier)
        return
    }
    var elapsedMs by remember(frames, timeline) { mutableLongStateOf(0L) }
    LaunchedEffect(frames, timeline) {
        val start = withFrameMillis { it }
        val length = (timeline.durationMs + PREVIEW_REST_MS).coerceAtLeast(1L)
        while (true) withFrameMillis { elapsedMs = (it - start) % length }
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        val at = timeline.trackMsAt(elapsedMs * 1000)
        scale(size.width / frames.width, pivot = Offset.Zero) { frames.drawOn(this, at) }
    }
}

/** Different from the Replay's, so a lyrics share and a replay share cannot overwrite each other. */
private const val LYRICS_FILE_NAME = "lyrics.png"
private const val LYRICS_FILE_PREFIX = "laya-lyrics"

/** The share video's frame rate: smooth enough for the sweep, half the work of 60. */
private const val VIDEO_FPS = 30

/** A beat on the finished verse before the preview loops back to the start. */
private const val PREVIEW_REST_MS = 1_200L
