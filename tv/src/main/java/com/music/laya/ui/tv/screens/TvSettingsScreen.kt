package com.music.laya.ui.tv.screens

import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.stringResource
import com.music.laya.R
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
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import com.music.laya.data.canvas.ArtistSignatureRepository
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.settings.HomeDialSource
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
private enum class TvSettingsPage(val title: Int) {
    ACCOUNT(R.string.tv_account),
    PLAYBACK(R.string.playback),
    SOUND(R.string.tv_sound),
    NOW_PLAYING(R.string.tv_now_playing),
    DISPLAY(R.string.tv_display),
    HOME(R.string.tv_settings_home),
    CONNECTIONS(R.string.tv_connections),
    GENERAL(R.string.tv_general),
}

/** The phone's sections, over TV's own groups. */
private val TV_SETTINGS_SECTIONS = listOf(
    R.string.tv_settings_section_look to listOf(TvSettingsPage.DISPLAY, TvSettingsPage.HOME),
    R.string.tv_settings_section_listening to listOf(TvSettingsPage.PLAYBACK, TvSettingsPage.SOUND, TvSettingsPage.NOW_PLAYING),
    R.string.tv_settings_section_app to listOf(TvSettingsPage.ACCOUNT, TvSettingsPage.CONNECTIONS, TvSettingsPage.GENERAL),
)

private enum class SettingsSheet { Theme, RefreshRate, Equalizer, Reverb, Scrobbling, LyricsSources, Sources, SignatureApi, Update, About }


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
    val sourceConfigs by com.music.laya.data.sources.SourceRegistry.configs.collectAsState()
    val preferMusicOnly by AppSettings.preferMusicOnly.collectAsState()
    val hideAiSongs by AppSettings.hideAiSongs.collectAsState()
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
    val bestPart by AppSettings.showBestPart.collectAsState()
    val signatureEnabled by AppSettings.signatureApiEnabled.collectAsState()
    // Only the saved address is ever shown; the built-in one stays out of the UI.
    val signatureUrl by AppSettings.customSignatureApiUrl.collectAsState()
    val signatureServerOnline by ArtistSignatureRepository.server.online.collectAsState()
    LaunchedEffect(signatureEnabled, signatureUrl) { ArtistSignatureRepository.refreshServer() }
    val themeId by TvSettings.tvTheme.collectAsState()
    val uiScale by TvSettings.tvUiScale.collectAsState()
    val refreshRate by TvRefreshRateController.preference.collectAsState()
    val reduceMotion by AppSettings.reduceAnimation.collectAsState()
    val systemFont by AppSettings.systemFont.collectAsState()
    val colorBackground by AppSettings.colorBackground.collectAsState()
    val splashScreen by AppSettings.splashScreen.collectAsState()
    val reduceTransparency by AppSettings.reduceDynamicBlur.collectAsState()
    val homeSpeedDial by AppSettings.homeSpeedDial.collectAsState()
    val homeArtistDial by AppSettings.homeArtistDial.collectAsState()
    val homeMixesDial by AppSettings.homeMixesDial.collectAsState()
    val homeLuckyTile by AppSettings.homeLuckyTile.collectAsState()
    val homeDialSource by AppSettings.homeDialSource.collectAsState()
    val homeDialRows by AppSettings.homeDialRows.collectAsState()
    val homeDialLabels by AppSettings.homeDialLabels.collectAsState()
    val homeDialPinIcon by AppSettings.homeDialPinIcon.collectAsState()
    val homeDialNewReleaseTags by AppSettings.homeDialNewReleaseTags.collectAsState()
    val hiddenFromDial by AppSettings.hiddenFromDial.collectAsState()

    val context = LocalContext.current
    val defaultBlurb = stringResource(R.string.tv_blurb_default)
    var blurb by remember { mutableStateOf(defaultBlurb) }
    var sheet by remember { mutableStateOf<SettingsSheet?>(null) }
    // The front page lists the categories; each opens its own rows, and back returns to the list.
    var page by rememberSaveable { mutableStateOf<TvSettingsPage?>(null) }
    val listState = rememberLazyListState()
    val listFocus = remember { FocusRequester() }
    var pageOpened by remember { mutableStateOf(false) }
    LaunchedEffect(page) {
        if (!pageOpened) { pageOpened = true; return@LaunchedEffect }
        listState.scrollToItem(0)
        withFrameNanos { }
        runCatching { listFocus.requestFocus() }
    }
    BackHandler(enabled = page != null && sheet == null) { page = null }
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
            state = listState,
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight()
                .focusRequester(listFocus)
                .focusGroup(),
            contentPadding = PaddingValues(end = TvDimensions.SafeMarginHorizontal, bottom = 38.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (page == null) {
                for ((section, pages) in TV_SETTINGS_SECTIONS) {
                    item { TvListHeader(stringResource(section)) }
                    for (entry in pages) {
                        item(key = entry.name) {
                            TvListRow(
                                title = stringResource(entry.title),
                                onFocused = { blurb = defaultBlurb },
                                onClick = { page = entry },
                                trailingIcon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            )
                        }
                    }
                }
            }
            if (page == TvSettingsPage.ACCOUNT) {
            item { TvListHeader(stringResource(R.string.tv_account)) }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_youtube_music),
                    value = if (signedIn) account?.name ?: stringResource(R.string.tv_signed_in) else stringResource(R.string.tv_sign_in),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_account) },
                    onClick = onOpenAccount,
                )
            }

            }
            if (page == TvSettingsPage.PLAYBACK) {
            item { TvListHeader(stringResource(R.string.playback)) }
            item {
                TvListRow(
                    title = stringResource(R.string.source),
                    value = stringResource(R.string.tv_sources_on, sourceConfigs.count { it.enabled }),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_sources) },
                    onClick = { sheet = SettingsSheet.Sources },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_automix),
                    value = onOff(automix),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_automix) },
                    onClick = { AppSettings.setSmartFadeEnabled(!automix) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.crossfade),
                    value = if (crossfade > 0) stringResource(R.string.tv_seconds_short, crossfade) else stringResource(R.string.off),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_crossfade) },
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
                    title = stringResource(R.string.tv_autoplay),
                    value = onOff(autoplay),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_autoplay) },
                    onClick = { AppSettings.setAutoplay(!autoplay) },
                )
            }
            item {
                val subtitle = androidx.compose.ui.res.stringResource(com.music.laya.R.string.prefer_music_only_subtitle)
                TvListRow(
                    title = androidx.compose.ui.res.stringResource(com.music.laya.R.string.prefer_music_only),
                    value = onOff(preferMusicOnly),
                    onFocused = { blurb = subtitle },
                    onClick = { AppSettings.setPreferMusicOnly(!preferMusicOnly) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_hide_ai_songs),
                    value = onOff(hideAiSongs),
                    onFocused = { blurb = context.getString(R.string.tv_hide_ai_songs_subtitle) },
                    onClick = { AppSettings.setHideAiSongs(!hideAiSongs) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_skip_silence),
                    value = onOff(skipSilence),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_skip_silence) },
                    onClick = { AppSettings.setSkipSilence(!skipSilence) },
                )
            }

            }
            if (page == TvSettingsPage.SOUND) {
            item { TvListHeader(stringResource(R.string.tv_sound)) }
            item {
                TvListRow(
                    title = stringResource(R.string.equalizer),
                    value = onOff(eqEnabled),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_equalizer) },
                    onClick = { sheet = SettingsSheet.Equalizer },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_reverb),
                    value = onOff(reverbEnabled),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_reverb) },
                    onClick = { sheet = SettingsSheet.Reverb },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_spatial_audio),
                    value = onOff(spatial),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_spatial) },
                    onClick = {
                        TvSettings.setSpatialAudioEnabled(!spatial)
                        TvSpatialAudioEngine.setEnabled(!spatial)
                    },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_sound_check),
                    value = onOff(loudness),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_sound_check) },
                    onClick = { AppSettings.setLoudnessNormalization(!loudness) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_usb_dac),
                    value = onOff(usbDac),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_usb_dac) },
                    onClick = { AppSettings.setPreferUsbDac(!usbDac) },
                )
            }

            }
            if (page == TvSettingsPage.NOW_PLAYING) {
            item { TvListHeader(stringResource(R.string.tv_now_playing)) }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_synced_lyrics),
                    value = onOff(syncedLyrics),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_synced_lyrics) },
                    onClick = { AppSettings.setSyncedLyrics(!syncedLyrics) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_lyrics_sources),
                    value = stringResource(R.string.tv_sources_on, lyricsSources.size),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_lyrics_sources) },
                    onClick = { sheet = SettingsSheet.LyricsSources },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_motion_artwork),
                    value = onOff(canvas),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_motion_artwork) },
                    onClick = { AppSettings.setAnimatedCanvas(!canvas) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_motion_artwork_lyrics),
                    value = onOff(lyricsCanvas),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_motion_artwork_lyrics) },
                    onClick = { TvSettings.setTvLyricsCanvasEnabled(!lyricsCanvas) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_artist_signatures),
                    value = onOff(signatureEnabled),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_artist_signatures) },
                    onClick = { AppSettings.setSignatureApiEnabled(!signatureEnabled) },
                )
            }
            if (signatureEnabled) {
                item {
                    TvListRow(
                        title = stringResource(R.string.tv_signature_api),
                        value = signatureUrl.removePrefix("https://").removePrefix("http://").ifBlank {
                            if (AppSettings.defaultSignatureApiUrl.isNotBlank()) stringResource(R.string.tv_built_in) else stringResource(R.string.tv_not_set)
                        },
                        subtitle = signatureServerOnline?.let { if (it) stringResource(R.string.artist_signatures_online) else stringResource(R.string.listen_together_server_offline) },
                        subtitleColor = signatureServerOnline?.let { if (it) Color(0xFF34C759) else Color(0xFFFF453A) },
                        onFocused = { blurb = context.getString(R.string.tv_blurb_signature_api) },
                        onClick = { sheet = SettingsSheet.SignatureApi },
                    )
                }
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_best_part_button),
                    value = onOff(bestPart),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_best_part) },
                    onClick = { AppSettings.setShowBestPart(!bestPart) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_stats_for_nerds),
                    value = onOff(nerdStats),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_stats) },
                    onClick = { AppSettings.setShowNerdStats(!nerdStats) },
                )
            }

            }
            if (page == TvSettingsPage.DISPLAY) {
            item { TvListHeader(stringResource(R.string.tv_display)) }
            item {
                TvListRow(
                    title = stringResource(R.string.appearance),
                    value = stringResource(AppThemeOption.fromId(themeId).title),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_appearance) },
                    onClick = { sheet = SettingsSheet.Theme },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_interface_size),
                    value = "${(uiScale * 100).toInt()}%",
                    onFocused = { blurb = context.getString(R.string.tv_blurb_interface_size) },
                    onClick = {
                        val steps = listOf(0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f)
                        val next = steps.firstOrNull { it > uiScale + 0.01f } ?: steps.first()
                        TvSettings.setTvUiScale(next)
                    },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_refresh_rate),
                    value = stringResource(refreshRate.label).substringBefore(" ("),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_refresh_rate) },
                    onClick = { sheet = SettingsSheet.RefreshRate },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_system_font),
                    value = onOff(systemFont),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_system_font) },
                    onClick = { AppSettings.setSystemFont(!systemFont) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_reduce_motion),
                    value = onOff(reduceMotion),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_reduce_motion) },
                    onClick = { AppSettings.setReduceAnimation(!reduceMotion) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_color_background),
                    value = onOff(colorBackground),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_color_background) },
                    onClick = { AppSettings.setColorBackground(!colorBackground) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_splash_screen),
                    value = onOff(splashScreen),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_splash_screen) },
                    onClick = { AppSettings.setSplashScreen(!splashScreen) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_reduce_transparency),
                    value = onOff(reduceTransparency),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_reduce_transparency) },
                    onClick = { AppSettings.setReduceDynamicBlur(!reduceTransparency) },
                )
            }

            }
            if (page == TvSettingsPage.HOME) {
            item { TvListHeader(stringResource(R.string.tv_settings_home)) }
            // Everything past the first row shapes the Speed Dial, so it goes with it while the dial is off.
            val homeBlurb = { blurb = context.getString(R.string.tv_settings_home_speed_dial_subtitle) }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_settings_home_speed_dial),
                    value = onOff(homeSpeedDial),
                    onFocused = homeBlurb,
                    onClick = { AppSettings.setHomeSpeedDial(!homeSpeedDial) },
                )
            }
            if (homeSpeedDial) {
                val toggles = listOf(
                    Triple(R.string.tv_settings_home_artist_dial, homeArtistDial, AppSettings::setHomeArtistDial),
                    Triple(R.string.tv_settings_home_mixes_dial, homeMixesDial, AppSettings::setHomeMixesDial),
                    Triple(R.string.tv_settings_home_lucky_tile, homeLuckyTile, AppSettings::setHomeLuckyTile),
                )
                for ((title, on, set) in toggles) item(key = "home_$title") {
                    TvListRow(title = stringResource(title), value = onOff(on), onFocused = homeBlurb, onClick = { set(!on) })
                }
                item {
                    TvListRow(
                        title = stringResource(R.string.tv_settings_home_dial_source),
                        value = stringResource(
                            if (homeDialSource == HomeDialSource.YOUTUBE) R.string.tv_settings_home_dial_source_youtube
                            else R.string.tv_settings_home_dial_source_history,
                        ),
                        onFocused = homeBlurb,
                        onClick = {
                            AppSettings.setHomeDialSource(
                                if (homeDialSource == HomeDialSource.YOUTUBE) HomeDialSource.HISTORY else HomeDialSource.YOUTUBE,
                            )
                        },
                    )
                }
                item {
                    TvListRow(
                        title = stringResource(R.string.tv_settings_home_dial_rows),
                        value = stringResource(R.string.tv_settings_home_dial_grid, homeDialRows),
                        onFocused = homeBlurb,
                        onClick = {
                            val rows = AppSettings.HOME_DIAL_ROWS
                            AppSettings.setHomeDialRows(if (homeDialRows >= rows.last) rows.first else homeDialRows + 1)
                        },
                    )
                }
                val shows = listOf(
                    Triple(R.string.tv_settings_home_dial_labels, homeDialLabels, AppSettings::setHomeDialLabels),
                    Triple(R.string.tv_settings_home_dial_pin_icon, homeDialPinIcon, AppSettings::setHomeDialPinIcon),
                    Triple(R.string.tv_settings_home_dial_new_tags, homeDialNewReleaseTags, AppSettings::setHomeDialNewReleaseTags),
                )
                for ((title, on, set) in shows) item(key = "home_$title") {
                    TvListRow(title = stringResource(title), value = onOff(on), onFocused = homeBlurb, onClick = { set(!on) })
                }
                if (hiddenFromDial.isNotEmpty()) {
                    item {
                        TvListRow(
                            title = stringResource(R.string.tv_settings_home_restore_tiles, hiddenFromDial.size),
                            onFocused = homeBlurb,
                            onClick = { hiddenFromDial.forEach { AppSettings.setHiddenFromDial(it, false) } },
                        )
                    }
                }
            }

            }
            if (page == TvSettingsPage.CONNECTIONS) {
            item { TvListHeader(stringResource(R.string.tv_connections)) }
            item {
                TvListRow(
                    title = stringResource(R.string.scrobbling),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_scrobbling) },
                    onClick = { sheet = SettingsSheet.Scrobbling },
                )
            }

            }
            if (page == TvSettingsPage.GENERAL) {
            item { TvListHeader(stringResource(R.string.tv_general)) }
            if (update != null) {
                item {
                    TvListRow(
                        title = stringResource(R.string.software_update),
                        value = stringResource(R.string.tv_update_available_value, update?.version.orEmpty()),
                        onFocused = { blurb = context.getString(R.string.tv_blurb_update) },
                        onClick = { sheet = SettingsSheet.Update },
                    )
                }
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_set_up_again),
                    onFocused = { blurb = context.getString(R.string.tv_blurb_set_up_again) },
                    onClick = onRunSetupAgain,
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_about),
                    value = BuildConfig.VERSION_NAME,
                    onFocused = { blurb = defaultBlurb },
                    onClick = { sheet = SettingsSheet.About },
                )
            }
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
        SettingsSheet.Sources -> com.music.laya.ui.tv.dialogs.TvSourcesDialog(onDismiss = { sheet = null })
        SettingsSheet.SignatureApi -> TvSignatureApiDialog(onDismiss = { sheet = null })
        SettingsSheet.Update -> update?.let { TvUpdateDialog(update = it, onDismiss = { sheet = null }) }
        SettingsSheet.About -> TvAboutDialog(onDismiss = { sheet = null })
        null -> Unit
    }
}

@Composable
private fun onOff(value: Boolean) = if (value) stringResource(R.string.tv_on) else stringResource(R.string.off)
