package com.music.laya.ui.tv.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.laya.BuildConfig
import com.music.laya.data.AppUpdateChecker
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.settings.TvSettings
import com.music.laya.ui.MainViewModel
import com.music.laya.ui.tv.TvAppMark
import com.music.laya.ui.tv.audio.TvSpatialAudioEngine
import com.music.laya.ui.tv.components.TvChromePinned
import com.music.laya.ui.tv.components.TvListHeader
import com.music.laya.ui.tv.components.TvListRow
import com.music.laya.ui.tv.dialogs.TvAboutDialog
import com.music.laya.ui.tv.dialogs.TvEqualizerDialog
import com.music.laya.ui.tv.dialogs.TvReverbDialog
import com.music.laya.ui.tv.dialogs.TvSignatureApiDialog
import com.music.laya.ui.tv.dialogs.TvLyricsSourcesDialog
import com.music.laya.ui.tv.dialogs.TvRefreshRateDialog
import com.music.laya.ui.tv.dialogs.TvScrobbleDialog
import com.music.laya.ui.tv.dialogs.TvThemeDialog
import com.music.laya.ui.tv.dialogs.TvUpdateDialog
import com.music.laya.ui.tv.display.TvRefreshRateController
import com.music.laya.ui.tv.personalization.AppThemeOption
import com.music.laya.ui.tv.theme.TvDimensions
import com.music.laya.ui.tv.theme.TvGlass
import com.music.laya.ui.tv.theme.TvType

// Discord Rich Presence is hidden on TV for now; TvDiscordDialog is kept for when it returns.
private enum class SettingsSheet { Theme, RefreshRate, Equalizer, Reverb, Scrobbling, LyricsSources, SignatureApi, Update, About }

private const val DefaultBlurb = "Playback, sound and display preferences for Laya on this TV."

/**
 * Settings, as tvOS lays it out: the app on the left with a plain description of
 * whatever row has focus, the grouped rows on the right. Values are written out
 * ("On", "6 s") and change in place on select; rows that need a choice open one.
 */
@Composable
fun TvSettingsScreen(
    viewModel: MainViewModel,
    onOpenAccount: () -> Unit,
    onRunSetupAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvChromePinned()
    val signedIn by viewModel.signedIn.collectAsState()
    val account by viewModel.account.collectAsState()

    val automix by AppSettings.smartFadeEnabled.collectAsState()
    val crossfade by AppSettings.crossfadeSeconds.collectAsState()
    val autoplay by AppSettings.autoplay.collectAsState()
    val skipSilence by AppSettings.skipSilence.collectAsState()
    val eqEnabled by AppSettings.equalizerEnabled.collectAsState()
    val reverbEnabled by AppSettings.reverbEnabled.collectAsState()
    val spatial by TvSettings.spatialAudioEnabled.collectAsState()
    val loudness by AppSettings.loudnessNormalization.collectAsState()
    val usbDac by AppSettings.preferUsbDac.collectAsState()
    val syncedLyrics by AppSettings.syncedLyrics.collectAsState()
    val lyricsSources by AppSettings.lyricsSources.collectAsState()
    val canvas by AppSettings.animatedCanvas.collectAsState()
    val lyricsCanvas by TvSettings.tvLyricsCanvasEnabled.collectAsState()
    val nerdStats by AppSettings.showNerdStats.collectAsState()
    val signatureEnabled by AppSettings.signatureApiEnabled.collectAsState()
    val signatureUrl by AppSettings.signatureApiUrl.collectAsState()
    val themeId by TvSettings.tvTheme.collectAsState()
    val uiScale by TvSettings.tvUiScale.collectAsState()
    val refreshRate by TvRefreshRateController.preference.collectAsState()
    val reduceMotion by AppSettings.reduceAnimation.collectAsState()
    val systemFont by AppSettings.systemFont.collectAsState()
    val reduceTransparency by AppSettings.reduceDynamicBlur.collectAsState()

    var blurb by remember { mutableStateOf(DefaultBlurb) }
    var sheet by remember { mutableStateOf<SettingsSheet?>(null) }
    val update by AppUpdateChecker.available.collectAsState()

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(top = TvDimensions.ContentTop),
    ) {
        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            TvAppMark(size = 120.dp)
            Spacer(modifier = Modifier.height(22.dp))
            AnimatedContent(
                targetState = blurb,
                transitionSpec = { fadeIn().togetherWith(fadeOut()) },
                label = "settingsBlurb",
            ) { text ->
                Text(
                    text = text,
                    style = TvType.Callout,
                    color = TvGlass.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 272.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight(),
            contentPadding = PaddingValues(end = TvDimensions.SafeMarginHorizontal, bottom = 38.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            item { TvListHeader("Account") }
            item {
                TvListRow(
                    title = "YouTube Music",
                    value = if (signedIn) account?.name ?: "Signed In" else "Sign In",
                    onFocused = { blurb = "Your library, playlists and recommendations come from this account." },
                    onClick = onOpenAccount,
                )
            }

            item { TvListHeader("Playback") }
            item {
                TvListRow(
                    title = "Automix",
                    value = onOff(automix),
                    onFocused = { blurb = "Blends one song into the next, matched to their tempo." },
                    onClick = { AppSettings.setSmartFadeEnabled(!automix) },
                )
            }
            item {
                TvListRow(
                    title = "Crossfade",
                    value = if (crossfade > 0) "$crossfade s" else "Off",
                    onFocused = { blurb = "Fades each song out as the next one fades in. Select to change the length." },
                    onClick = {
                        AppSettings.setCrossfadeSeconds(
                            when (crossfade) {
                                0 -> 3
                                3 -> 6
                                6 -> 10
                                else -> 0
                            },
                        )
                    },
                )
            }
            item {
                TvListRow(
                    title = "Autoplay",
                    value = onOff(autoplay),
                    onFocused = { blurb = "When the queue ends, keeps playing music similar to what you were listening to." },
                    onClick = { AppSettings.setAutoplay(!autoplay) },
                )
            }
            item {
                TvListRow(
                    title = "Skip Silence",
                    value = onOff(skipSilence),
                    onFocused = { blurb = "Skips over silent stretches at the start and end of songs." },
                    onClick = { AppSettings.setSkipSilence(!skipSilence) },
                )
            }

            item { TvListHeader("Sound") }
            item {
                TvListRow(
                    title = "Equalizer",
                    value = onOff(eqEnabled),
                    onFocused = { blurb = "Shape the sound with presets or seven bands of your own." },
                    onClick = { sheet = SettingsSheet.Equalizer },
                )
            }
            item {
                TvListRow(
                    title = "Reverb",
                    value = onOff(reverbEnabled),
                    onFocused = { blurb = "Places the music in a room, hall, plate or cathedral, with its decay, distance and tone yours to set." },
                    onClick = { sheet = SettingsSheet.Reverb },
                )
            }
            item {
                TvListRow(
                    title = "Spatial Audio",
                    value = onOff(spatial),
                    onFocused = { blurb = "Widens stereo music using the TV's audio virtualizer, where it has one." },
                    onClick = {
                        TvSettings.setSpatialAudioEnabled(!spatial)
                        TvSpatialAudioEngine.setEnabled(!spatial)
                    },
                )
            }
            item {
                TvListRow(
                    title = "Sound Check",
                    value = onOff(loudness),
                    onFocused = { blurb = "Plays every song at the same perceived loudness." },
                    onClick = { AppSettings.setLoudnessNormalization(!loudness) },
                )
            }
            item {
                TvListRow(
                    title = "USB DAC",
                    value = onOff(usbDac),
                    onFocused = { blurb = "Sends audio straight to a connected USB DAC, unchanged, when one is attached." },
                    onClick = { AppSettings.setPreferUsbDac(!usbDac) },
                )
            }

            item { TvListHeader("Now Playing") }
            item {
                TvListRow(
                    title = "Synced Lyrics",
                    value = onOff(syncedLyrics),
                    onFocused = { blurb = "Lyrics that follow along with the song, line by line." },
                    onClick = { AppSettings.setSyncedLyrics(!syncedLyrics) },
                )
            }
            item {
                TvListRow(
                    title = "Lyrics Sources",
                    value = "${lyricsSources.size} On",
                    onFocused = { blurb = "Choose which lyric databases to ask, and in what order." },
                    onClick = { sheet = SettingsSheet.LyricsSources },
                )
            }
            item {
                TvListRow(
                    title = "Motion Artwork",
                    value = onOff(canvas),
                    onFocused = { blurb = "Plays a song's looping video artwork behind the player, when it has one." },
                    onClick = { AppSettings.setAnimatedCanvas(!canvas) },
                )
            }
            item {
                TvListRow(
                    title = "Motion Artwork in Lyrics",
                    value = onOff(lyricsCanvas),
                    onFocused = { blurb = "Keeps the video artwork playing behind full-screen lyrics." },
                    onClick = { TvSettings.setTvLyricsCanvasEnabled(!lyricsCanvas) },
                )
            }
            item {
                TvListRow(
                    title = "Artist Signatures",
                    value = onOff(signatureEnabled),
                    onFocused = { blurb = "Shows a handwritten signature on an artist's page when Apple Music has no title logo." },
                    onClick = { AppSettings.setSignatureApiEnabled(!signatureEnabled) },
                )
            }
            if (signatureEnabled) {
                item {
                    TvListRow(
                        title = "Signature API",
                        value = signatureUrl.removePrefix("https://").removePrefix("http://").ifBlank { "Not Set" },
                        onFocused = { blurb = "The address of the artist-signature API to ask. Select to change it." },
                        onClick = { sheet = SettingsSheet.SignatureApi },
                    )
                }
            }
            item {
                TvListRow(
                    title = "Stats for Nerds",
                    value = onOff(nerdStats),
                    onFocused = { blurb = "Shows the codec, bitrate and sample rate of what's playing." },
                    onClick = { AppSettings.setShowNerdStats(!nerdStats) },
                )
            }

            item { TvListHeader("Display") }
            item {
                TvListRow(
                    title = "Appearance",
                    value = AppThemeOption.fromId(themeId).title,
                    onFocused = { blurb = "The background behind Laya's pages." },
                    onClick = { sheet = SettingsSheet.Theme },
                )
            }
            item {
                TvListRow(
                    title = "Interface Size",
                    value = "${(uiScale * 100).toInt()}%",
                    onFocused = { blurb = "Makes everything larger or smaller. Select to step through sizes." },
                    onClick = {
                        val steps = listOf(0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f)
                        val next = steps.firstOrNull { it > uiScale + 0.01f } ?: steps.first()
                        TvSettings.setTvUiScale(next)
                    },
                )
            }
            item {
                TvListRow(
                    title = "Refresh Rate",
                    value = refreshRate.label.substringBefore(" ("),
                    onFocused = { blurb = "How often the screen redraws while you browse." },
                    onClick = { sheet = SettingsSheet.RefreshRate },
                )
            }
            item {
                TvListRow(
                    title = "System Font",
                    value = onOff(systemFont),
                    onFocused = { blurb = "Shows text in your TV's font instead of SF Pro." },
                    onClick = { AppSettings.setSystemFont(!systemFont) },
                )
            }
            item {
                TvListRow(
                    title = "Reduce Motion",
                    value = onOff(reduceMotion),
                    onFocused = { blurb = "Calms the moving background and other decorative animation." },
                    onClick = { AppSettings.setReduceAnimation(!reduceMotion) },
                )
            }
            item {
                TvListRow(
                    title = "Reduce Transparency",
                    value = onOff(reduceTransparency),
                    onFocused = { blurb = "Replaces blurred backgrounds with solid ones. Helps on slower TVs." },
                    onClick = { AppSettings.setReduceDynamicBlur(!reduceTransparency) },
                )
            }

            item { TvListHeader("Connections") }
            item {
                TvListRow(
                    title = "Scrobbling",
                    onFocused = { blurb = "Last.fm and ListenBrainz." },
                    onClick = { sheet = SettingsSheet.Scrobbling },
                )
            }

            item { TvListHeader("General") }
            if (update != null) {
                item {
                    TvListRow(
                        title = "Software Update",
                        value = "${update?.version.orEmpty()} Available",
                        onFocused = { blurb = "Download and install the new version of Laya for TV." },
                        onClick = { sheet = SettingsSheet.Update },
                    )
                }
            }
            item {
                TvListRow(
                    title = "Set Up Again",
                    onFocused = { blurb = "Go through the first-time setup again." },
                    onClick = onRunSetupAgain,
                )
            }
            item {
                TvListRow(
                    title = "About",
                    value = BuildConfig.VERSION_NAME,
                    onFocused = { blurb = DefaultBlurb },
                    onClick = { sheet = SettingsSheet.About },
                )
            }
        }
    }

    when (sheet) {
        SettingsSheet.Theme -> TvThemeDialog(onDismiss = { sheet = null })
        SettingsSheet.RefreshRate -> TvRefreshRateDialog(onDismiss = { sheet = null })
        SettingsSheet.Equalizer -> TvEqualizerDialog(onDismiss = { sheet = null })
        SettingsSheet.Reverb -> TvReverbDialog(onDismiss = { sheet = null })
        SettingsSheet.Scrobbling -> TvScrobbleDialog(onDismiss = { sheet = null })
        SettingsSheet.LyricsSources -> TvLyricsSourcesDialog(onDismiss = { sheet = null })
        SettingsSheet.SignatureApi -> TvSignatureApiDialog(onDismiss = { sheet = null })
        SettingsSheet.Update -> update?.let { TvUpdateDialog(update = it, onDismiss = { sheet = null }) }
        SettingsSheet.About -> TvAboutDialog(onDismiss = { sheet = null })
        null -> Unit
    }
}

private fun onOff(value: Boolean) = if (value) "On" else "Off"
