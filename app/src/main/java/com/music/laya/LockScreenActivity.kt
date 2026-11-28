package com.music.laya

import androidx.compose.animation.core.tween
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import coil3.compose.AsyncImage
import com.music.laya.auth.SignedIn
import com.music.laya.data.LikeState
import com.music.laya.data.model.LikeStatus
import com.music.laya.data.model.artworkAt
import com.music.laya.data.settings.AppSettings
import com.music.laya.playback.ACTION_TOGGLE_FAVORITE
import com.music.laya.playback.ServiceLyrics
import com.music.laya.playback.rememberMediaController
import com.music.laya.playback.rememberPlayerState
import com.music.laya.ui.player.LyricsOnShow
import com.music.laya.ui.player.SyncedLyricsPanel
import com.music.laya.ui.player.rememberMiniAutoMixArtwork
import com.music.laya.ui.player.ThinSlider
import com.music.laya.ui.theme.LayaTheme
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale

/**
 * The lock-screen player: the playing track's artwork full-bleed, a clock,
 * its synced lyrics and the transport, drawn over the keyguard. Started by
 * PlaybackService as the screen comes on (see `screenOnReceiver` there) when
 * [AppSettings.lockScreenPlayer] is on.
 *
 * Closing it — Unlock, swipe up, Back — only finishes it: the system lock
 * screen is still underneath and asks for the PIN or fingerprint as usual.
 * It also goes as soon as the phone is unlocked some other way, and (being
 * `noHistory`) when the screen goes off.
 */
class LockScreenActivity : ComponentActivity() {

    private val unlockedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = close()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        // Arrived by way of the full-screen notification: it has done its job.
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        enableEdgeToEdge()
        ContextCompat.registerReceiver(
            this,
            unlockedReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        setContent {
            val systemFont by AppSettings.systemFont.collectAsState()
            LayaTheme(darkTheme = true, systemFont = systemFont) {
                LockScreen(onClose = ::close)
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlockedReceiver) }
        if (isFinishing) closedAt = SystemClock.elapsedRealtime()
        super.onDestroy()
    }

    private fun close() {
        closedAt = SystemClock.elapsedRealtime()
        finishAndRemoveTask()
    }

    companion object {
        @Volatile private var closedAt = 0L

        private const val CHANNEL_ID = "laya_lock_screen"
        private const val NOTIFICATION_ID = 10_023

        /**
         * Puts the player up from the background. Directly with "Display over
         * other apps"; otherwise by a full-screen notification, which the system
         * turns into the activity itself while the keyguard is up. With neither
         * allowed, nothing: the system lock screen's media card stands.
         */
        fun show(context: Context) {
            val intent = intent(context)
            if (Settings.canDrawOverlays(context) && runCatching { context.startActivity(intent) }.isSuccess) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 34 && !manager.canUseFullScreenIntent()) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.lock_screen_player),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
            val pending = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.lock_screen_player))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pending, true)
                .setAutoCancel(true)
                .setSilent(true)
                .build()
            runCatching { manager.notify(NOTIFICATION_ID, notification) }
        }

        /**
         * True for a moment after this screen closed. Its task going away can
         * reach PlaybackService.onTaskRemoved without saying whose task it was,
         * and that must not read as the app being swiped away.
         */
        fun closedRecently(): Boolean = SystemClock.elapsedRealtime() - closedAt < 1_000L

        fun intent(context: Context): Intent = Intent(context, LockScreenActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
    }
}

/** Where the sharp cover's bottom edge sits, as a share of the screen's height: just above the controls. */
private const val COVER_BOTTOM = 0.78f

/** How much of the cover's height fades into the blur, at its top and again at its bottom. */
private const val COVER_FEATHER = 0.2f

/** How soft the mirror images are: enough to lose detail, not the shapes. */
private val REFLECTION_BLUR = 7.dp

/** How far past the screen the blurred layer reaches. */
private val BLUR_MARGIN = 40.dp

/** Sizes the two covers are fetched at: the sharp one fills a phone's width; the blurred one needs little. */
private const val COVER_PX = 1080
private const val BACKDROP_PX = 240

@Composable
private fun LockScreen(onClose: () -> Unit) {
    val controller = rememberMediaController()
    val state = rememberPlayerState(controller)
    val song = state.song
    val serviceLyrics by ServiceLyrics.lines.collectAsState()
    val shownLyrics by ServiceLyrics.shown.collectAsState()
    // The app's own choice for this track when it has made one; the service's lookup otherwise.
    val lyrics = shownLyrics?.takeIf { it.videoId == song?.videoId }?.lines ?: serviceLyrics.takeIf { shownLyrics?.videoId != song?.videoId }
    // The app's translation or romanization, line for line with its own lyrics — so only over those.
    val onShow by LyricsOnShow.subLines.collectAsState()
    val subLines = onShow?.takeIf { it.first == song?.videoId && shownLyrics?.videoId == song?.videoId }?.second
    val likes by LikeState.overrides.collectAsState()
    val context = LocalContext.current
    val signedIn by SignedIn.state(context).collectAsState()
    val lyricsInteractive by AppSettings.lockScreenLyricsInteractive.collectAsState()
    val showProgress by AppSettings.lockScreenProgress.collectAsState()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                // Swipe up to close, as on the system lock screen.
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onVerticalDrag = { _, dy -> dragged += dy },
                    onDragEnd = { if (dragged < -size.height / 5f) onClose() },
                )
            },
    ) {
        // The cover blurred over the whole screen, as a wallpaper would be.
        // The cover blurred over the whole screen, for any edge the reflections don't reach.
        // Android 11 and older draw no blur, so there the backdrop is dimmed to stay behind the cover.
        AsyncImage(
            model = song?.thumbnailUrl.artworkAt(BACKDROP_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(48.dp, BlurredEdgeTreatment.Rectangle)
                .graphicsLayer { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) alpha = 0.35f },
        )
        // The cover itself, square and sharp, just above the controls, with a blurred mirror image of
        // it above and below — as a reflection in glass — and its own top and bottom feathered into them.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = minOf(maxWidth, maxHeight)
            val top = (maxHeight * COVER_BOTTOM - side).coerceAtLeast(0.dp)
            val cover = song?.thumbnailUrl.artworkAt(COVER_PX)
            // AutoMix's artwork transition, always on: while two songs blend, every copy of the cover —
            // the mirror images and the sharp one — dissolves to the next in step with the audio. Any other
            // song change (a skip, a plain next, Automix off) reveals the next cover over the last the same way.
            val artwork = rememberMiniAutoMixArtwork(song, state.queue, state.queueIndex, px = COVER_PX, ownSwitch = true)
            // One blurred layer — mirror image, the cover, mirror image — so the sharp cover's feathered
            // edges fade into a blur of themselves with no seam. Drawn past the screen by [BLUR_MARGIN]
            // so the blur has picture to take in at the screen's edges rather than darkening them.
            Box(
                Modifier
                    .requiredSize(maxWidth + BLUR_MARGIN * 2, maxHeight + BLUR_MARGIN * 2)
                    .graphicsLayer { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) alpha = 0.35f }
                    .blur(REFLECTION_BLUR, BlurredEdgeTreatment.Rectangle),
            ) {
                for ((y, mirrored) in listOf(top - side to true, top to false, top + side to true)) {
                    artwork(
                        cover,
                        Modifier
                            .offset(x = BLUR_MARGIN, y = y + BLUR_MARGIN)
                            .size(side)
                            .graphicsLayer { if (mirrored) scaleY = -1f },
                    )
                }
            }
            artwork(
                cover,
                Modifier
                    .offset(y = top)
                    .size(side)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                COVER_FEATHER to Color.Black,
                                1f - COVER_FEATHER to Color.Black,
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Clock()
            Box(Modifier.fillMaxWidth().height(260.dp).padding(top = 16.dp)) {
                val lines = lyrics
                // Only timed lyrics: plain text can't follow the song, so none is shown rather than that.
                if (song != null && lines != null && lines.any { it.timeMs > 0 }) {
                    SyncedLyricsPanel(
                        lines = lines,
                        trackKey = song.videoId,
                        position = state.position,
                        looking = false,
                        isPlaying = state.isPlaying,
                        onSeekToLine = { if (lyricsInteractive) controller?.seekTo(it) },
                        centerFocus = true,
                        subLines = subLines,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Read-only unless the setting says otherwise: this takes the
                    // touches so the lines can be neither tapped nor scrolled,
                    // and leaves them unconsumed for the swipe-up behind.
                    if (!lyricsInteractive) {
                        Box(
                            Modifier.fillMaxSize().pointerInput(Unit) {
                                awaitPointerEventScope { while (true) awaitPointerEvent() }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (song != null) {
                Text(
                    song.title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artist,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showProgress && state.durationMs > 0L) {
                Spacer(Modifier.height(16.dp))
                Progress(state.position.positionMs, state.durationMs) { controller?.seekTo(it) }
            }
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(Icons.Rounded.SkipPrevious, stringResource(R.string.lock_screen_previous)) {
                    controller?.seekToPrevious()
                }
                TransportButton(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(R.string.lock_screen_play_pause),
                    size = 64,
                ) {
                    controller?.run { if (isPlaying) pause() else play() }
                }
                TransportButton(Icons.Rounded.SkipNext, stringResource(R.string.lock_screen_next)) {
                    controller?.seekToNext()
                }
                // The notification's Favorite, on the same terms: signed in, and
                // not a file on the device.
                if (signedIn && song != null && song.localUri == null) {
                    val liked = likes[song.videoId] == LikeStatus.LIKE
                    TransportButton(
                        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        stringResource(R.string.lock_screen_like),
                        tint = if (liked) Color(0xFFFF4D5E) else Color.White,
                    ) { controller?.toggleFavorite() }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.lock_screen_unlock),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Clock() {
    val context = LocalContext.current
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    val locale = Locale.getDefault()
    val time = DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", now).toString()
    val date = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), now).toString()
    Row(verticalAlignment = Alignment.Bottom) {
        Text(time, color = Color.White, fontSize = 76.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.size(12.dp))
        Text(
            date,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 15.sp,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

/** The seek bar, with elapsed and total time under it. Held at the drag while scrubbing. */
@Composable
private fun Progress(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    val fraction = scrub ?: (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        ThinSlider(
            value = fraction,
            onValueChange = { scrub = it },
            onValueChangeFinished = {
                scrub?.let { onSeek((it * durationMs).toLong()) }
                scrub = null
            },
            idleHeight = 5.dp,
            activeHeight = 10.dp,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clockTime((fraction * durationMs).toLong()), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            Text(clockTime(durationMs), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}

private fun clockTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    description: String,
    size: Int = 40,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Icon(
        icon,
        contentDescription = description,
        tint = tint,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(8.dp)
            .size(size.dp),
    )
}

/** The same session command the notification's and widget's Favorite send. */
private fun MediaController.toggleFavorite() {
    sendCustomCommand(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY), Bundle.EMPTY)
}
