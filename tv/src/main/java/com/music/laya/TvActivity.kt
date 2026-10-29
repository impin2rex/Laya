package com.music.laya

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import com.music.laya.data.listentogether.JamInviteLink
import com.music.laya.data.listentogether.consume
import com.music.laya.data.settings.AppSettings
import com.music.laya.playback.MusicLink
import com.music.laya.playback.PlayerDeepLink
import com.music.laya.playback.rememberMediaController
import com.music.laya.playback.rememberPlayerState
import com.music.laya.ui.MainViewModel
import com.music.laya.ui.theme.LayaTheme
import com.music.laya.ui.tv.TvApp
import com.music.laya.ui.tv.display.TvRefreshRateController
import com.music.laya.ui.tv.theme.LayaTvTheme

/**
 * Dedicated TV Activity for Android TV, Google TV, and Fire TV devices.
 * Launched via `android.intent.category.LEANBACK_LAUNCHER`.
 */
class TvActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Attach TV Refresh Rate and Display Controller
        TvRefreshRateController.attach(this)

        // Make window full edge-to-edge for 1080p/4K television screens
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        // A cold launch from the Now Playing card (or a link) arrives here, not
        // through onNewIntent.
        if (savedInstanceState == null) consumeLinks(intent)

        setContent {
            LayaTvTheme {
                // The phone's Material theme too, so the components shared with
                // it (lyrics, mood sleeves) are set in the same face on its type scale.
                val systemFont by AppSettings.systemFont.collectAsState()
                LayaTheme(darkTheme = true, systemFont = systemFont) {
                    val mediaController = rememberMediaController()
                    val playerState = rememberPlayerState(mediaController)

                    TvApp(
                        viewModel = viewModel,
                        mediaController = mediaController,
                        playerState = playerState,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLinks(intent)
    }

    private fun consumeLinks(intent: Intent) {
        PlayerDeepLink.consume(intent)
        JamInviteLink.consume(intent)
        MusicLink.consume(intent)
    }

    override fun onDestroy() {
        // Closing the app (back out of Home, or the task being swiped away)
        // pauses the music rather than leaving it playing behind the launcher.
        if (isFinishing) {
            runCatching {
                val future = androidx.media3.session.MediaController.Builder(
                    applicationContext,
                    androidx.media3.session.SessionToken(
                        applicationContext,
                        android.content.ComponentName(
                            applicationContext,
                            com.music.laya.playback.PlaybackService::class.java,
                        ),
                    ),
                ).buildAsync()
                future.addListener({
                    runCatching { future.get().run { pause(); release() } }
                }, androidx.core.content.ContextCompat.getMainExecutor(applicationContext))
            }
        }
        super.onDestroy()
        TvRefreshRateController.detach()
    }
}
