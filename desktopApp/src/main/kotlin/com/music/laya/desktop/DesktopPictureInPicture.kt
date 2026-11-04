package com.music.laya.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.flow.MutableStateFlow
import java.awt.GraphicsEnvironment

/**
 * The desktop's picture-in-picture for a playing music video: a small floating window, above
 * every other, showing only the picture, which goes on as the main window is used for anything
 * else or put away. Hovering it offers play/pause, the way back to the player, and closing it.
 */
internal object DesktopPictureInPicture {
    /** The video the floating window shows; null while there is none. */
    val videoId = MutableStateFlow<String?>(null)

    /** Set by the app: back to the full player, and play/pause. */
    var onReturn: () -> Unit = {}
    var onTogglePlay: () -> Unit = {}

    fun open(videoId: String) {
        this.videoId.value = videoId
    }

    fun close() {
        videoId.value = null
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopPictureInPictureWindow() {
    val shown by DesktopPictureInPicture.videoId.collectAsState()
    val videoId = shown ?: return
    val aspect by DesktopMusicVideo.aspect.collectAsState()
    val playing = DesktopMusicVideo.playback?.collectAsState()?.value?.isPlaying == true
    // Docked in the screen's bottom right corner, clear of the dock or taskbar, as a browser's is.
    val state = remember {
        val width = PICTURE_WIDTH_DP
        val height = width / aspect.coerceIn(1f, 2.4f)
        val usable = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        androidx.compose.ui.window.WindowState(
            size = DpSize(width.dp, height.dp),
            position = WindowPosition(
                (usable.x + usable.width - width - EDGE_DP).dp,
                (usable.y + usable.height - height - EDGE_DP).dp,
            ),
        )
    }
    Window(
        onCloseRequest = DesktopPictureInPicture::close,
        state = state,
        title = "Laya",
        undecorated = true,
        alwaysOnTop = true,
        resizable = true,
    ) {
        var hovered by remember { mutableStateOf(false) }
        WindowDraggableArea {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .onPointerEvent(PointerEventType.Enter) { hovered = true }
                    .onPointerEvent(PointerEventType.Exit) { hovered = false },
            ) {
                DesktopMusicVideoSurface(
                    videoId = videoId,
                    fit = true,
                    onRenderedChanged = {},
                    modifier = Modifier.fillMaxSize(),
                )
                if (hovered) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
                    Row(
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PictureButton(Icons.Rounded.OpenInFull, DesktopPictureInPicture.onReturn)
                        PictureButton(Icons.Rounded.Close, DesktopPictureInPicture::close)
                    }
                    Box(Modifier.align(Alignment.Center)) {
                        PictureButton(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            DesktopPictureInPicture.onTogglePlay,
                            size = 44,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PictureButton(icon: ImageVector, onClick: () -> Unit, size: Int = 30) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.6f).dp))
    }
}

private const val PICTURE_WIDTH_DP = 400f
private const val EDGE_DP = 24f
