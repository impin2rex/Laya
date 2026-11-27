package com.music.laya.desktop

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import java.awt.MouseInfo

// The window's own frame, drawn by the application rather than by the system.

/** The strip where the system's title bar would have been. */
@Composable
internal fun DesktopTitleBar() {
    // Collected rather than read: [DesktopTitleBarSetting.active] is a plain call, so a composable
    // that only asks it never learns the answer changed.
    val enabled by DesktopTitleBarSetting.enabled.collectAsState()
    if (!DesktopPlatform.drawsOwnWindowFrame || !enabled) return
    DesktopTitleBarDragArea(
        Modifier
            .fillMaxWidth()
            .height(if (DesktopPlatform.windowsCaption != 0) WINDOWS_CAPTION_HEIGHT else CAPTION_HEIGHT)
            .desktopWindowGlass(),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (DesktopPlatform.windowsCaption != 0) {
                DesktopWindowsCaptionButtons(Modifier.align(Alignment.TopEnd))
            } else {
                DesktopWindowButtons(Modifier.align(Alignment.CenterStart))
            }
        }
    }
}

/** Whether there is a title bar at all. */
internal object DesktopTitleBarSetting {

    internal const val KEY = "window_title_bar"

    private val _enabled = MutableStateFlow(DesktopPersistence().boolean(KEY, false))

    /** What Settings shows and writes. */
    val enabled: StateFlow<Boolean> = _enabled

    fun set(value: Boolean) {
        DesktopPersistence().saveBoolean(KEY, value)
        _enabled.value = value
    }
}

/** What the caption buttons act on. */
internal class DesktopWindowActions(
    val minimize: () -> Unit,
    val toggleMaximize: () -> Unit,
    val close: () -> Unit,
)

internal val LocalDesktopWindowActions = staticCompositionLocalOf<DesktopWindowActions?> { null }

/** The window, for the drag area. */
internal val LocalDesktopWindowScope = staticCompositionLocalOf<WindowScope?> { null }

/** A region that moves the window, and maximizes it on a double click. */
@Composable
internal fun DesktopTitleBarDragArea(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val scope = LocalDesktopWindowScope.current
    val actions = LocalDesktopWindowActions.current
    if (!DesktopPlatform.drawsOwnWindowFrame || scope == null) {
        Box(modifier) { content() }
        return
    }
    // Behind the content rather than around it. Wrapped around it, the drag area saw every press
    // in the toolbar too — dragging the volume slider moved the window with it. As a sibling
    // underneath, anything with its own pointer input (buttons, sliders) is hit first and the
    // press never reaches here; only empty toolbar falls through to move the window.
    Box(modifier) {
        Box(Modifier.matchParentSize().captionPress(scope, actions))
        content()
    }
}

/**
 * macOS' traffic lights, at the window's top left. In the title bar when there is one, otherwise on the toolbar.
 * Windows has its own, on the right: [DesktopWindowsCaptionButtons].
 */
@Composable
internal fun DesktopWindowButtons(modifier: Modifier = Modifier) {
    if (!DesktopPlatform.drawsOwnWindowFrame || DesktopPlatform.windowsCaption != 0) return
    val actions = LocalDesktopWindowActions.current ?: return
    val maximized by DesktopWindowMode.maximized.collectAsState()
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val isFocused = windowInfo.isWindowFocused
    val inactiveFill = if (!isFocused && DesktopPlatform.isMac) Color.White.copy(alpha = 0.22f) else null

    Row(
        modifier = modifier.height(CAPTION_HEIGHT).padding(start = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MacCaptionButton("Close", MAC_CLOSE, onClick = actions.close, inactiveFill = inactiveFill) { glyph ->
            val reach = GLYPH_HALF.toPx()
            val line = HAIRLINE.toPx()
            drawLine(
                color = glyph,
                start = Offset(center.x - reach, center.y - reach),
                end = Offset(center.x + reach, center.y + reach),
                strokeWidth = line,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = glyph,
                start = Offset(center.x + reach, center.y - reach),
                end = Offset(center.x - reach, center.y + reach),
                strokeWidth = line,
                cap = StrokeCap.Round,
            )
        }
        MacCaptionButton("Minimize", MAC_MINIMIZE, onClick = actions.minimize, inactiveFill = inactiveFill) { glyph ->
            drawLine(
                color = glyph,
                start = Offset(center.x - GLYPH_HALF.toPx(), center.y),
                end = Offset(center.x + GLYPH_HALF.toPx(), center.y),
                strokeWidth = HAIRLINE.toPx(),
                cap = StrokeCap.Round,
            )
        }
        MacCaptionButton(
            if (maximized) "Restore" else "Maximize",
            MAC_MAXIMIZE,
            onClick = actions.toggleMaximize,
            inactiveFill = inactiveFill,
        ) { glyph ->
            val reach = GLYPH_HALF.toPx()
            val line = HAIRLINE.toPx()
            // The opposing corner marks used by macOS' green zoom control. They remain legible at
            // 100% Windows scaling, unlike a tiny outlined square.
            drawLine(glyph, Offset(center.x - reach, center.y + reach), Offset(center.x + reach, center.y - reach), line, StrokeCap.Round)
            drawLine(glyph, Offset(center.x + reach, center.y - reach), Offset(center.x + 1.dp.toPx(), center.y - reach), line, StrokeCap.Round)
            drawLine(glyph, Offset(center.x + reach, center.y - reach), Offset(center.x + reach, center.y - 1.dp.toPx()), line, StrokeCap.Round)
            drawLine(glyph, Offset(center.x - reach, center.y + reach), Offset(center.x - 1.dp.toPx(), center.y + reach), line, StrokeCap.Round)
            drawLine(glyph, Offset(center.x - reach, center.y + reach), Offset(center.x - reach, center.y + 1.dp.toPx()), line, StrokeCap.Round)
        }
    }
}

@Composable
private fun MacCaptionButton(
    label: String,
    fill: Color,
    onClick: () -> Unit,
    inactiveFill: Color? = null,
    glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val buttonColor = if (inactiveFill != null && !hovered) inactiveFill else fill
    Box(
        Modifier
            .size(CAPTION_TOUCH_SIZE)
            .hoverable(interaction)
            // The arrow, not the hand: this is window furniture, and the pointer says so before the
            // click does.
            .pointerHoverIcon(PointerIcon.Default)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = label }
            .drawBehind {
                drawCircle(buttonColor, radius = MAC_DOT_RADIUS.toPx())
                if (hovered) glyph(MAC_GLYPH)
            },
        contentAlignment = Alignment.Center,
    ) {}
}

/**
 * Windows' caption buttons, at the window's top right (minimize, maximize or restore, close), drawn as the
 * running Windows draws its own ([DesktopPlatform.windowsGeneration]): Windows 11's and 10's flat 46 × 32 cells,
 * each with its own hover and red, or Windows 7's glossy Aero group hanging from the top edge (also on 8 / 8.1).
 * Glyphs are drawn rather than set in Segoe Fluent Icons / MDL2 Assets, which not every version has.
 */
@Composable
internal fun DesktopWindowsCaptionButtons(modifier: Modifier = Modifier) {
    if (DesktopPlatform.windowsCaption == 0) return
    val actions = LocalDesktopWindowActions.current ?: return
    val maximized by DesktopWindowMode.maximized.collectAsState()
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    WindowsCaptionButtons(DesktopPlatform.windowsCaption, actions, maximized, focused, modifier)
}

/** One generation's buttons, whatever Windows this is: [DesktopWindowsCaptionButtons], and tests. */
@Composable
internal fun WindowsCaptionButtons(
    generation: Int,
    actions: DesktopWindowActions,
    maximized: Boolean,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    when (generation) {
        7 -> AeroCaptionButtons(actions, maximized, focused, modifier)
        10 -> FlatCaptionButtons(actions, maximized, focused, WINDOWS_10, modifier)
        else -> FlatCaptionButtons(actions, maximized, focused, WINDOWS_11, modifier)
    }
}

/** One flat generation's colours, as its dark caption draws them. */
private class FlatPalette(val hover: Color, val pressed: Color, val closeHover: Color, val closePressed: Color)

private val WINDOWS_11 = FlatPalette(
    hover = Color.White.copy(alpha = 0.06f),
    pressed = Color.White.copy(alpha = 0.04f),
    closeHover = Color(0xFFC42B1C),
    closePressed = Color(0xE6C42B1C),
)
private val WINDOWS_10 = FlatPalette(
    hover = Color.White.copy(alpha = 0.10f),
    pressed = Color.White.copy(alpha = 0.20f),
    closeHover = Color(0xFFE81123),
    closePressed = Color(0xFFF1707A),
)

@Composable
private fun FlatCaptionButtons(
    actions: DesktopWindowActions,
    maximized: Boolean,
    focused: Boolean,
    palette: FlatPalette,
    modifier: Modifier,
) {
    val glyph = if (focused) Color.White else Color.White.copy(alpha = 0.45f)
    Row(modifier.height(WINDOWS_CAPTION_HEIGHT)) {
        FlatCaptionButton("Minimize", glyph, palette.hover, palette.pressed, onClick = actions.minimize) { color ->
            val y = snap(center.y)
            drawLine(color, Offset(snap(center.x - WIN_GLYPH_HALF.toPx()), y), Offset(snap(center.x + WIN_GLYPH_HALF.toPx()), y), WIN_LINE.toPx())
        }
        FlatCaptionButton(if (maximized) "Restore" else "Maximize", glyph, palette.hover, palette.pressed, onClick = actions.toggleMaximize) { color ->
            val half = WIN_GLYPH_HALF.toPx()
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(WIN_LINE.toPx())
            if (maximized) {
                // Two windows, the front one lower left, the back one showing only its top and right edges.
                val step = 2.dp.toPx()
                val front = androidx.compose.ui.geometry.Rect(snap(center.x - half), snap(center.y - half + step), snap(center.x + half - step), snap(center.y + half))
                drawRect(color, front.topLeft, front.size, style = stroke)
                val backLeft = front.left + step
                val backTop = front.top - step
                val backRight = snap(center.x + half)
                val backBottom = front.bottom - step
                drawLine(color, Offset(backLeft, backTop), Offset(backRight, backTop), WIN_LINE.toPx())
                drawLine(color, Offset(backRight, backTop), Offset(backRight, backBottom), WIN_LINE.toPx())
            } else {
                val left = snap(center.x - half)
                val top = snap(center.y - half)
                drawRect(color, Offset(left, top), androidx.compose.ui.geometry.Size(snap(center.x + half) - left, snap(center.y + half) - top), style = stroke)
            }
        }
        FlatCaptionButton("Close", glyph, palette.closeHover, palette.closePressed, onClick = actions.close, activeGlyph = Color.White) { color ->
            val half = WIN_GLYPH_HALF.toPx()
            drawLine(color, Offset(center.x - half, center.y - half), Offset(center.x + half, center.y + half), WIN_LINE.toPx())
            drawLine(color, Offset(center.x + half, center.y - half), Offset(center.x - half, center.y + half), WIN_LINE.toPx())
        }
    }
}

@Composable
private fun FlatCaptionButton(
    label: String,
    glyphColor: Color,
    hoverFill: Color,
    pressedFill: Color,
    onClick: () -> Unit,
    activeGlyph: Color? = null,
    glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill = when {
        pressed -> pressedFill
        hovered -> hoverFill
        else -> Color.Transparent
    }
    val color = if ((hovered || pressed) && activeGlyph != null) activeGlyph else glyphColor
    Box(
        Modifier
            .size(width = WIN_BUTTON_WIDTH, height = WINDOWS_CAPTION_HEIGHT)
            .background(fill)
            .captionButton(interaction, label, onClick)
            .drawBehind { glyph(color) },
    )
}

/**
 * Windows 7's Aero caption buttons: one glossy group hanging from the window's top edge a little in from the
 * corner, its outer bottom corners rounded. Minimize and maximize are clear glass that glows blue under the
 * pointer; close is red glass while the window is in front and turns to clear glass behind, as Aero does.
 */
@Composable
private fun AeroCaptionButtons(actions: DesktopWindowActions, maximized: Boolean, focused: Boolean, modifier: Modifier) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = AERO_RADIUS, bottomEnd = AERO_RADIUS)
    Row(
        modifier
            .padding(end = AERO_INSET)
            .height(AERO_HEIGHT)
            .clip(shape)
            .border(1.dp, AERO_EDGE, shape),
    ) {
        AeroCaptionButton("Minimize", AERO_SMALL_WIDTH, red = false, onClick = actions.minimize) { color ->
            val w = 4.dp.toPx()
            drawRect(color, Offset(center.x - w, center.y + 1.dp.toPx()), androidx.compose.ui.geometry.Size(w * 2, 2.5.dp.toPx()))
        }
        AeroDivider()
        AeroCaptionButton(if (maximized) "Restore" else "Maximize", AERO_SMALL_WIDTH, red = false, onClick = actions.toggleMaximize) { color ->
            val w = 4.5.dp.toPx()
            val h = 3.5.dp.toPx()
            val line = 1.2.dp.toPx()
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(line)
            if (maximized) {
                val step = 2.dp.toPx()
                drawRect(color, Offset(center.x - w + step, center.y - h - step), androidx.compose.ui.geometry.Size(w * 2 - step, h * 2 - step), style = stroke)
                drawRect(color, Offset(center.x - w, center.y - h), androidx.compose.ui.geometry.Size(w * 2 - step, h * 2), style = stroke)
                drawRect(color, Offset(center.x - w, center.y - h), androidx.compose.ui.geometry.Size(w * 2 - step, 2.dp.toPx()))
            } else {
                drawRect(color, Offset(center.x - w, center.y - h), androidx.compose.ui.geometry.Size(w * 2, h * 2), style = stroke)
                // Aero's maximize glyph carries a heavy top edge, its title bar.
                drawRect(color, Offset(center.x - w, center.y - h), androidx.compose.ui.geometry.Size(w * 2, 2.dp.toPx()))
            }
        }
        AeroDivider()
        AeroCaptionButton("Close", AERO_CLOSE_WIDTH, red = focused, onClick = actions.close) { color ->
            val w = 4.dp.toPx()
            val h = 3.5.dp.toPx()
            val line = 2.2.dp.toPx()
            drawLine(color, Offset(center.x - w, center.y - h), Offset(center.x + w, center.y + h), line, StrokeCap.Round)
            drawLine(color, Offset(center.x + w, center.y - h), Offset(center.x - w, center.y + h), line, StrokeCap.Round)
        }
    }
}

@Composable
private fun AeroDivider() {
    Box(Modifier.size(width = 1.dp, height = AERO_HEIGHT).background(AERO_EDGE))
}

@Composable
private fun AeroCaptionButton(
    label: String,
    width: Dp,
    red: Boolean,
    onClick: () -> Unit,
    glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    // Top half and bottom half: the split down the middle is what reads as glass.
    val (top, bottom) = when {
        red && pressed -> Color(0xFFB0473A) to Color(0xFF8E2416)
        red && hovered -> Color(0xFFF6A094) to Color(0xFFE04B37)
        red -> Color(0xFFE4A39A) to Color(0xFFC4473A)
        pressed -> Color(0xFF5A8FC2) to Color(0xFF1D4F84)
        hovered -> Color(0xFFA8D4F6) to Color(0xFF3C8DD5)
        else -> Color.White.copy(alpha = 0.32f) to Color.White.copy(alpha = 0.10f)
    }
    Box(
        Modifier
            .size(width = width, height = AERO_HEIGHT)
            .captionButton(interaction, label, onClick)
            .drawBehind {
                val half = size.height / 2
                drawRect(top, size = androidx.compose.ui.geometry.Size(size.width, half))
                drawRect(bottom, topLeft = Offset(0f, half), size = androidx.compose.ui.geometry.Size(size.width, size.height - half))
                // The glyph's dark halo, then the glyph: white on any glass.
                translate(top = 1.dp.toPx()) { glyph(Color.Black.copy(alpha = 0.45f)) }
                glyph(Color.White)
            },
    )
}

/** What every caption button does with the pointer: the arrow (window furniture), a press, a name. */
private fun Modifier.captionButton(interaction: MutableInteractionSource, label: String, onClick: () -> Unit): Modifier =
    hoverable(interaction)
        .pointerHoverIcon(PointerIcon.Default)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .semantics { contentDescription = label }

/** Half a pixel in from a whole one, so a one-pixel line covers one row of pixels rather than blurring over two. */
private fun snap(value: Float): Float = kotlin.math.floor(value) + 0.5f

private val WINDOWS_CAPTION_HEIGHT = 32.dp
private val WIN_BUTTON_WIDTH = 46.dp
/** Windows' caption glyphs are 10 px squares drawn with one-pixel lines. */
private val WIN_GLYPH_HALF = 5.dp
private val WIN_LINE = 1.dp

/** Aero's metrics at 96 dpi: 26 + 26 + 45 wide, 20 tall, a few pixels in from the corner. */
private val AERO_HEIGHT = 20.dp
private val AERO_SMALL_WIDTH = 26.dp
private val AERO_CLOSE_WIDTH = 45.dp
private val AERO_INSET = 6.dp
private val AERO_RADIUS = 4.dp
private val AERO_EDGE = Color(0xB3203040)

/**
 * The platform's own caption metrics, which is what makes these read as the window's controls
 * rather than the application's.
 */
private val CAPTION_TOUCH_SIZE = 22.dp
private val CAPTION_HEIGHT = 24.dp
private val MAC_DOT_RADIUS = 6.dp

/** Half the width of a glyph, and the weight every one of them is drawn at. */
private val GLYPH_HALF = 2.5.dp
private val HAIRLINE = 0.75.dp

private val MAC_CLOSE = Color(0xFFFF5F57)
private val MAC_MINIMIZE = Color(0xFFFFBD2E)
private val MAC_MAXIMIZE = Color(0xFF28C840)
private val MAC_GLYPH = Color(0xB3000000)

/**
 * A press on empty caption: moves the window, and a second one in quick succession maximizes it,
 * the way the system caption always has.
 *
 * On Windows the move is handed to the system ([DesktopWindowsFrame.startDrag]), which runs its own
 * move loop — DWM slides the window, Aero Snap works, and no edge is left exposed to paint white.
 * On macOS it is handed to AppKit via [DesktopMacFrame.startDrag] for smooth ProMotion dragging.
 * The double click is counted here rather than left to Windows: the area is client area as far as
 * Windows knows, so it never sends the caption's own double click. Without the native frame the
 * window is moved from the pointer, as WindowDraggableArea did.
 */
private fun Modifier.captionPress(scope: WindowScope, actions: DesktopWindowActions?): Modifier =
    pointerInput(scope, actions) {
        var lastPressAt = 0L
        var lastPressPosition = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown()
            val double = down.uptimeMillis - lastPressAt <= viewConfiguration.doubleTapTimeoutMillis &&
                (down.position - lastPressPosition).getDistance() <= viewConfiguration.touchSlop
            if (double) {
                lastPressAt = 0L
                down.consume()
                actions?.toggleMaximize()
                return@awaitEachGesture
            }
            lastPressAt = down.uptimeMillis
            lastPressPosition = down.position
            if (DesktopWindowsFrame.startDrag()) return@awaitEachGesture
            if (DesktopPlatform.isMac && DesktopMacFrame.startDrag()) return@awaitEachGesture
            val window = scope.window
            val windowAtStart = window.location
            val pointerAtStart = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
            drag(down.id) {
                val pointer = MouseInfo.getPointerInfo()?.location ?: return@drag
                window.setLocation(
                    windowAtStart.x + pointer.x - pointerAtStart.x,
                    windowAtStart.y + pointer.y - pointerAtStart.y,
                )
            }
        }
    }
