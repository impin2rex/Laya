package com.music.laya.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.music.laya.data.lyrics.LyricLine
import com.music.laya.playback.PlaybackPosition
import com.music.laya.ui.haptics.rememberHaptics
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [LyricsPanel] for a screen in another module — the Android TV app's — that
 * only holds a [PlaybackPosition]: builds the lyric playhead from it, so the
 * internal playhead type never has to leave this module.
 */
@Composable
fun SyncedLyricsPanel(
    lines: List<LyricLine>,
    trackKey: String,
    position: PlaybackPosition,
    looking: Boolean,
    isPlaying: Boolean,
    onSeekToLine: (Long) -> Unit,
    /** See [LyricsPanel]'s own: the sung line held mid-panel, not at its top. */
    centerFocus: Boolean = false,
    /** A translation or romanization drawn small under each line — see [rememberLyricsVersions]. */
    subLines: List<LyricLine>? = null,
    modifier: Modifier = Modifier,
) {
    LyricsPanel(
        lines = lines,
        subLines = subLines,
        trackKey = trackKey,
        playhead = rememberLyricPlayhead(position),
        looking = looking,
        isPlaying = isPlaying,
        onSeekToLine = onSeekToLine,
        controlsOpen = true,
        onRevealControls = {},
        onHideControls = {},
        centerFocus = centerFocus,
        // Not on the TV, where the lyrics are on screen nearly all the time: measured on a 4-core armv7 TV, the
        // separation took 74 s of model time per 5.8 s of audio and starved Automix's analysis, which then finished
        // minutes after the blend (4.5 min for a 4:37 track) and every mix fell back to a plain fade.
        warmKaraoke = false,
        modifier = modifier,
    )
}

/**
 * The phone's lyric translation and romanization for a screen in another
 * module — the Android TV app's — reduced to what its buttons and panel need.
 * Same languages, same requests, same notices as the phone; see
 * [rememberLyricsTranslation].
 */
class LyricsVersions internal constructor(
    /** For [SyncedLyricsPanel]'s `subLines`: the version on show, or null for the originals alone. */
    val subLines: List<LyricLine>?,
    val showingTranslation: Boolean,
    val showingRomanization: Boolean,
    /** A request is on its way. */
    val translating: Boolean,
    val romanizing: Boolean,
    /** Nothing to do: the lyrics are already in the language, or already Latin script. */
    val translationPointless: Boolean,
    val romanizationPointless: Boolean,
    val toggleTranslation: () -> Unit,
    val toggleRomanization: () -> Unit,
)

@Composable
fun rememberLyricsVersions(trackId: String, lyrics: List<LyricLine>?): LyricsVersions {
    val ui = rememberLyricsTranslation(
        trackId = trackId,
        lyrics = lyrics,
        lyricsSource = null,
        lyricsUnavailable = false,
        loadingText = "",
        haptics = rememberHaptics(),
    )
    return LyricsVersions(
        subLines = ui.subLines,
        showingTranslation = ui.showingTranslation,
        showingRomanization = ui.showingRomanization,
        translating = ui.translationState is LyricsTranslationUiState.Loading,
        romanizing = ui.romanizationState is LyricsTranslationUiState.Loading,
        translationPointless = ui.translationState is LyricsTranslationUiState.SameLanguage,
        romanizationPointless = ui.romanizationState is LyricsTranslationUiState.SameLanguage,
        toggleTranslation = ui.toggleTranslation,
        toggleRomanization = ui.toggleRomanization,
    )
}

/**
 * The translation or romanization the app's player has under its lyrics, by track — line for line
 * with the lyrics the app settled on — for the lock screens (Android's player, the iPhone's lyrics
 * card), which draw the same. Null while it shows the originals alone.
 */
object LyricsOnShow {
    val subLines = MutableStateFlow<Pair<String, List<LyricLine>>?>(null)
}
