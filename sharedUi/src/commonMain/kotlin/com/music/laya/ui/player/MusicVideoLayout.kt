package com.music.laya.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import com.music.laya.sharedui.resources.Res
import com.music.laya.sharedui.resources.video_quality
import com.music.laya.sharedui.resources.video_quality_auto
import com.music.laya.sharedui.resources.audio_short
import com.music.laya.sharedui.resources.picture_in_picture
import com.music.laya.sharedui.resources.switch_to_audio
import com.music.laya.ui.icons.LayaIcons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.music.laya.ui.haptics.Haptic
import com.music.laya.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.laya.ui.components.backdrop.Backdrop
import com.music.laya.ui.components.backdrop.backdrops.layerBackdrop
import com.music.laya.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.music.laya.ui.components.backdrop.drawBackdrop
import com.music.laya.ui.components.backdrop.effects.blur
import com.music.laya.ui.components.backdrop.effects.colorControls
import com.music.laya.ui.components.backdrop.effects.lens
import com.music.laya.ui.components.backdrop.highlight.Highlight
import com.music.laya.ui.components.backdrop.shadow.Shadow
import com.music.laya.ui.components.isGlassSupported
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.music.laya.data.model.Song
import com.music.laya.ui.theme.HideSystemBars
import kotlinx.coroutines.delay

/** How long the controls stay over a playing video untouched. */
private const val VIDEO_CONTROLS_IDLE_MS = 3_500L

/**
 * A music video on a phone held sideways: the picture is the whole screen,
 * letterboxed in black, with the system bars away. A tap brings the controls
 * up over it — the title, the scrubber and the transport — and they stand
 * down again a few seconds after the last touch while it plays, as in every
 * video player. [onExitFullscreen] goes back to the ordinary landscape player.
 */
@Composable
internal fun FullscreenVideoPlayer(
    song: Song,
    isPlaying: Boolean,
    artRequest: ImageRequest,
    onRenderedChanged: (Boolean) -> Unit,
    rendered: Boolean,
    onExitFullscreen: () -> Unit,
    onOpenQuality: () -> Unit,
    scrubber: @Composable () -> Unit,
    transport: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Back to the song's audio-only cut, where it has one — see [SwitchToAudioPill]. */
    onSwitchToAudio: (() -> Unit)? = null,
    switchingVersion: Boolean = false,
) {
    HideSystemBars(true)
    var controlsShown by remember { mutableStateOf(true) }
    // Bumped by every touch on the controls, so the idle timer starts over.
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(controlsShown, isPlaying, touches) {
        if (controlsShown && isPlaying) {
            delay(VIDEO_CONTROLS_IDLE_MS)
            controlsShown = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { controlsShown = !controlsShown },
    ) {
        // What the glass pill frosts and bends: the picture, or the thumbnail before it.
        val pictureBackdrop = rememberLayerBackdrop()
        Box(Modifier.fillMaxSize().layerBackdrop(pictureBackdrop)) {
            // Black with a spinner until the picture's first frame lands.
            if (!rendered) VideoLoadingBackdrop(artRequest, Modifier.fillMaxSize())
            MusicVideoSurface(
                videoId = song.videoId,
                fit = true,
                onRenderedChanged = onRenderedChanged,
                modifier = Modifier.fillMaxSize(),
            )
        }

        AnimatedVisibility(
            visible = controlsShown,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.62f),
                            0.30f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.78f),
                        ),
                    )
                    // Any touch on the controls starts the idle timer over.
                    // Watched, not taken: a tap on bare picture still reaches
                    // the box below and puts them away.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                touches++
                            }
                        }
                    }
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Row(
                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = song.artist,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (onSwitchToAudio != null) {
                        SwitchToAudioPill(onClick = onSwitchToAudio, switching = switchingVersion, backdrop = pictureBackdrop)
                        Spacer(Modifier.width(8.dp))
                    }
                    VideoCornerControls(fullscreen = true, onToggleFullscreen = onExitFullscreen, onOpenQuality = onOpenQuality)
                }
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 420.dp),
                ) {
                    transport()
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    scrubber()
                }
            }
        }
    }
}

/**
 * The way back from a music video to the song alone: a small capsule of
 * frosted glass floating on the picture's corner, rather than a row of the
 * player given over to it.
 *
 * Glass by layering, not by blur: the picture underneath is a hardware video
 * surface the blur can't sample, so the frost is a dark translucent body (what
 * keeps white text legible on a bright frame), a lit top edge, and a hairline
 * rim that catches the light on a dark one — with a soft shadow lifting it
 * off either. It eases in, gives a little under the finger, and swaps its
 * glyph for a spinner while the audio cut is being found and lined up.
 */
@Composable
internal fun SwitchToAudioPill(
    onClick: () -> Unit,
    switching: Boolean,
    /** The picture behind the pill, for its glass to bend and frost; null draws the plain pill. */
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "switchToAudioPress",
    )
    val shape = RoundedCornerShape(percent = 50)
    val label = stringResource(Res.string.switch_to_audio)
    // Mounted with the video, so it arrives with it rather than popping in.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    AnimatedVisibility(
        visible = entered,
        enter = fadeIn(tween(260)) + scaleIn(tween(260), initialScale = 0.85f),
        exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.85f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .videoGlass(backdrop, shape)
                .clickable(interactionSource = interaction, indication = null, enabled = !switching) {
                    haptics.play(Haptic.Tap)
                    onClick()
                }
                .semantics { contentDescription = label }
                .height(36.dp)
                .padding(start = 12.dp, end = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = switching,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                label = "switchToAudioGlyph",
            ) { busy ->
                if (busy) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 1.75.dp,
                        modifier = Modifier.size(15.dp),
                    )
                } else {
                    Icon(
                        imageVector = LayaIcons.MusicNote,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.audio_short),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/**
 * Apple's clear glass over the picture: what is behind frosted, bent at the rim and lit along its
 * edge, with only a breath of white over it so the picture's own colour comes through. Where the
 * platform can't sample the picture (Android before 12), the same pill drawn as a translucent
 * gradient with a lit hairline.
 */
@Composable
private fun Modifier.videoGlass(backdrop: Backdrop?, shape: RoundedCornerShape): Modifier {
    if (backdrop == null || !isGlassSupported()) {
        return shadow(elevation = 10.dp, shape = shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.22f))
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.30f),
                    0.5f to Color.White.copy(alpha = 0.16f),
                    1f to Color.White.copy(alpha = 0.10f),
                ),
            )
            .border(
                width = 0.75.dp,
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.65f),
                    1f to Color.White.copy(alpha = 0.18f),
                ),
                shape = shape,
            )
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1.4f)
            blur(10.dp.toPx())
            lens(
                refractionHeight = 12.dp.toPx(),
                refractionAmount = 18.dp.toPx(),
                depthEffect = true,
                chromaticAberration = true,
            )
        },
        highlight = { Highlight.Default },
        shadow = { Shadow.Default },
        onDrawSurface = { drawRect(Color.White.copy(alpha = 0.10f)) },
    )
}

/**
 * What stands in for a music video until its first frame lands, as YouTube
 * does it: the video's own thumbnail, darkened, with a spinner over it — the
 * picture is there at once, and the frames take over from it. Black with the
 * spinner when there is no thumbnail. Shared by the portrait card and the
 * full-screen player.
 */
@Composable
internal fun VideoLoadingBackdrop(artRequest: ImageRequest?, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (artRequest != null) {
            AsyncImage(
                model = artRequest,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }
        CircularProgressIndicator(
            color = Color.White,
            strokeWidth = 3.dp,
            modifier = Modifier.size(36.dp),
        )
    }
}

/**
 * The two glass boxes in a music video's top-right corner: the quality box —
 * the web player's VideoQuality, which reads the rendition on screen and opens
 * the quality drawer — and the full-screen button beside it.
 */
@Composable
internal fun VideoCornerControls(
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onOpenQuality: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoQualityBox(onClick = onOpenQuality)
        PlayerPlatform.host.pictureInPicture?.let { enter ->
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.38f))
                    .clickable(onClick = enter),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PictureInPictureAlt,
                    contentDescription = stringResource(Res.string.picture_in_picture),
                    tint = Color.White,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.38f))
                .clickable(onClick = onToggleFullscreen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * What the quality box and drawer say is playing: the held height, else the one on screen — and
 * the one on screen when it is lower than the pick, which this device couldn't play.
 */
private fun MusicVideoQualityUi.selected(): Int? = when {
    held == null || held !in heights -> activeHeight
    activeHeight != null && activeHeight < held -> activeHeight
    else -> held
}

/**
 * The playing video's quality: 360p, 480p, HD, FullHD, 2K, 4K — what is on
 * screen, which is not always what was picked (4K held, then a video shot in
 * 1080p). Nothing until the renditions are known and there is a choice to make.
 */
@Composable
private fun VideoQualityBox(onClick: () -> Unit) {
    val quality by PlayerPlatform.host.musicVideoQuality.collectAsStateWithLifecycle()
    if (quality.heights.size < 2) return
    val label = quality.selected()?.let(::videoQualityLabel) ?: "Auto"
    Box(
        modifier = Modifier
            .height(30.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = label,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
            label = "videoQualityLabel",
        ) { text ->
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * The quality list, in the same drawer as the audio output: Auto, then every
 * height this video offers, the one playing marked. Picking one holds it from
 * now on — for the next video too, where that video has it.
 */
@Composable
internal fun VideoQualitySheet(hazeState: HazeState, onDismiss: () -> Unit) {
    val quality by PlayerPlatform.host.musicVideoQuality.collectAsStateWithLifecycle()
    val selected = quality.selected()
    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(Res.string.video_quality),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QualityRow(
                name = stringResource(Res.string.video_quality_auto),
                detail = if (quality.held == null) selected?.let(::videoQualityLabel) else null,
                active = quality.held == null,
            ) {
                PlayerPlatform.host.setMusicVideoQuality(null)
                onDismiss()
            }
            quality.heights.forEach { height ->
                QualityRow(
                    name = videoQualityLabel(height),
                    detail = "${height}p",
                    active = quality.held != null && selected == height,
                ) {
                    PlayerPlatform.host.setMusicVideoQuality(height)
                    onDismiss()
                }
            }
        }
    }
}

/** One row of [VideoQualitySheet], drawn as the audio output drawer draws an output. */
@Composable
private fun QualityRow(name: String, detail: String?, active: Boolean, onSelect: () -> Unit) {
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = if (active) 0.10f else 0.05f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !active,
            ) {
                haptics.play(Haptic.Select)
                onSelect()
            }
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = Color.White.copy(alpha = if (active) 1f else 0.85f),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (active) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
