package com.music.laya.ui.screens

import androidx.compose.runtime.Composable
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PartyInfo(val code: String?, val memberCount: Int)

/** One line describing the audio output ("sink · device · 48 kHz · PCM 16"), and the two facts rows branch on. */
class AudioOutputInfo(val summary: String, val isUsb: Boolean, val isFloat: Boolean)

class RefreshRates(val supported: List<Int>, val selected: Int, val openBatterySettings: () -> Unit)

class LocalMusicFolder(val label: String?, val pick: () -> Unit)

class BackupActions(val export: () -> Unit, val import: () -> Unit)

/**
 * What [SettingsScreen] needs from the platform. Every default is "not available", and the
 * row behind it is hidden — which is how iOS runs; Android installs the real thing at start.
 */
interface SettingsHost {
    /** null hides the Dolby Atmos row; otherwise whether this device can decode it. */
    val playsDolbyAtmos: Boolean? get() = null
    val party: StateFlow<PartyInfo> get() = NO_PARTY
    /** null hides the output-precision and USB DAC rows. */
    val audioOutput: StateFlow<AudioOutputInfo?> get() = NO_OUTPUT
    /** false shows the "equaliser can't touch a bitstream" notice. */
    val dspAvailable: StateFlow<Boolean> get() = DSP_AVAILABLE
    /** null hides the "system equaliser" row. */
    val openSystemEqualizer: (() -> Unit)? get() = null
    val hasDownloads: Boolean get() = false // Phase 6
    val hasSongCache: Boolean get() = false
    val hasBackgroundService: Boolean get() = false
    /**
     * false hides the rows that only drive Android's playback service: streaming quality ceilings,
     * prefer music only, loudness, crossfade / automix, don't repeat, hide status bar, version alignment.
     */
    val hasEngineOptions: Boolean get() = true
    /** false hides just the Wi-Fi / mobile-data streaming-quality rows, which are what addons are asked at. */
    val hasQualityOptions: Boolean get() = hasEngineOptions
    val appIconRow: (@Composable () -> Unit)? get() = null

    @Composable fun versionName(): String = ""
    @Composable fun appLanguageName(): String = ""
    @Composable fun WearOsRow(onClick: () -> Unit) {}
    @Composable fun rememberRefreshRates(stored: Int, onBatteryReturn: () -> Unit): RefreshRates? = null
    @Composable fun rememberLocalFolder(uri: String): LocalMusicFolder? = null
    /** [onExported]/[onImported] get the one-line outcome to show under the row. */
    @Composable fun rememberBackup(onExported: (String) -> Unit, onImported: (String) -> Unit): BackupActions? = null

    fun clearSongCache() {}
    fun clearImageCache() {}
}

private val NO_PARTY = MutableStateFlow(PartyInfo(null, 0))
private val DSP_AVAILABLE = MutableStateFlow(true)
private val NO_OUTPUT = MutableStateFlow<AudioOutputInfo?>(null)

object SettingsExtras {
    @Volatile
    var host: SettingsHost = object : SettingsHost {}
}
