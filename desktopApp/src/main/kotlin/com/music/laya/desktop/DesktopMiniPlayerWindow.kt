package com.music.laya.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import com.music.laya.data.canvas.CanvasArtwork
import com.music.laya.data.model.ROW_ART_PX
import com.music.laya.ui.player.CanvasArtworkPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.music.laya.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import laya.desktopapp.generated.resources.Res
import laya.desktopapp.generated.resources.logo
import org.jetbrains.compose.resources.painterResource
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

/**
 * Apple Music's mini player: a small floating window, above every other, that stands in for the
 * main window while it is put away. Two shapes — the artwork filling a tall card with the controls
 * over its blurred foot, or a compact glass strip — switched from its menu or its cover.
 */
internal object DesktopMiniWindow {
    val open = MutableStateFlow(false)
    val compact = MutableStateFlow(false)

    /** Pushed by the app on every composition; the window only reads them. */
    val liked = MutableStateFlow(false)
    val videoId = MutableStateFlow<String?>(null)
    var onTogglePlay: () -> Unit = {}
    var onPrevious: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onSeek: (Long) -> Unit = {}
    var onToggleLike: () -> Unit = {}
    var onOpenQueue: () -> Unit = {}
    var onOpenAudioOutput: () -> Unit = {}

    /** In place of the main window, as Apple Music's Switch to Mini Player does. */
    fun show() {
        open.value = true
        DesktopWindowVisibility.hide()
        DesktopTrayMenu.refresh()
    }

    /** Back to the main window, which is what closing the mini player means. */
    fun close() = DesktopWindowVisibility.show()
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopMiniPlayerWindow() {
    val open by DesktopMiniWindow.open.collectAsState()
    if (!open) return
    val compact by DesktopMiniWindow.compact.collectAsState()
    // The top right corner of the screen the main window was on, clear of the menu bar, where
    // Apple Music first puts its own.
    val state = remember {
        val config = DesktopWindowVisibility.window?.graphicsConfiguration
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val area = usableArea(config)
        val size = miniSize(compact, area)
        WindowState(size = size, position = miniPosition(size, area, right = area.maxX.toFloat() - EDGE_DP, top = area.y + EDGE_DP))
    }
    Window(
        onCloseRequest = DesktopMiniWindow::close,
        state = state,
        title = "Laya",
        icon = painterResource(Res.drawable.logo),
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        resizable = false,
        // Esc, ⌘W, or the shortcut that opened it — ⌥⌘M, Ctrl+Alt+M elsewhere — as Apple Music's.
        onPreviewKeyEvent = { event ->
            val close = event.type == KeyEventType.KeyDown && (
                event.key == Key.Escape ||
                    (DesktopPlatform.isMac && event.isMetaPressed && event.key == Key.W) ||
                    (event.key == Key.M && event.isAltPressed &&
                        (if (DesktopPlatform.isMac) event.isMetaPressed else event.isCtrlPressed))
                )
            if (close) DesktopMiniWindow.close()
            close
        },
    ) {
        // A switch of shape keeps the right edge where it was, as the card is docked from the right,
        // and never leaves any of it off the screen it is on.
        LaunchedEffect(compact) {
            val area = usableArea(window.graphicsConfiguration)
            val size = miniSize(compact, area)
            if (size == state.size) return@LaunchedEffect
            val position = miniPosition(size, area, right = (window.x + window.width).toFloat(), top = window.y.toFloat())
            state.size = size
            state.position = position
        }
        val playback = DesktopMusicVideo.playback?.collectAsState()?.value ?: DesktopPlaybackState()
        MaterialTheme(colorScheme = desktopColorScheme(), typography = desktopTypography()) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                // The close button shows on hover, top left, as on Apple Music's.
                var hovered by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .onPointerEvent(PointerEventType.Enter) { hovered = true }
                        .onPointerEvent(PointerEventType.Exit) { hovered = false },
                ) {
                    if (compact) CompactMiniPlayer(playback) else TallMiniPlayer(playback)
                    if (hovered) {
                        Box(
                            Modifier
                                .padding(6.dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable(onClick = DesktopMiniWindow::close),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Close, DesktopStrings["close", "Close"], modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowScope.TallMiniPlayer(playback: DesktopPlaybackState) {
    val song = playback.song
    val videoId by DesktopMiniWindow.videoId.collectAsState()
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
        WindowDraggableArea(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                DesktopArtwork(song?.thumbnailUrl, Modifier.fillMaxSize(), px = ART_PX)
                rememberMiniCanvas(song)?.let { canvas ->
                    CanvasArtworkPlayer(canvas, playback.isPlaying, Modifier.fillMaxSize())
                }
                DesktopMiniPlayerVideo(videoId, Modifier.fillMaxSize())
            }
        }
        // The same picture blurred and faded in from the middle down: the frosted foot the
        // controls sit on, with no hard edge where it starts.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0.42f to Color.Transparent, 0.68f to Color.Black),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            DesktopArtwork(song?.thumbnailUrl, Modifier.fillMaxSize().blur(28.dp), px = ART_PX)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song?.title.orEmpty(),
                        maxLines = 1,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    )
                    Text(song?.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = SECONDARY)
                }
                Spacer(Modifier.width(8.dp))
                LikeButton()
                Spacer(Modifier.width(6.dp))
                MoreButton(compact = false)
            }
            Scrubber(playback, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TimeText(formatTime(playback.positionMs))
                Text(
                    qualityLabel(playback).orEmpty(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SECONDARY,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                TimeText("-" + formatTime((playback.durationMs - playback.positionMs).coerceAtLeast(0L)))
            }
            Transport(playback.isPlaying, Modifier.fillMaxWidth().padding(vertical = 6.dp), big = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.VolumeMute, null, tint = SECONDARY, modifier = Modifier.size(14.dp))
                val volume by DesktopPlayerHost.volume.level.collectAsState()
                DesktopThinSlider(
                    value = volume,
                    onValueChange = DesktopPlayerHost.volume::set,
                    idleHeight = 4.dp,
                    activeHeight = 6.dp,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                )
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = SECONDARY, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun WindowScope.CompactMiniPlayer(playback: DesktopPlaybackState) {
    val song = playback.song
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFF2A2A2D))) {
        // Glass from the cover itself, since the window has no system material of its own.
        DesktopArtwork(song?.thumbnailUrl, Modifier.fillMaxSize().blur(40.dp), px = ROW_ART_PX)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            WindowDraggableArea {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiniCover(song, Modifier.size(42.dp).clickable { DesktopMiniWindow.compact.value = false })
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(song?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(song?.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = SECONDARY)
                    }
                    MoreButton(compact = true, playing = playback.isPlaying)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeText(formatTime(playback.positionMs))
                Scrubber(playback, Modifier.weight(1f).padding(horizontal = 8.dp))
                TimeText("-" + formatTime((playback.durationMs - playback.positionMs).coerceAtLeast(0L)))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallButton(Icons.AutoMirrored.Rounded.QueueMusic, DesktopStrings["queue", "Queue"]) { DesktopMiniWindow.onOpenQueue() }
                Transport(playback.isPlaying, Modifier.weight(1f), big = false)
                SmallButton(Icons.Rounded.Headphones, DesktopStrings["audio_output", "Audio output"]) { DesktopMiniWindow.onOpenAudioOutput() }
            }
        }
    }
}

@Composable
private fun MiniCover(song: Song?, modifier: Modifier) {
    val videoId by DesktopMiniWindow.videoId.collectAsState()
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF323236))) {
        DesktopArtwork(song?.thumbnailUrl, Modifier.matchParentSize(), px = ROW_ART_PX)
        DesktopMiniPlayerVideo(videoId, Modifier.matchParentSize())
    }
}

/**
 * The track's Spotify/Apple canvas, looked up here rather than read from the app: the main window
 * is hidden behind the mini player and stops recomposing, so its own lookup would go stale.
 * Same client and cache as the full player, so a clip it already found paints at once.
 */
@Composable
private fun rememberMiniCanvas(song: Song?): CanvasArtwork? {
    val enabled by DesktopPlayerSettings.animatedCanvas.collectAsState()
    return produceState<CanvasArtwork?>(null, song?.videoId, enabled) {
        value = null
        if (song == null || !enabled) return@produceState
        value = DesktopCanvasClient.cached(song)?.toShared()
        withContext(Dispatchers.IO) { DesktopCanvasClient.lookup(song) }?.let { value = it.toShared() }
    }.value
}

/** The playhead, held where the pointer leaves it while dragged and sought only on release. */
@Composable
private fun Scrubber(playback: DesktopPlaybackState, modifier: Modifier) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs
    val progress = if (duration > 0L) playback.positionMs.toFloat() / duration else 0f
    DesktopThinSlider(
        value = dragged ?: progress,
        onValueChange = { dragged = it },
        onValueChangeFinished = {
            dragged?.let { if (duration > 0L) DesktopMiniWindow.onSeek((it * duration).toLong()) }
            dragged = null
        },
        idleHeight = 4.dp,
        activeHeight = 6.dp,
        modifier = modifier,
    )
}

@Composable
private fun Transport(isPlaying: Boolean, modifier: Modifier, big: Boolean) {
    val skip = if (big) 26.dp else 22.dp
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        PlainButton(Icons.Rounded.FastRewind, DesktopStrings["widget_previous", "Previous"], skip) { DesktopMiniWindow.onPrevious() }
        PlainButton(
            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (isPlaying) "Pause" else "Play",
            if (big) 36.dp else 30.dp,
        ) { DesktopMiniWindow.onTogglePlay() }
        PlainButton(Icons.Rounded.FastForward, DesktopStrings["widget_next", "Next"], skip) { DesktopMiniWindow.onNext() }
    }
}

@Composable
private fun LikeButton() {
    val liked by DesktopMiniWindow.liked.collectAsState()
    CircleButton(if (liked) Icons.Rounded.Star else Icons.Rounded.StarOutline, "Favorite") { DesktopMiniWindow.onToggleLike() }
}

@Composable
private fun MoreButton(compact: Boolean, playing: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    Box {
        if (compact) {
            SmallButton(if (playing) Icons.Rounded.GraphicEq else Icons.Rounded.MoreHoriz, "More") { menu = true }
        } else {
            CircleButton(Icons.Rounded.MoreHoriz, "More") { menu = true }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (compact) DesktopStrings["d_mini_artwork", "Show Artwork"] else DesktopStrings["d_mini_compact", "Compact Player"]) },
                onClick = {
                    menu = false
                    DesktopMiniWindow.compact.value = !compact
                },
            )
            DropdownMenuItem(
                text = { Text(DesktopStrings["widget_open_laya", "Open Laya"]) },
                onClick = {
                    menu = false
                    DesktopMiniWindow.close()
                },
            )
        }
    }
}

@Composable
private fun CircleButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(24.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun SmallButton(icon: ImageVector, description: String, onClick: () -> Unit) =
    PlainButton(icon, description, 18.dp, tint = SECONDARY, onClick = onClick)

@Composable
private fun PlainButton(
    icon: ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(size + 10.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(size))
    }
}

@Composable
private fun TimeText(text: String) = Text(text, fontSize = 10.sp, color = SECONDARY, maxLines = 1)

/** The badge Apple Music prints between the times: the best thing the stream can claim. */
private fun qualityLabel(playback: DesktopPlaybackState): String? {
    val format = playback.streamFormat ?: return null
    return when {
        format.isDolbyAtmos -> "Dolby Atmos"
        format.isHiRes -> "Hi-Res Lossless"
        format.isLossless -> "Lossless"
        else -> null
    }
}

private fun formatTime(ms: Long): String {
    val seconds = ms / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** A screen's bounds less its menu bar, Dock or taskbar — logical points, the same unit as dp. */
private fun usableArea(config: GraphicsConfiguration): Rectangle {
    val bounds = config.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    return Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        bounds.width - insets.left - insets.right,
        bounds.height - insets.top - insets.bottom,
    )
}

/**
 * The card's size on a screen with [area] free: full size wherever it fits with its margin, scaled
 * down whole on a short or heavily zoomed laptop screen (a 1366×768 panel at 150% has ~480pt of
 * height), but never below [MIN_SCALE], under which the controls would cover the artwork.
 */
internal fun miniSize(compact: Boolean, area: Rectangle): DpSize {
    val base = if (compact) COMPACT else TALL
    val scale = minOf(
        1f,
        (area.width - 2 * EDGE_DP) / base.width.value,
        (area.height - 2 * EDGE_DP) / base.height.value,
    ).coerceAtLeast(MIN_SCALE)
    return DpSize(base.width * scale, base.height * scale)
}

/** Where a card of [size] with its right edge at [right] and top at [top] goes, wholly inside [area]. */
internal fun miniPosition(size: DpSize, area: Rectangle, right: Float, top: Float): WindowPosition {
    val maxX = maxOf(area.x.toFloat(), area.maxX.toFloat() - size.width.value)
    val maxY = maxOf(area.y.toFloat(), area.maxY.toFloat() - size.height.value)
    return WindowPosition(
        (right - size.width.value).coerceIn(area.x.toFloat(), maxX).dp,
        top.coerceIn(area.y.toFloat(), maxY).dp,
    )
}

private val TALL = DpSize(280.dp, 450.dp)
private val COMPACT = DpSize(340.dp, 136.dp)
private val SECONDARY = Color.White.copy(alpha = 0.62f)
private const val ART_PX = 600
private const val EDGE_DP = 24f
private const val MIN_SCALE = 0.7f
