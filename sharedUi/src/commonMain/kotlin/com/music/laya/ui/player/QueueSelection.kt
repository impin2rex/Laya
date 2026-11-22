package com.music.laya.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.laya.data.model.QueueEntryIdentity
import com.music.laya.data.model.Song
import com.music.laya.sharedui.resources.*
import com.music.laya.ui.components.optimizedHazeEffect
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * What a platform's player can do to its queue beyond the plain row edits,
 * for the queue screen's swipes, Undo and multi-select. A host that hands in
 * none gets the old queue: no Select, no Undo, swipes that only remove.
 */
class QueueActions(
    /** Takes the rows out; the token undoes it with [undo]. Null when nothing was removed. */
    val remove: suspend (List<QueueEntryIdentity>) -> Long?,
    /** Puts back what the removal [remove] answered with this token took out. */
    val undo: suspend (token: Long) -> Boolean,
    /** Moves the row into Next in queue (its head or tail, per the swipe setting). */
    val moveToUserQueue: (suspend (QueueEntryIdentity) -> Boolean)? = null,
    /** Null hides the Add to playlist actions rather than leaving them inert. */
    val addToPlaylist: ((List<Song>) -> Unit)? = null,
)

internal val QueueTracks.rows: List<QueueTrack>
    get() = history + user + context + autoplay

private val QueueTrack.selectionKey: String
    get() = uniqueEntryId?.let { "entry:$it" } ?: key

/** Owned above the lazy rows, so scrolling never disposes the selection. */
internal class QueueSelectionState {
    var active by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
    var generation by mutableIntStateOf(0)
        private set
    private var keys by mutableStateOf(emptySet<String>())

    /** Where a shift-click range starts: the last row toggled. */
    var anchorKey: String? = null
        private set

    fun start() {
        generation++
        active = true
        busy = false
        keys = emptySet()
        anchorKey = null
    }

    fun isSelected(track: QueueTrack): Boolean = track.selectionKey in keys

    fun selected(rows: List<QueueTrack>): List<QueueTrack> = rows.filter(::isSelected)

    fun toggle(track: QueueTrack) {
        if (busy) return
        val key = track.selectionKey
        keys = if (key in keys) keys - key else keys + key
        anchorKey = track.key
    }

    /** Selects every row from the last one toggled to [track], as a shift-click does. */
    fun extendTo(track: QueueTrack, rows: List<QueueTrack>) {
        if (busy) return
        val from = rows.indexOfFirst { it.key == anchorKey }
        val to = rows.indexOfFirst { it.key == track.key }
        if (from < 0 || to < 0) return toggle(track)
        keys = keys + rows.subList(minOf(from, to), maxOf(from, to) + 1).map { it.selectionKey }
    }

    fun selectAll(rows: List<QueueTrack>) {
        if (!busy) keys = rows.mapTo(HashSet()) { it.selectionKey }
    }

    fun retain(rows: List<QueueTrack>) {
        keys = keys.intersect(rows.mapTo(HashSet()) { it.selectionKey })
    }

    fun clear() {
        generation++
        active = false
        busy = false
        keys = emptySet()
        anchorKey = null
    }
}

/**
 * The drawers' frosted material, for the bars laid over the queue's own list:
 * the list (a haze source) blurred under a thin charcoal slab, flat with
 * reduced blur on.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun Modifier.queueBarSurface(shape: Shape, hazeState: HazeState): Modifier {
    val reduceDynamicBlur by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    return clip(shape)
        .then(
            if (reduceDynamicBlur) {
                Modifier.background(Color(0xFF1E1E1E))
            } else {
                Modifier
                    .optimizedHazeEffect(state = hazeState, style = HazeMaterials.regular(Color(0xFF141414)))
                    .background(Color(0xFF121212).copy(alpha = 0.45f))
            },
        )
        .border(0.5.dp, Color.White.copy(alpha = 0.10f), shape)
}

/** A removal the listener can take back; a new [id] restarts the notice's lifetime. */
internal class QueueUndoNotice(val id: Long, val message: String, val token: Long)

@Composable
internal fun QueueUndoBar(
    notice: QueueUndoNotice?,
    reduceAnimation: Boolean,
    hazeState: HazeState,
    onUndo: (QueueUndoNotice) -> Unit,
) {
    AnimatedContent(
        targetState = notice,
        transitionSpec = {
            if (reduceAnimation) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (fadeIn() + slideInVertically { it / 2 }) togetherWith (fadeOut() + slideOutVertically { it / 2 })
            }
        },
        contentKey = { it?.id },
        label = "queueUndoNotice",
        modifier = Modifier.fillMaxWidth(),
    ) { current ->
        if (current != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .queueBarSurface(RoundedCornerShape(16.dp), hazeState)
                    .clickable(onClick = {})
                    .padding(start = 20.dp, end = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = current.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 16.dp),
                )
                TextButton(onClick = { onUndo(current) }) {
                    Text(stringResource(Res.string.queue_undo_action), color = Color.White)
                }
            }
        }
    }
}

@Composable
internal fun QueueSelectionBar(
    selection: QueueSelectionState,
    rows: List<QueueTrack>,
    reduceAnimation: Boolean,
    onAddToPlaylist: ((List<Song>) -> Unit)?,
    onRemoveSelected: suspend (List<QueueTrack>) -> Unit,
    hazeState: HazeState,
) {
    val scope = rememberCoroutineScope()
    AnimatedVisibility(
        visible = selection.active,
        enter = if (reduceAnimation) EnterTransition.None else
            slideInVertically { it } + expandVertically() + fadeIn(),
        exit = if (reduceAnimation) ExitTransition.None else
            slideOutVertically { it } + shrinkVertically() + fadeOut(),
        label = "queue-selection-bar",
    ) {
        val selected = selection.selected(rows)
        val enabled = selected.isNotEmpty() && !selection.busy
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .queueBarSurface(RoundedCornerShape(16.dp), hazeState)
                .clickable(onClick = {})
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Capped so the actions stay grouped on tablets and wide windows.
            Row(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onAddToPlaylist != null) {
                    BulkActionButton(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(Res.string.queue_add_to_playlist), enabled) {
                        onAddToPlaylist(selected.map { it.song })
                        selection.clear()
                    }
                }
                BulkActionButton(Icons.Rounded.Delete, stringResource(Res.string.queue_selection_remove), enabled) {
                    // One action at a time; a selection closed or restarted meanwhile is left alone.
                    val generation = selection.generation
                    selection.busy = true
                    scope.launch {
                        try {
                            onRemoveSelected(selected)
                            if (selection.generation == generation) selection.clear()
                        } finally {
                            if (selection.generation == generation) selection.busy = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.BulkActionButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val labelStyle = MaterialTheme.typography.labelMedium
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        // One line on every width and font scale: shrinks a little before it ever ellipsizes.
        BasicText(
            text = label,
            style = labelStyle.copy(color = Color.White, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = labelStyle.fontSize),
        )
    }
}
