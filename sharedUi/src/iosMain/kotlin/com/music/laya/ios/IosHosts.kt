package com.music.laya.ios

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.music.laya.data.canvas.CanvasArtwork
import com.music.laya.data.lyrics.LyricLine
import com.music.laya.data.lyrics.LyricsTranslation
import com.music.laya.data.model.Song
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.settings.LastPlayerScreen
import com.music.laya.data.settings.LibraryViewType
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
import com.music.laya.ui.screens.SettingsHost
import dev.chrisbanes.haze.HazeState
import platform.Foundation.NSBundle
import kotlinx.coroutines.flow.MutableStateFlow

/** Everything not overridden is hidden on iOS; Phase 5/6 fill in accounts, Listen Together, downloads. */
internal object IosSettingsHost : SettingsHost {
    @Composable
    override fun versionName() =
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "1.0"
}

internal object IosAppUiHost : AppUiHost {
    override val swipeToPlayNext = AppSettings.swipeToPlayNext
    override val homeRecentsViewType = AppSettings.homeRecentsViewType
    override fun setHomeRecentsViewType(value: LibraryViewType) = AppSettings.setHomeRecentsViewType(value)
    override val pinnedPlaylists = AppSettings.pinnedPlaylists
    override val librarySort = AppSettings.librarySort

    @Composable
    override fun downloadedIds(): Set<String> = emptySet() // Phase 6: downloads
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
    override val audioQualityCellular get() = AppSettings.audioQualityCellular
    override val audioQualityWifi get() = AppSettings.audioQualityWifi
    override val canvasOverCellular get() = AppSettings.canvasOverCellular
    override val fullBleedArtwork get() = AppSettings.fullBleedArtwork
    override val hideSongStatus get() = AppSettings.hideSongStatus
    override val showQueueHistory get() = AppSettings.showQueueHistory
    override val hideVolumeBar = MutableStateFlow(true) // no system volume bridge yet
    override val hideStatusBar get() = AppSettings.hideStatusBarInPlayer
    override val lastPlayerScreen get() = AppSettings.lastPlayerScreen
    override val legacyMeshGradient get() = AppSettings.legacyMeshGradient
    override val lyricsBlur get() = AppSettings.lyricsBlur
    override val lyricsOffsetMs get() = AppSettings.lyricsOffsetMs
    override val lyricsSourceOrder get() = AppSettings.lyricsSourceOrder
    override val meteredConnection get() = AppSettings.meteredConnection
    override val preferUsbDac get() = AppSettings.preferUsbDac
    override val prioritizeSpotifyCanvas get() = AppSettings.prioritizeSpotifyCanvas
    override val reduceAnimation get() = AppSettings.reduceAnimation
    override val reduceDynamicBlur get() = AppSettings.reduceDynamicBlur
    override val reverbEnabled get() = AppSettings.reverbEnabled
    override val showNerdStats get() = AppSettings.showNerdStats
    override val smartAnalysis get() = AppSettings.smartAnalysis
    override val smartFadeEnabled get() = AppSettings.smartFadeEnabled
    override val smartMixInProgress get() = AppSettings.smartMixInProgress
    override val smartMixBlend get() = AppSettings.smartMixBlend
    override val smartTransitionWindow get() = AppSettings.smartTransitionWindow
    override val spotifyCanvasAutoHide get() = AppSettings.spotifyCanvasAutoHide
    override val syncedLyrics get() = AppSettings.syncedLyrics
    override val translationLanguage get() = AppSettings.translationLanguage
    override val versionAlignmentInProgress get() = AppSettings.versionAlignmentInProgress

    override fun setLastPlayerScreen(value: LastPlayerScreen) = AppSettings.setLastPlayerScreen(value)
    override fun setLyricsOffsetMs(value: Int) = AppSettings.setLyricsOffsetMs(value)
    override fun setReverbEnabled(value: Boolean) = AppSettings.setReverbEnabled(value)
}
