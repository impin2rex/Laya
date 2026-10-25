package com.music.laya.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import com.music.laya.data.canvas.CanvasArtwork
import com.music.laya.data.canvas.CanvasRepository
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
import com.music.laya.ui.player.PlayerHost
import com.music.laya.ui.player.PlayerSettingsSource
import com.music.laya.ui.player.SystemVolume
import com.music.laya.ui.screens.SettingsHost
import dev.chrisbanes.haze.HazeState
import platform.Foundation.NSBundle
import kotlinx.coroutines.flow.MutableStateFlow

/** Everything not overridden is hidden on iOS; Phase 5/6 fill in accounts, Listen Together, downloads. */
internal object IosSettingsHost : SettingsHost {
    override val hasEngineOptions = false // the AVPlayer engine has none of these yet
    override val hasCrossfade = true
    override val hasAutomix = true
    override val hasLiquidGlassOption = false
    override val party = IosParty.info
    override val hasDownloads = true
    override val hasExportDownloads = false // no shared Music folder on iOS; downloads stay in the app
    override val hasQualityOptions = true // the Wi-Fi / mobile-data ceilings decide whether addons are asked
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
    override fun downloadedIds(): Set<String> = IosDownloads.savedIds.collectAsState().value
}

internal object IosPlayerHost : PlayerHost {
    override val settings: PlayerSettingsSource = IosPlayerSettings

    override fun cachedCanvas(song: Song): CanvasArtwork? = CanvasRepository.cached(song)
    override suspend fun canvasFor(song: Song): CanvasArtwork? = CanvasRepository.canvasFor(song)

    @Composable
    override fun CanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) = IosCanvasVideo(spec, modifier)

    @Composable
    override fun rememberRemoteArtworkUrl(song: Song?): String? = song?.thumbnailUrl

    override val volume = IosVolume

    @Composable
    override fun rememberAudioOutputs(): List<AudioOutputDevice> = rememberCurrentOutputs()

    @Composable
    override fun rememberOutputPicker(onOpen: () -> Unit): () -> Unit = onOpen

    // iOS cannot switch routes itself or list idle ones: tapping the current route opens the system picker (Bluetooth, AirPlay, speaker).
    override fun selectAudioOutput(id: Int?) = showRoutePicker()
    override val outputFormat = MutableStateFlow(OutputFormatUi())
    override val party = IosParty.ui
    override val castState = IosCast.ui

    @Composable
    override fun CastDialog(hazeState: HazeState, onDismiss: () -> Unit) = IosCastDialog(hazeState, onDismiss)

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
    override fun AudioPipelineDialog(hazeState: HazeState, isPlaying: Boolean, onDismiss: () -> Unit) = IosAudioPipeline(hazeState, onDismiss)
}

private object IosPlayerSettings : PlayerSettingsSource {
    override val animatedCanvas get() = AppSettings.animatedCanvas // Spotify Canvas only on iOS
    override val audioQualityCellular get() = AppSettings.audioQualityCellular
    override val audioQualityWifi get() = AppSettings.audioQualityWifi
    override val canvasOverCellular get() = AppSettings.canvasOverCellular
    override val fullBleedArtwork get() = AppSettings.fullBleedArtwork
    override val hideSongStatus get() = AppSettings.hideSongStatus
    override val showQueueHistory get() = AppSettings.showQueueHistory
    override val hideVolumeBar get() = AppSettings.hideVolumeBar
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
