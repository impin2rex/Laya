package com.music.laya.ui.player

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.IosShare
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.laya.R
import com.music.laya.data.DebugLog
import com.music.laya.data.canvas.CanvasArtwork
import com.music.laya.data.canvas.CanvasRepository
import com.music.laya.data.settings.AppSettings
import com.music.laya.ui.haptics.Haptic
import com.music.laya.ui.haptics.rememberHaptics
import com.music.laya.ui.replay.ShareAction
import com.music.laya.ui.replay.cacheForSharing
import com.music.laya.ui.replay.saveToGallery
import com.music.laya.ui.replay.sendIntent
import com.music.laya.ui.screens.SegmentedControl
import dev.chrisbanes.haze.HazeState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The picked lines, drawn, with the two things you can do to the picture.
 *
 * Built on [PlayerDrawer] rather than on a `ModalBottomSheet` for the same reason
 * as every other sheet in this package: it is inside the player, which is itself
 * already a modal sheet, and nesting the two leaves the inner one with nothing
 * to be modal over. It also means this drawer gets the player's own frosted
 * material, the drag-to-dismiss gesture and the scrim fade for free.
 *
 * Opens as a 9:16 story video: the picked stretch of the song with the lines
 * swept exactly as the lyrics panel sweeps them (see [LyricsCardPainter]), on
 * the cover's backdrop or the song's Canvas. Image is the still card, every
 * line lit white. The render happens on a background dispatcher; the preview
 * runs the same painter on the song's clock, so what is shared is what was seen.
 */
@Composable
internal fun AndroidLyricsShareSheet(
    hazeState: HazeState,
    request: LyricsShareRequest,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    var image by remember(request) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(request) { mutableStateOf(false) }
    // Separate from [failed]: that one is the card's own render, shown as
    // "couldn't draw picture"; this is the save or share step after a picture
    // that drew fine, so it gets the generic error text instead.
    var actionFailed by remember(request) { mutableStateOf(false) }
    var saved by remember(request) { mutableStateOf(false) }
    // The 9:16 story is what these are mostly made for, so it is where the
    // sheet opens; the card cut to its lines is one tap away.
    var story by remember(request) { mutableStateOf(true) }
    // Remembered across shares: someone who takes the mark off wants it off.
    val showLogo by AppSettings.lyricsCardLogo.collectAsStateWithLifecycle()

    // The song's animated cover, offered as a background when it has one. The
    // player's own clip is in hand already; otherwise it is looked up once.
    var canvas by remember(request) { mutableStateOf(CanvasRepository.cached(request.song)) }
    LaunchedEffect(request) {
        if (canvas == null) canvas = runCatching { CanvasRepository.canvasFor(request.song) }.getOrNull()
    }
    var onCanvas by remember(request) { mutableStateOf(false) }
    // Video — the song, the words sweeping with it — or Image: the still card.
    var asVideo by remember(request) { mutableStateOf(true) }
    val clip: CanvasArtwork? = canvas?.takeIf { onCanvas && asVideo }
    // Synced lines carry where they are sung: the share becomes a video of
    // that stretch of the song, each line lighting up as the vocal reaches it.
    val timeline = remember(request) { shareTimeline(request.lines) }
    // Nothing would move in a video with no vocal to follow and no clip.
    val videoPossible = timeline != null || canvas != null
    val isVideo = asVideo && (clip != null || timeline != null)
    // Saving runs from the tap until the file is in the gallery: the export,
    // then the copy. "Saved!" only once both are done.
    var saving by remember(request) { mutableStateOf(false) }

    var painter by remember(request) { mutableStateOf<LyricsCardPainter?>(null) }
    LaunchedEffect(request, story, showLogo, onCanvas, asVideo) {
        image = null
        painter = null
        failed = false
        actionFailed = false
        saved = false
        runCatching {
            val paint = lyricsCardPainter(context, request, story, showLogo, transparent = clip != null)
            image = withContext(Dispatchers.Default) { paint.render(null) }
            painter = paint
        }.onFailure {
            DebugLog.w(TAG, "lyrics card render failed", it)
            failed = true
        }
    }

    // One export per look, reused by Save and Share alike.
    var exporting by remember(request) { mutableStateOf(false) }
    var silent by remember(request) { mutableStateOf(false) }
    var exported by remember(request) { mutableStateOf<Pair<Any, File>?>(null) }
    suspend fun videoFile(): File? {
        val look = listOf(clip, story, showLogo)
        exported?.takeIf { it.first == look && it.second.exists() }?.let { return it.second }
        exporting = true
        return try {
            // The export lays its own background — the clip, or the backdrop —
            // so the card it is handed is always the bare one.
            val overlay = lyricsCardPainter(context, request, story, showLogo, transparent = true)
            silent = false
            exportLyricsVideo(context, request, clip, overlay, onSilent = { silent = true })?.also { exported = look to it }
        } finally {
            exporting = false
        }
    }

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(R.string.lyrics_share_title),
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
                text = stringResource(R.string.lyrics_share_description),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            )

            // Unsynced lyrics with no Canvas have nothing to move: Image alone.
            if (videoPossible) {
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.lyrics_share_video),
                        stringResource(R.string.lyrics_share_image),
                    ),
                    selectedIndex = if (asVideo) 0 else 1,
                    onSelect = {
                        haptics.play(Haptic.Tap)
                        asVideo = it == 0
                    },
                )
            }
            if (asVideo && canvas != null) {
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.lyrics_share_gradient),
                        stringResource(R.string.lyrics_share_canvas),
                    ),
                    selectedIndex = if (onCanvas) 1 else 0,
                    onSelect = {
                        haptics.play(Haptic.Tap)
                        onCanvas = it == 1
                    },
                )
            }

            val bitmap = image
            // The card is only as tall as its lines, so the frame follows it.
            // Sized off the screen's height so the whole drawer, actions and
            // all, fits.
            val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: (9f / 16f)
            val previewH = (LocalConfiguration.current.screenHeightDp * PREVIEW_SCREEN_SHARE).dp
            val preview = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentWidth()
                .height(previewH)
                .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(18.dp))
            if (bitmap == null) {
                Box(
                    modifier = preview.background(Color.White.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.5.dp,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            } else {
                Box(modifier = preview, contentAlignment = Alignment.Center) {
                    if (clip != null) {
                        // The clip live under the card, blurred and dimmed the
                        // way the export draws it.
                        CanvasArtworkPlayer(
                            canvas = clip,
                            isPlaying = true,
                            modifier = Modifier.matchParentSize().blur(PREVIEW_BLUR),
                        )
                        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = CLIP_SCRIM)))
                    }
                    CardPreview(painter, bitmap, timeline.takeIf { isVideo }, Modifier.matchParentSize())
                    if (exporting) ExportSpinner()
                }
            }

            ShareAction(
                label = stringResource(if (story) R.string.lyrics_share_story_revert else R.string.lyrics_share_story),
                icon = if (story) Icons.Rounded.Image else ImageVector.vectorResource(R.drawable.ic_instagram),
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
                    .toggleable(
                        value = showLogo,
                        role = Role.Switch,
                        onValueChange = {
                            haptics.play(Haptic.Tap)
                            AppSettings.setLyricsCardLogo(it)
                        },
                    )
                    .padding(start = 18.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.lyrics_share_show_logo),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // The row is the toggle; the switch only shows its state.
                Switch(checked = showLogo, onCheckedChange = null)
            }

            if (isVideo && silent && !exporting) {
                Text(
                    text = stringResource(R.string.lyrics_share_video_silent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            if (failed) {
                Text(
                    text = stringResource(R.string.couldnt_draw_picture),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            if (actionFailed) {
                Text(
                    text = stringResource(R.string.something_went_wrong),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ShareAction(
                    label = stringResource(if (saving) R.string.lyrics_share_saving else R.string.save),
                    icon = Icons.Rounded.Download,
                    accent = false,
                    enabled = image != null && !exporting && !saving,
                    modifier = Modifier.weight(1f),
                    // Once the file is in the gallery the button says so itself.
                    saved = saved,
                    savedLabel = stringResource(R.string.saved_bang),
                    onClick = {
                        val drawn = image
                        if (drawn != null) {
                            haptics.play(Haptic.Tap)
                            saved = false
                            actionFailed = false
                            saving = true
                            scope.launch {
                                // A failed export or write must land here as `false`, not as an
                                // exception that skips `saving = false` and crashes the sheet —
                                // the video pipeline (Media3 Transformer, device codecs, a flaky
                                // network for the song's Opus) can throw, not just return null.
                                val done = try {
                                    if (isVideo) {
                                        val video = videoFile()
                                        video != null && saveVideoToGallery(context, video, request.song.title)
                                    } else {
                                        saveToGallery(context, drawn, request.song.title, LYRICS_FILE_PREFIX)
                                    }
                                } catch (e: Exception) {
                                    DebugLog.w(TAG, "lyrics video save failed", e)
                                    false
                                } finally {
                                    saving = false
                                }
                                actionFailed = !done
                                saved = done
                            }
                        }
                    },
                )
                ShareAction(
                    label = stringResource(R.string.share),
                    icon = Icons.Rounded.IosShare,
                    accent = true,
                    enabled = image != null && !exporting && !saving,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val drawn = image
                        if (drawn != null) {
                            haptics.play(Haptic.Tap)
                            actionFailed = false
                            scope.launch {
                                val send = try {
                                    if (isVideo) {
                                        videoFile()?.let { shareVideoIntent(context, it) }
                                    } else {
                                        cacheForSharing(context, drawn, LYRICS_FILE_NAME)?.let(::sendIntent)
                                    }
                                } catch (e: Exception) {
                                    DebugLog.w(TAG, "lyrics video share failed", e)
                                    null
                                }
                                if (send == null) {
                                    actionFailed = true
                                    saved = false
                                } else {
                                    context.startActivity(
                                        Intent.createChooser(send, context.getString(R.string.share_your_lyrics)),
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Two names, deliberately not the Replay's.
 *
 * The cache name so a replay share and a lyrics share cannot overwrite each
 * other while the receiving app is still reading the first, and the gallery
 * prefix so a saved card in the pictures folder says which feature produced it.
 */
private const val TAG = "LyricsShareSheet"
private const val LYRICS_FILE_NAME = "lyrics.png"
private const val LYRICS_FILE_PREFIX = "laya-lyrics"

/** Over the preview while the video is being made. */
@Composable
private fun ExportSpinner() {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            strokeWidth = 2.5.dp,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

/**
 * The card as the video will play it: [painter] drawn every frame at the
 * moment of the song the loop has reached — the syllable sweep, the lifts and
 * the held words, as in the player. The still [card] when there's no vocal to
 * follow, or until the painter is ready.
 */
@Composable
private fun CardPreview(
    painter: LyricsCardPainter?,
    card: Bitmap,
    timeline: ShareTimeline?,
    modifier: Modifier,
) {
    val description = stringResource(R.string.your_lyrics_card)
    if (painter == null || timeline == null) {
        Image(bitmap = card.asImageBitmap(), contentDescription = description, modifier = modifier)
        return
    }
    var elapsedMs by remember(painter, timeline) { mutableLongStateOf(0L) }
    LaunchedEffect(painter, timeline) {
        val start = withFrameMillis { it }
        val length = (timeline.durationMs + PREVIEW_REST_MS).coerceAtLeast(1L)
        while (true) withFrameMillis { elapsedMs = (it - start) % length }
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        val at = timeline.trackMsAt(elapsedMs * 1000)
        val scale = size.width / painter.width
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.scale(scale, scale)
            painter.drawOn(native, at)
            native.restore()
        }
    }
}

/** The preview's height, as a share of the screen's. */
private const val PREVIEW_SCREEN_SHARE = 0.3f

/** A beat on the finished verse before the preview loops back to the start. */
private const val PREVIEW_REST_MS = 1_200L

/** The black over the Canvas clip in the preview: the export's 60% dim. */
private const val CLIP_SCRIM = 0.4f

/** About the blur the export draws, at the preview's size. */
private val PREVIEW_BLUR = 10.dp
