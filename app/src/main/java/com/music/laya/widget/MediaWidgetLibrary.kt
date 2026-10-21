package com.music.laya.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.music.laya.MainActivity
import com.music.laya.R
import com.music.laya.playback.PlayerDeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * The 4×2 "Now playing + Liked" widget: the track and transport across the top,
 * the first few Liked Music covers along the bottom. A cover opens the app and
 * plays that song, through the same `watch?v=` link handling a shared link uses
 * ([MusicLink][com.music.laya.playback.MusicLink]).
 *
 * Built like [MediaWidgetPill]: the body is the cover blurred to colour
 * ([MediaWidgetArt.pill]) at the widget's exact size, and the controls are pushed
 * before any picture so a tap is answered in a frame.
 */
class MediaWidgetLibrary : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        renderAsync(context, ids)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: Bundle?,
    ) {
        renderAsync(context, intArrayOf(id))
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) {
            lastBody.remove(id)
            lastArt.remove(id)
        }
    }

    private fun renderAsync(context: Context, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                render(app, ids)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    companion object {

        /** Redraws every placed instance. From [MediaWidget.refresh] and [LikedWidgetSongs.save]. */
        internal fun refresh(context: Context) {
            val app = context.applicationContext
            scope.launch {
                val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return@launch
                val ids = runCatching {
                    manager.getAppWidgetIds(ComponentName(app, MediaWidgetLibrary::class.java))
                }.getOrNull()
                if (ids == null || ids.isEmpty()) return@launch
                render(app, ids)
            }
        }

        private suspend fun render(context: Context, ids: IntArray) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val snapshot = MediaWidgetSnapshot.load(context)
            val liked = LikedWidgetSongs.load(context).take(TILES.size)
            val key = snapshot.artworkUrl
            val bodyRadius = context.resources.getDimension(R.dimen.widget_corner_radius)
            for (id in ids) {
                val g = geometry(context, manager, id, hasLiked = liked.isNotEmpty())
                val cachedArt = key?.let { MediaWidgetArt.peekTile(it, g.artPx, g.artRadius) }
                val cachedBody = key?.let { MediaWidgetArt.peekPill(it, g.widthPx, g.heightPx, bodyRadius) }
                val cachedTiles = liked.map { MediaWidgetArt.peekTile(it.videoId, g.tilePx, g.tileRadius) }
                // Controls first, with what this instance last showed — a RemoteViews
                // replaces the whole tree. See MediaWidget.render.
                manager.push(
                    id,
                    views(context, g.tall, snapshot, liked, cachedArt ?: lastArt[id], cachedBody ?: lastBody[id], cachedTiles),
                )
                val drawn = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                    val art = cachedArt ?: key?.let { MediaWidgetArt.tile(context, key, g.artPx, g.artRadius, it) }
                    val body = cachedBody
                        ?: MediaWidgetArt.pill(context, key, g.widthPx, g.heightPx, key, bodyRadius)
                    val tiles = liked.mapIndexed { i, song ->
                        cachedTiles[i]
                            ?: MediaWidgetArt.tile(context, song.artworkUrl, g.tilePx, g.tileRadius, song.videoId)
                    }
                    Triple(art, body, tiles)
                } ?: continue
                val (art, body, tiles) = drawn
                art?.let { lastArt[id] = it } ?: lastArt.remove(id)
                lastBody[id] = body
                manager.push(id, views(context, g.tall, snapshot, liked, art, body, tiles))
            }
        }

        /** Which layout an instance takes, and the pixel sizes its pictures are drawn at. */
        private class Geometry(
            val tall: Boolean,
            val widthPx: Int,
            val heightPx: Int,
            val artPx: Int,
            val tilePx: Int,
        ) {
            val artRadius = artPx * ART_RADIUS_FRACTION
            val tileRadius = tilePx * ART_RADIUS_FRACTION
        }

        /**
         * The widget's size for the orientation in force, as [MediaWidget.measure]
         * reads it, and the cover and tile sizes each layout gives that size —
         * the same arithmetic the two layout files do with their padding, gaps
         * and weights, so the bitmaps land at the size they are shown.
         */
        private fun geometry(context: Context, manager: AppWidgetManager, id: Int, hasLiked: Boolean): Geometry {
            val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
            val landscape =
                context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val widthDp = options?.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
            )?.takeIf { it > 0 } ?: FALLBACK_WIDTH_DP
            val heightDp = options?.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
            )?.takeIf { it > 0 } ?: FALLBACK_HEIGHT_DP

            val tall = heightDp >= TALL_MIN_DP
            val (artDp, tileDp) = if (tall) {
                // widget_media_library_tall: 14dp padding, rows 3:2 with a 12dp gap.
                val content = heightDp - 28
                val rows = content - 12
                if (hasLiked) rows * 3 / 5 to minOf((widthDp - 28) / TILES.size, rows * 2 / 5) else content to 0
            } else {
                // widget_media_library: 10dp padding, a 40dp tile row under a 6dp gap.
                val content = heightDp - 20
                val tile = context.resources.getDimension(R.dimen.widget_library_tile) /
                    context.resources.displayMetrics.density
                if (hasLiked) content - 6 - tile.roundToInt() to tile.roundToInt() else content to 0
            }
            val density = context.resources.displayMetrics.density
            fun px(dp: Int) = (dp * density).roundToInt().coerceIn(1, MAX_SIDE_PX)
            return Geometry(
                tall = tall,
                widthPx = px(widthDp),
                heightPx = px(heightDp),
                artPx = px(artDp),
                tilePx = px(tileDp),
            )
        }

        private fun views(
            context: Context,
            tall: Boolean,
            snapshot: MediaWidgetSnapshot,
            liked: List<LikedWidgetSongs.Entry>,
            art: Bitmap?,
            body: Bitmap?,
            tiles: List<Bitmap?>,
        ): RemoteViews {
            val views = RemoteViews(
                context.packageName,
                if (tall) R.layout.widget_media_library_tall else R.layout.widget_media_library,
            )
            art?.let { views.setImageViewBitmap(R.id.widget_art, it) }
            body?.let {
                views.setImageViewBitmap(R.id.widget_library_bg, it)
                views.setInt(R.id.widget_library_bg, "setBackgroundResource", 0)
            }

            views.setTextViewText(
                R.id.widget_title,
                if (snapshot.hasTrack) snapshot.title else context.getString(R.string.widget_nothing_played),
            )
            views.setTextViewText(R.id.widget_artist, snapshot.artist)
            views.setViewVisibility(R.id.widget_artist, if (snapshot.artist.isBlank()) View.GONE else View.VISIBLE)
            views.setImageViewResource(
                R.id.widget_toggle,
                if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )
            views.setContentDescription(
                R.id.widget_toggle,
                context.getString(if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play),
            )

            val open = openPlayer(context)
            views.setOnClickPendingIntent(R.id.widget_now_playing, open)
            views.setOnClickPendingIntent(R.id.widget_logo, open)
            if (snapshot.hasTrack) {
                views.setImageAlpha(R.id.widget_toggle, ALPHA_ENABLED)
                views.setImageAlpha(R.id.widget_previous, if (snapshot.hasPrevious) ALPHA_ENABLED else ALPHA_DISABLED)
                views.setImageAlpha(R.id.widget_next, if (snapshot.hasNext) ALPHA_ENABLED else ALPHA_DISABLED)
                for ((button, action) in TRANSPORT) {
                    views.setOnClickPendingIntent(button, MediaWidgetActions.pendingIntent(context, action))
                }
            } else {
                // Nothing to control yet; every button opens the app instead. See MediaWidget.views.
                for ((button, _) in TRANSPORT) {
                    views.setImageAlpha(button, ALPHA_DISABLED)
                    views.setOnClickPendingIntent(button, open)
                }
            }

            // Signed out, or nothing liked yet: no row at all, and the now-playing
            // half grows into the room — see the layouts.
            views.setViewVisibility(R.id.widget_liked_row, if (liked.isEmpty()) View.GONE else View.VISIBLE)
            TILES.forEachIndexed { i, tile ->
                val song = liked.getOrNull(i)
                if (song == null) {
                    views.setViewVisibility(tile, View.INVISIBLE)
                    return@forEachIndexed
                }
                views.setViewVisibility(tile, View.VISIBLE)
                tiles.getOrNull(i)?.let { views.setImageViewBitmap(tile, it) }
                views.setContentDescription(tile, song.title)
                views.setOnClickPendingIntent(tile, playSong(context, i, song.videoId))
            }
            return views
        }

        private fun RemoteViews.setImageAlpha(viewId: Int, alpha: Int) =
            setInt(viewId, "setImageAlpha", alpha)

        /** Same intent as [MediaWidget]'s, and why it has no action is explained there. */
        private fun openPlayer(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(PlayerDeepLink.EXTRA_OPEN_PLAYER, true)
            return PendingIntent.getActivity(
                context,
                REQUEST_OPEN_PLAYER,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** A YouTube Music watch link, aimed at this app: MusicLink plays it. */
        private fun playSong(context: Context, index: Int, videoId: String): PendingIntent {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://music.youtube.com/watch?v=${Uri.encode(videoId)}"),
                context,
                MainActivity::class.java,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context,
                REQUEST_LIKED_BASE + index,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun AppWidgetManager.push(id: Int, views: RemoteViews) {
            runCatching { updateAppWidget(id, views) }
        }

        private val TRANSPORT = listOf(
            R.id.widget_previous to MediaWidgetActions.ACTION_PREVIOUS,
            R.id.widget_toggle to MediaWidgetActions.ACTION_TOGGLE,
            R.id.widget_next to MediaWidgetActions.ACTION_NEXT,
        )

        private val TILES = intArrayOf(
            R.id.widget_liked_0,
            R.id.widget_liked_1,
            R.id.widget_liked_2,
            R.id.widget_liked_3,
            R.id.widget_liked_4,
        )

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** The cover each instance shows; per instance, since each can be a different size. */
        private val lastArt = ConcurrentHashMap<Int, Bitmap>()

        private val lastBody = ConcurrentHashMap<Int, Bitmap>()

        /** Four cells by two, by the launcher's 70n − 30 arithmetic, for a host that hasn't said. */
        private const val FALLBACK_WIDTH_DP = 250
        private const val FALLBACK_HEIGHT_DP = 110

        private const val MAX_SIDE_PX = 1_600

        /**
         * The height from which the track goes over the transport rather than
         * beside it, with the cover as tall as its row. Samsung's 4×2 is ~190dp;
         * the launchers' nominal 110dp is well under.
         */
        private const val TALL_MIN_DP = 150

        /** Corner radius of the cover and tiles, as a share of their side. */
        private const val ART_RADIUS_FRACTION = 0.08f

        private const val RENDER_TIMEOUT_MS = 8_000L

        private const val ALPHA_ENABLED = 255
        private const val ALPHA_DISABLED = 90

        private const val REQUEST_OPEN_PLAYER = 0
        private const val REQUEST_LIKED_BASE = 200
    }
}
