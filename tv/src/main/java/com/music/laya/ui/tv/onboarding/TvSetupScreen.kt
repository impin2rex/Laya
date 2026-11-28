package com.music.laya.ui.tv.onboarding

import androidx.compose.ui.res.stringResource
import com.music.laya.R
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.settings.TvSettings
import com.music.laya.ui.MainViewModel
import com.music.laya.ui.tv.TvAppMark
import com.music.laya.ui.tv.audio.TvSpatialAudioEngine
import com.music.laya.ui.tv.components.TvButton
import com.music.laya.ui.tv.components.TvListRow
import com.music.laya.ui.tv.components.tvClick
import com.music.laya.ui.tv.components.tvInitialFocus
import com.music.laya.ui.tv.components.tvLift
import com.music.laya.ui.tv.dialogs.TvAccountDialog
import com.music.laya.ui.tv.personalization.AppThemeOption
import com.music.laya.ui.tv.theme.TvDarkPalette
import com.music.laya.ui.tv.theme.TvGlass
import com.music.laya.ui.tv.theme.TvMidnightPalette
import com.music.laya.ui.tv.theme.TvOledPalette
import com.music.laya.ui.tv.theme.TvType

private enum class SetupStep { Welcome, SignIn, Appearance, Preferences, Done }

/**
 * First-run setup, in the manner of Apple TV's: one decision per screen, a
 * centred title and explanation, and the choices stacked at the bottom. Back
 * steps back; nothing is saved until the last screen.
 */
@Composable
fun TvSetupScreen(
    viewModel: MainViewModel,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableStateOf(SetupStep.Welcome) }
    var theme by remember { mutableStateOf(AppThemeOption.fromId(TvSettings.tvTheme.value)) }

    val finish = {
        TvSettings.setTvPersonalization(
            nickname = TvSettings.tvNickname.value,
            themeId = theme.id,
            version = 1,
        )
        onComplete()
    }

    BackHandler(enabled = step != SetupStep.Welcome) {
        step = SetupStep.entries[step.ordinal - 1]
    }

    AnimatedContent(
        targetState = step,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            (slideInHorizontally(tween(320)) { if (forward) it / 6 else -it / 6 } + fadeIn(tween(320)))
                .togetherWith(slideOutHorizontally(tween(240)) { if (forward) -it / 6 else it / 6 } + fadeOut(tween(200)))
        },
        label = "setupStep",
        modifier = modifier.fillMaxSize(),
    ) { current ->
        when (current) {
            SetupStep.Welcome -> TvSetupPage(
                title = stringResource(R.string.tv_welcome_title),
                message = stringResource(R.string.tv_welcome_message),
                hero = { TvAppMark(size = 106.dp) },
            ) {
                TvSetupButton(stringResource(R.string.tv_continue), initialFocus = true) { step = SetupStep.SignIn }
                TvSetupButton(stringResource(R.string.tv_set_up_later)) { finish() }
            }
            SetupStep.SignIn -> TvSignInStep(
                viewModel = viewModel,
                onContinue = { step = SetupStep.Appearance },
            )
            SetupStep.Appearance -> TvSetupPage(
                title = stringResource(R.string.tv_choose_a_look),
                message = stringResource(R.string.tv_change_later),
                hero = {
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        AppThemeOption.entries.forEach { option ->
                            TvThemeSwatch(
                                option = option,
                                selected = option == theme,
                                initialFocus = option == theme,
                                onClick = { theme = option },
                            )
                        }
                    }
                },
            ) {
                TvSetupButton(stringResource(R.string.tv_continue)) {
                    TvSettings.setTvTheme(theme.id)
                    step = SetupStep.Preferences
                }
            }
            SetupStep.Preferences -> TvPreferencesStep(onContinue = { step = SetupStep.Done })
            SetupStep.Done -> TvSetupPage(
                title = stringResource(R.string.tv_all_set_title),
                message = stringResource(R.string.tv_all_set_message),
                hero = {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(77.dp),
                    )
                },
            ) {
                TvSetupButton(stringResource(R.string.tv_start_listening), initialFocus = true) { finish() }
            }
        }
    }
}

/** The common frame of every setup screen. */
@Composable
private fun TvSetupPage(
    title: String,
    message: String,
    hero: @Composable () -> Unit,
    buttons: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 38.dp, vertical = 35.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        hero()
        Spacer(modifier = Modifier.height(29.dp))
        Text(text = title, style = TvType.LargeTitle, color = TvGlass.TextPrimary, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = TvType.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W400),
            color = TvGlass.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 448.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        Column(
            modifier = Modifier.width(304.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = buttons,
        )
    }
}

@Composable
private fun TvSetupButton(text: String, initialFocus: Boolean = false, onClick: () -> Unit) {
    TvButton(
        text = text,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .tvInitialFocus(initialFocus),
    )
}

@Composable
private fun TvSignInStep(viewModel: MainViewModel, onContinue: () -> Unit) {
    val signedIn by viewModel.signedIn.collectAsState()
    val account by viewModel.account.collectAsState()
    var signingIn by remember { mutableStateOf(false) }

    // Signing in from here moves on by itself once it lands.
    LaunchedEffect(signedIn) {
        if (signedIn && signingIn) {
            signingIn = false
            onContinue()
        }
    }

    TvSetupPage(
        title = if (signedIn) stringResource(R.string.tv_signed_in) else stringResource(R.string.tv_sign_in_ytm_title),
        message = if (signedIn) {
            stringResource(R.string.tv_laya_will_use, account?.name ?: stringResource(R.string.tv_your_account))
        } else {
            stringResource(R.string.tv_bring_library)
        },
        hero = { TvAppMark(size = 77.dp) },
    ) {
        if (signedIn) {
            TvSetupButton(stringResource(R.string.tv_continue), initialFocus = true, onClick = onContinue)
        } else {
            TvSetupButton(stringResource(R.string.tv_sign_in), initialFocus = true) { signingIn = true }
            TvSetupButton(stringResource(R.string.tv_not_now), onClick = onContinue)
        }
    }

    if (signingIn && !signedIn) {
        // Phone (QR) sign-in is held back from setup for now; Settings still offers it.
        TvAccountDialog(viewModel = viewModel, onDismiss = { signingIn = false }, allowPhoneSignIn = false)
    }
}

@Composable
private fun TvPreferencesStep(onContinue: () -> Unit) {
    val lyrics by AppSettings.syncedLyrics.collectAsState()
    val canvas by AppSettings.animatedCanvas.collectAsState()
    val automix by AppSettings.smartFadeEnabled.collectAsState()
    val spatial by TvSettings.spatialAudioEnabled.collectAsState()

    TvSetupPage(
        title = stringResource(R.string.activity_listening),
        message = stringResource(R.string.tv_select_row_hint),
        hero = {
            Column(
                modifier = Modifier.width(416.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                TvListRow(
                    title = stringResource(R.string.tv_synced_lyrics),
                    value = if (lyrics) stringResource(R.string.tv_on) else stringResource(R.string.off),
                    modifier = Modifier.tvInitialFocus(),
                    onClick = { AppSettings.setSyncedLyrics(!lyrics) },
                )
                TvListRow(
                    title = stringResource(R.string.tv_motion_artwork),
                    value = if (canvas) stringResource(R.string.tv_on) else stringResource(R.string.off),
                    onClick = { AppSettings.setAnimatedCanvas(!canvas) },
                )
                TvListRow(
                    title = stringResource(R.string.tv_automix),
                    value = if (automix) stringResource(R.string.tv_on) else stringResource(R.string.off),
                    onClick = { AppSettings.setSmartFadeEnabled(!automix) },
                )
                TvListRow(
                    title = stringResource(R.string.tv_spatial_audio),
                    value = if (spatial) stringResource(R.string.tv_on) else stringResource(R.string.off),
                    onClick = {
                        TvSettings.setSpatialAudioEnabled(!spatial)
                        TvSpatialAudioEngine.setEnabled(!spatial)
                    },
                )
            }
        },
    ) {
        TvSetupButton(stringResource(R.string.tv_continue), onClick = onContinue)
    }
}

/** A small picture of the theme's canvas with two placeholder cards, its name under it. */
@Composable
private fun TvThemeSwatch(
    option: AppThemeOption,
    selected: Boolean,
    initialFocus: Boolean,
    onClick: () -> Unit,
) {
    val palette = when (option) {
        AppThemeOption.MIDNIGHT -> TvMidnightPalette
        AppThemeOption.PURE_BLACK -> TvOledPalette
        AppThemeOption.DYNAMIC_ARTWORK -> TvDarkPalette
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(168.dp)
                .aspectRatio(16f / 10f)
                .tvInitialFocus(initialFocus)
                .tvLift(interaction, RoundedCornerShape(11.dp), focusedScale = 1.08f, elevation = 18.dp)
                .tvClick(interaction, onClick = onClick)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            if (option == AppThemeOption.DYNAMIC_ARTWORK) Color(0xFF3A2A44) else palette.canvasTop,
                            palette.canvasBottom,
                        ),
                    ),
                )
                .padding(13.dp),
        ) {
            Row(
                modifier = Modifier.align(Alignment.BottomStart),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.White.copy(alpha = 0.16f)),
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.selected),
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(19.dp)
                        .clip(CircleShape),
                )
            }
        }
        Spacer(modifier = Modifier.height(11.dp))
        Text(
            text = stringResource(option.title),
            style = TvType.CardTitle,
            color = if (focused || selected) TvGlass.TextPrimary else TvGlass.TextSecondary,
        )
    }
}
