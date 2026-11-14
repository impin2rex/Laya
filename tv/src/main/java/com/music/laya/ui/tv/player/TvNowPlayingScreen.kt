package com.music.laya.ui.tv.player

import androidx.compose.foundation.layout.padding
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import com.music.laya.playback.swapToVersion
import com.music.laya.data.model.Song
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.unit.dp
import com.music.laya.ui.tv.components.tvInitialFocus
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.music.laya.data.LikeState
import com.music.laya.data.model.LikeStatus
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.settings.MusicVideoChoice
import com.music.laya.playback.PlayerState
import com.music.laya.playback.toggleAutoplay
import com.music.laya.playback.toggleShuffle
import com.music.laya.ui.MainViewModel
import com.music.laya.ui.tv.dialogs.TvSongActionMenuDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the transport stays up after the last key press. */
private const val ControlsTimeoutMs = 5_000L

/** One D-pad press on the scrubber. */
private const val ScrubStepMs = 10_000L

/** How long the scrubber waits for more presses before it actually seeks. */
private const val ScrubCommitDelayMs = 650L

private enum class PlayerSheet { Menu, VideoQuality }

/**
 * Now Playing, as Apple Music has it on Apple TV.
 *
 * The screen is the song: the artwork-coloured backdrop, the cover and — when
 * there are lyrics — the lyrics beside it. The transport (title, buttons and
 * scrubber) rises on any press and steps away after a few idle seconds. Down
 * from the scrubber opens Up Next. Back closes Up Next, then the transport,
 * then the player.
 *
 * The playhead is read only inside [TvScrubber]'s draw pass and its once-a-second
 * time labels, so playback ticks recompose nothing else on this screen.
 */
@Composable
fun TvNowPlayingScreen(
    viewModel: MainViewModel,
    mediaController: MediaController?,
    playerState: PlayerState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = playerState.song
    if (song == null) {
        BackHandler(onBack = onBack)
        TvNothingPlaying(onBrowse = onBack, modifier = modifier)
        return
    }

    val scope = rememberCoroutineScope()
    val isPlaying = playerState.isPlaying

    // Keep the TV awake (no screensaver) while a song plays on this screen.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(isPlaying) {
        view.keepScreenOn = isPlaying
        onDispose { view.keepScreenOn = false }
    }
    val position = playerState.position
    val durationMs = playerState.durationMs

    val lyrics by viewModel.lyrics.collectAsState()
    val lyricsChecked by viewModel.lyricsChecked.collectAsState()
    val likeOverrides by LikeState.overrides.collectAsState()
    val isLiked = likeOverrides[song.videoId] == LikeStatus.LIKE
    val shuffle by com.music.laya.playback.QueueShuffle.enabled.collectAsState()
    val autoplay by AppSettings.autoplay.collectAsState()

    LaunchedEffect(song.videoId, durationMs) {
        viewModel.loadLyrics(
            videoId = song.videoId,
            title = song.title,
            artist = song.artist,
            durationMs = durationMs.coerceAtLeast(0L),
            album = song.albumName,
        )
    }

    // A music video's picture follows this controller — see MusicVideoEngine.
    androidx.compose.runtime.DisposableEffect(mediaController) {
        com.music.laya.ui.player.MusicVideoEngine.attach(mediaController)
        onDispose { com.music.laya.ui.player.MusicVideoEngine.attach(null) }
    }
    // The other cut of this track — its music video, or the song itself — looked up as it starts,
    // and swapped in at the playhead on the transport's switch. Never in a party: everyone in one
    // hears the same cut.
    val inParty = com.music.laya.data.listentogether.ListenTogether.state.collectAsState().value.inParty
    val versionSwitching by AppSettings.versionAlignmentInProgress.collectAsState()
    val versionPairs = remember { mutableMapOf<String, Song>() }
    var alternate by remember(song.videoId) { mutableStateOf<Song?>(null) }
    // Swaps this queue entry for [other] at the playhead, keeping its place in the queue.
    fun swapTo(other: Song) {
        val target = other.copy(
            isVideoOrigin = song.isVideo,
            queueTier = song.queueTier,
            queueEntryId = song.queueEntryId,
            radioName = song.radioName,
            playbackSource = song.playbackSource,
            playbackSourceType = song.playbackSourceType,
            playbackSourceId = song.playbackSourceId,
        )
        versionPairs[target.videoId] = song
        // Switched to on purpose: a video's picture comes up with it.
        if (target.isVideo) MusicVideoChoice.watching.value = target.videoId
        mediaController?.swapToVersion(target)
    }
    // "Prefer music-only version": a video plays as a song — its audio release when there is one,
    // its own sound with no picture otherwise — until the listener switches to the video.
    val preferMusicOnly by AppSettings.preferMusicOnly.collectAsState()
    val watchingVideo by MusicVideoChoice.watching.collectAsState()
    LaunchedEffect(song.videoId, inParty) {
        alternate = null
        MusicVideoChoice.onTrackChanged(song.videoId)
        // A saved song still offers its music video, streamed; a saved video is never swapped away
        // from the file for a stream of its audio.
        if (inParty || (song.localUri != null && song.isVideo)) return@LaunchedEffect
        val other = versionPairs[song.videoId] ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                if (song.isVideo) {
                    com.music.laya.data.YtMusicRepository.resolveAudio(song).takeIf { it.videoId != song.videoId }
                } else {
                    com.music.laya.data.YtMusicRepository.resolveVideo(song)
                        ?.takeIf { it.videoId != song.videoId && com.music.laya.ui.player.MusicVideoEngine.hasPicture(it.videoId) }
                }
            }.getOrNull()
        }
        alternate = other
        if (other != null && song.isVideo && !other.isVideo && AppSettings.preferMusicOnly.value &&
            MusicVideoChoice.watching.value != song.videoId
        ) {
            swapTo(other)
        }
    }
    val toggleVideo: (() -> Unit)? = alternate?.let { other -> { swapTo(other) } }
        // No audio version to go to: the switch only puts the video's picture up or away.
        ?: song.takeIf { it.isVideo && preferMusicOnly }?.let { video -> { MusicVideoChoice.toggle(video.videoId) } }
    val videoMode = MusicVideoChoice.pictureWanted(song, preferMusicOnly, watchingVideo)
    // The aspect button's choice — see [com.music.laya.data.settings.VideoScale].
    val videoScale by AppSettings.videoScale.collectAsState()
    var videoRendered by remember(song.videoId) { mutableStateOf(false) }

    var lyricsHidden by rememberSaveable { mutableStateOf(false) }
    val hasLyrics = !lyrics.isNullOrEmpty()
    val showLyrics = hasLyrics && !lyricsHidden
    // Romanize and translate: the phone's own, with its languages and notices.
    val lyricVersions = com.music.laya.ui.player.rememberLyricsVersions(song.videoId, lyrics)

    var controlsVisible by remember { mutableStateOf(true) }
    var upNextOpen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var lastInput by remember { mutableLongStateOf(0L) }
    var flash by remember { mutableStateOf<TvFlash?>(null) }
    var flashTick by remember { mutableIntStateOf(0) }
    // A scrub in progress: where the playhead will go once the presses stop.
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var scrubJob by remember { mutableStateOf<Job?>(null) }

    val stageFocus = remember { FocusRequester() }
    val scrubberFocus = remember { FocusRequester() }
    val upNextFocus = remember { FocusRequester() }

    val togglePlay: () -> Unit = {
        mediaController?.let { if (it.isPlaying) it.pause() else it.play() }
    }
    val showFlash: (TvFlash) -> Unit = {
        flash = it
        flashTick++
    }
    val skipNext: () -> Unit = {
        mediaController?.seekToNextMediaItem()
        if (!controlsVisible) showFlash(TvFlash.Next)
    }
    val skipPrevious: () -> Unit = {
        mediaController?.seekToPrevious()
        if (!controlsVisible) showFlash(TvFlash.Previous)
    }
    val commitScrub: () -> Unit = {
        scrubJob?.cancel()
        scrubTarget?.let { mediaController?.seekTo(it) }
        scrubTarget = null
    }
    val scrubBy: (Long) -> Unit = { delta ->
        val from = scrubTarget ?: position.positionMs
        scrubTarget = (from + delta).coerceIn(0L, durationMs.coerceAtLeast(0L))
        scrubJob?.cancel()
        scrubJob = scope.launch {
            delay(ScrubCommitDelayMs)
            commitScrub()
        }
    }
    val revealControls: () -> Unit = { controlsVisible = true }
    val hideControls: () -> Unit = {
        upNextOpen = false
        controlsVisible = false
    }

    // The transport steps away after a few idle seconds — never while Up Next
    // or a sheet is open, a scrub is pending, or the music is paused.
    LaunchedEffect(controlsVisible, lastInput, upNextOpen, sheet, isPlaying, scrubTarget) {
        if (controlsVisible && !upNextOpen && sheet == null && isPlaying && scrubTarget == null) {
            delay(ControlsTimeoutMs)
            hideControls()
        }
    }
    // Focus follows what's on screen: the scrubber when the transport rises,
    // the stage (which takes the remote's presses) when it goes.
    LaunchedEffect(controlsVisible) {
        delay(60)
        runCatching { if (controlsVisible) scrubberFocus.requestFocus() else stageFocus.requestFocus() }
    }
    LaunchedEffect(upNextOpen) {
        if (upNextOpen) {
            delay(60)
            runCatching { upNextFocus.requestFocus() }
        } else if (controlsVisible) {
            runCatching { scrubberFocus.requestFocus() }
        }
    }

    BackHandler(enabled = sheet == null) {
        when {
            upNextOpen -> upNextOpen = false
            controlsVisible -> hideControls()
            else -> onBack()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastInput = System.nanoTime()
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                        togglePlay()
                        if (!controlsVisible) showFlash(if (isPlaying) TvFlash.Pause else TvFlash.Play)
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { mediaController?.play(); true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { mediaController?.pause(); true }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> { skipNext(); true }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { skipPrevious(); true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { revealControls(); scrubBy(ScrubStepMs); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { revealControls(); scrubBy(-ScrubStepMs); true }
                    else -> false
                }
            },
    ) {
        TvPlayerBackground(artworkUrl = song.thumbnailUrl)

        // The stage: takes the remote while the transport is down. Select or a
        // vertical press brings the transport up; left and right change track.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(stageFocus)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || controlsVisible) return@onKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { revealControls(); true }
                        KeyEvent.KEYCODE_DPAD_LEFT -> { skipPrevious(); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { skipNext(); true }
                        else -> false
                    }
                }
                .focusable(),
        )

        val dim by animateFloatAsState(if (upNextOpen) 0.12f else 1f, tween(280), label = "stageDim")
        // A music video is the whole stage, letterboxed in black; the cover holds it until the
        // first frame lands.
        if (videoMode) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (videoRendered) dim else 0f }
                    .background(androidx.compose.ui.graphics.Color.Black),
            )
            com.music.laya.ui.player.AndroidMusicVideo(
                videoId = song.videoId,
                scale = videoScale,
                onRenderedChanged = { videoRendered = it },
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = dim },
                priority = 2,
            )
            TvVideoScaleFlash(videoScale, Modifier.align(androidx.compose.ui.Alignment.Center))
        }
        if (!videoMode || !videoRendered) TvNowPlayingStage(
            song = song,
            isPlaying = isPlaying,
            position = position,
            lyrics = lyrics.orEmpty(),
            showLyrics = showLyrics,
            lyricsLoading = !lyricsChecked && lyrics == null && !lyricsHidden,
            controlsVisible = controlsVisible,
            onSeek = { mediaController?.seekTo(it) },
            subLines = lyricVersions.subLines,
            modifier = Modifier.graphicsLayer { alpha = dim },
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(240)) + slideInVertically(tween(320)) { it / 6 },
            exit = fadeOut(tween(320)) + slideOutVertically(tween(320)) { it / 6 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            TvTransport(
                song = song,
                isPlaying = isPlaying,
                isLoading = playerState.isLoading,
                position = position,
                durationMs = durationMs,
                scrubTarget = scrubTarget,
                isLiked = isLiked,
                // Over a music video the lyrics button is the way back to the song, lyrics up:
                // the video covers the stage they would show on.
                lyricsAvailable = hasLyrics || (videoMode && toggleVideo != null),
                lyricsOn = showLyrics && !videoMode,
                lyricVersions = lyricVersions,
                upNextOpen = upNextOpen,
                scrubberFocus = scrubberFocus,
                onScrub = scrubBy,
                onSelectOnScrubber = {
                    if (scrubTarget != null) commitScrub() else togglePlay()
                },
                onOpenUpNext = { upNextOpen = true },
                onToggleLike = { viewModel.toggleLike(song.videoId) },
                onToggleLyrics = {
                    val backToSong = toggleVideo?.takeIf { videoMode }
                    if (backToSong != null) {
                        lyricsHidden = false
                        backToSong()
                    } else {
                        lyricsHidden = !lyricsHidden
                    }
                },
                onToggleUpNext = { upNextOpen = !upNextOpen },
                onMore = { sheet = PlayerSheet.Menu },
                onToggleVideo = toggleVideo,
                playingVideo = videoMode,
                onOpenVideoQuality = if (videoMode) ({ sheet = PlayerSheet.VideoQuality }) else null,
                versionSwitching = versionSwitching,
                upNext = {
                    TvUpNext(
                        queue = playerState.queue,
                        currentIndex = playerState.queueIndex,
                        shuffle = shuffle,
                        repeatMode = playerState.repeatMode,
                        autoplay = autoplay,
                        firstCardFocus = upNextFocus,
                        onJump = { index ->
                            mediaController?.seekToDefaultPosition(index)
                            mediaController?.play()
                        },
                        onToggleShuffle = { mediaController?.toggleShuffle() },
                        onCycleRepeat = {
                            mediaController?.let {
                                val next = when (it.repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                    else -> Player.REPEAT_MODE_OFF
                                }
                                AppSettings.setRepeatMode(next)
                                it.repeatMode = next
                            }
                        },
                        onToggleAutoplay = { mediaController?.toggleAutoplay() },
                    )
                },
            )
        }

        TvFlashGlyph(
            flash = flash,
            tick = flashTick,
            modifier = Modifier.align(Alignment.Center),
        )
    }

    when (sheet) {
        PlayerSheet.Menu -> TvSongActionMenuDialog(
            song = song,
            isLiked = isLiked,
            viewModel = viewModel,
            mediaController = mediaController,
            onToggleLike = { viewModel.toggleLike(song.videoId) },
            onDismiss = { sheet = null },
        )
        PlayerSheet.VideoQuality -> TvVideoQualityDialog(onDismiss = { sheet = null })
        null -> Unit
    }
}

/**
 * The phone's video quality drawer as a tvOS alert: Auto, then every height the playing video
 * offers. A pick is held from then on, for the next video too, where that video has it.
 */
@Composable
private fun TvVideoQualityDialog(onDismiss: () -> Unit) {
    val engine = com.music.laya.ui.player.MusicVideoEngine
    val quality by engine.quality.collectAsState()
    // What is on screen when it is lower than the pick: this TV couldn't play the pick.
    val playing = quality.activeHeight
    fun label(height: Int) = when {
        height >= 2160 -> "4K"
        height >= 1440 -> "2K"
        height >= 1080 -> "Full HD"
        height >= 720 -> "HD"
        else -> "${height}p"
    }
    com.music.laya.ui.tv.components.TvDialog(title = "Video Quality", onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Column(
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp),
        ) {
            com.music.laya.ui.tv.components.TvListRow(
                title = "Auto",
                value = if (quality.held == null) playing?.let(::label) else null,
                trailingIcon = if (quality.held == null) androidx.compose.material.icons.Icons.Rounded.Check else null,
                modifier = Modifier.tvInitialFocus(),
                onClick = {
                    engine.setQuality(null)
                    onDismiss()
                },
            )
            quality.heights.forEach { height ->
                val picked = quality.held == height
                // Failed to play on this TV: shown, but not offered.
                val failed = height in quality.failed
                com.music.laya.ui.tv.components.TvListRow(
                    title = label(height),
                    value = when {
                        failed -> "Can't play on this TV"
                        picked && playing != null && playing < height -> "${playing}p playing"
                        else -> "${height}p"
                    },
                    trailingIcon = if (picked) androidx.compose.material.icons.Icons.Rounded.Check else null,
                    enabled = !failed,
                    onClick = {
                        engine.setQuality(height)
                        onDismiss()
                    },
                )
            }
        }
    }
}

/** The aspect's name in the middle of the picture for a moment after it changes, as VLC shows it. */
@Composable
private fun TvVideoScaleFlash(scale: com.music.laya.data.settings.VideoScale, modifier: Modifier = Modifier) {
    var seen by remember { mutableStateOf(scale) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(scale) {
        if (scale == seen) return@LaunchedEffect
        seen = scale
        shown = true
        kotlinx.coroutines.delay(1_200)
        shown = false
    }
    if (!shown) return
    val label = when (scale) {
        com.music.laya.data.settings.VideoScale.BEST_FIT -> com.music.laya.R.string.video_scale_best_fit
        com.music.laya.data.settings.VideoScale.FIT_SCREEN -> com.music.laya.R.string.video_scale_fit_screen
        com.music.laya.data.settings.VideoScale.FILL -> com.music.laya.R.string.video_scale_fill
    }
    androidx.compose.material3.Text(
        text = androidx.compose.ui.res.stringResource(label),
        color = androidx.compose.ui.graphics.Color.White,
        fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp),
        modifier = modifier
            .background(
                androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
            )
            .padding(horizontal = 28.dp, vertical = 12.dp),
    )
}
