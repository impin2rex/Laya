package com.music.laya.ios

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.music.laya.data.canvas.CanvasArtwork
import com.music.laya.data.lyrics.LyricLine
import com.music.laya.data.lyrics.LyricsSource
import com.music.laya.data.lyrics.LyricsTranslation
import com.music.laya.data.model.Song
import com.music.laya.data.settings.AudioQuality
import com.music.laya.data.settings.LastPlayerScreen
import com.music.laya.data.settings.LibrarySort
import com.music.laya.data.settings.LibraryViewType
import com.music.laya.data.settings.MixBlend
import com.music.laya.data.settings.SmartAnalysis
import com.music.laya.data.settings.TransitionWindow
import com.music.laya.ui.AppUiHost
import com.music.laya.ui.player.AudioOutputDevice
import com.music.laya.ui.player.CanvasVideoSpec
import com.music.laya.ui.player.LyricsRomanizationResult
import com.music.laya.ui.player.LyricsTranslationResult
import com.music.laya.ui.player.OutputFormatUi
import com.music.laya.ui.player.PartyUi
import com.music.laya.ui.player.PlayerHost
import com.music.laya.ui.player.PlayerSettingsSource
import com.music.laya.ui.player.SystemVolume
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ponytail: in-memory settings at Android's defaults; 4d swaps them for multiplatform-settings.

internal object IosAppUiHost : AppUiHost {
    override val swipeToPlayNext = MutableStateFlow(false)
    private val recents = MutableStateFlow(LibraryViewType.LIST)
    override val homeRecentsViewType: StateFlow<LibraryViewType> = recents
    override fun setHomeRecentsViewType(value: LibraryViewType) { recents.value = value }
    override val pinnedPlaylists = MutableStateFlow(emptyList<String>())
    override val librarySort = MutableStateFlow(LibrarySort.DEFAULT)

    @Composable
    override fun downloadedIds(): Set<String> = emptySet() // TODO(4d): downloads
}

internal object IosPlayerHost : PlayerHost {
    override val settings: PlayerSettingsSource = IosPlayerSettings

    override fun cachedCanvas(song: Song): CanvasArtwork? = null
    override suspend fun canvasFor(song: Song): CanvasArtwork? = null

    @Composable
    override fun CanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) = Unit

    @Composable
    override fun rememberRemoteArtworkUrl(song: Song?): String? = song?.thumbnailUrl

    override val volume = object : SystemVolume {
        override val level = MutableStateFlow(1f)
        override fun set(level: Float) = Unit
        override fun refresh() = Unit
    }

    @Composable
    override fun rememberAudioOutputs(): List<AudioOutputDevice> = emptyList()

    @Composable
    override fun rememberOutputPicker(onOpen: () -> Unit): () -> Unit = onOpen

    override fun selectAudioOutput(id: Int?) = Unit
    override val outputFormat = MutableStateFlow(OutputFormatUi())
    override val party = MutableStateFlow(PartyUi())

    override suspend fun translateLyrics(trackId: String, lines: List<LyricLine>, targetLanguageTag: String) =
        when (val result = LyricsTranslation.translate(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.Result.Translated -> LyricsTranslationResult.Translated(result.lines)
            is LyricsTranslation.Result.SameLanguage -> LyricsTranslationResult.SameLanguage(result.language)
            LyricsTranslation.Result.Unavailable -> LyricsTranslationResult.Unavailable
        }

    override suspend fun romanizeLyrics(trackId: String, lines: List<LyricLine>, targetLanguageTag: String) =
        when (val result = LyricsTranslation.romanize(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.RomanizationResult.Romanized -> LyricsRomanizationResult.Romanized(result.lines)
            LyricsTranslation.RomanizationResult.AlreadyRomanized -> LyricsRomanizationResult.AlreadyRomanized
            LyricsTranslation.RomanizationResult.Unavailable -> LyricsRomanizationResult.Unavailable
        }

    /** The newest notice, shown by [LayaIosApp] as a snackbar. */
    val messages = MutableStateFlow<String?>(null)
    override fun showMessage(message: String) { messages.value = message }

    @Composable
    override fun AudioPipelineDialog(hazeState: HazeState, isPlaying: Boolean, onDismiss: () -> Unit) = Unit
}

private object IosPlayerSettings : PlayerSettingsSource {
    override val animatedCanvas = MutableStateFlow(false) // no canvas decoder on iOS
    override val audioQualityCellular = MutableStateFlow(AudioQuality.LOSSLESS)
    override val audioQualityWifi = MutableStateFlow(AudioQuality.LOSSLESS)
    override val canvasOverCellular = MutableStateFlow(true)
    override val fullBleedArtwork = MutableStateFlow(false)
    override val hideSongStatus = MutableStateFlow(false)
    override val showQueueHistory = MutableStateFlow(false)
    override val hideVolumeBar = MutableStateFlow(true) // no system volume bridge yet
    override val hideStatusBar = MutableStateFlow(false)
    override val lastPlayerScreen = MutableStateFlow(LastPlayerScreen.MAIN)
    override val legacyMeshGradient = MutableStateFlow(false)
    override val lyricsBlur = MutableStateFlow(true)
    override val lyricsOffsetMs = MutableStateFlow(0)
    override val lyricsSourceOrder = MutableStateFlow<List<LyricsSource>>(LyricsSource.offered)
    override val meteredConnection = MutableStateFlow<Boolean?>(null)
    override val preferUsbDac = MutableStateFlow(false)
    override val prioritizeSpotifyCanvas = MutableStateFlow(false)
    override val reduceAnimation = MutableStateFlow(false)
    override val reduceDynamicBlur = MutableStateFlow(false)
    override val reverbEnabled = MutableStateFlow(false)
    override val showNerdStats = MutableStateFlow(false)
    override val smartAnalysis = MutableStateFlow(SmartAnalysis())
    override val smartFadeEnabled = MutableStateFlow(false)
    override val smartMixInProgress = MutableStateFlow(false)
    override val smartMixBlend = MutableStateFlow<MixBlend?>(null)
    override val smartTransitionWindow = MutableStateFlow<TransitionWindow?>(null)
    override val spotifyCanvasAutoHide = MutableStateFlow(true)
    override val syncedLyrics = MutableStateFlow(true)
    override val translationLanguage = MutableStateFlow("")
    override val versionAlignmentInProgress = MutableStateFlow(false)

    override fun setLastPlayerScreen(value: LastPlayerScreen) { lastPlayerScreen.value = value }
    override fun setLyricsOffsetMs(value: Int) { lyricsOffsetMs.value = value }
    override fun setReverbEnabled(value: Boolean) { reverbEnabled.value = value }
}
