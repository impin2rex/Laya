package com.music.laya.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The toggles read far from the Settings sheet that sets them.
 *
 * Mirrors Android's `AppSettings` for these few: a composable deep in the player or the lyrics
 * pane can collect one without it being threaded down through every caller.
 */
internal object DesktopAppearanceSettings {

    private fun flag(key: String, default: Boolean) =
        MutableStateFlow(DesktopPersistence().boolean(key, default))

    private val _reduceAnimation = flag(KEY_REDUCE_ANIMATION, false)
    private val _reduceDynamicBlur = flag(KEY_REDUCE_DYNAMIC_BLUR, false)
    private val _hideVolumeBar = flag(KEY_HIDE_VOLUME_BAR, false)
    private val _ambientBackdrop = flag(KEY_AMBIENT_BACKDROP, false)
    private val _systemFont = flag(KEY_SYSTEM_FONT, false)
    private val _miniPlayerVideo = flag(KEY_MINI_PLAYER_VIDEO, false)
    private val _preferMusicOnly = flag(KEY_PREFER_MUSIC_ONLY, true)

    /** Freezes the main player's gradient instead of drifting. */
    val reduceAnimation: StateFlow<Boolean> = _reduceAnimation

    /** Swaps frosted glass for solid fills across the app. */
    val reduceDynamicBlur: StateFlow<Boolean> = _reduceDynamicBlur

    /** Removes the volume slider from the main player. */
    val hideVolumeBar: StateFlow<Boolean> = _hideVolumeBar

    /** Diffuses current artwork colors under the window glass chrome like Apple Music. */
    val ambientBackdrop: StateFlow<Boolean> = _ambientBackdrop

    /** Sets the UI in the OS's default font instead of SF Pro. */
    val systemFont: StateFlow<Boolean> = _systemFont

    /** Plays a music video's picture in the mini player's cover slot. */
    val miniPlayerVideo: StateFlow<Boolean> = _miniPlayerVideo

    /**
     * A music video plays as a song — its audio release when there is one, its own sound with no
     * picture otherwise — until the listener switches to the video. Android's key, and its default.
     */
    val preferMusicOnly: StateFlow<Boolean> = _preferMusicOnly

    fun setReduceAnimation(value: Boolean) = write(KEY_REDUCE_ANIMATION, value, _reduceAnimation)

    fun setReduceDynamicBlur(value: Boolean) = write(KEY_REDUCE_DYNAMIC_BLUR, value, _reduceDynamicBlur)

    fun setHideVolumeBar(value: Boolean) = write(KEY_HIDE_VOLUME_BAR, value, _hideVolumeBar)

    fun setAmbientBackdrop(value: Boolean) = write(KEY_AMBIENT_BACKDROP, value, _ambientBackdrop)

    fun setSystemFont(value: Boolean) = write(KEY_SYSTEM_FONT, value, _systemFont)

    fun setMiniPlayerVideo(value: Boolean) = write(KEY_MINI_PLAYER_VIDEO, value, _miniPlayerVideo)

    fun setPreferMusicOnly(value: Boolean) = write(KEY_PREFER_MUSIC_ONLY, value, _preferMusicOnly)

    private fun write(key: String, value: Boolean, into: MutableStateFlow<Boolean>) {
        DesktopPersistence().saveBoolean(key, value)
        into.value = value
    }

    internal const val KEY_REDUCE_ANIMATION = "reduce_animation"
    internal const val KEY_REDUCE_DYNAMIC_BLUR = "reduce_dynamic_blur"
    internal const val KEY_HIDE_VOLUME_BAR = "hide_volume_bar"
    internal const val KEY_AMBIENT_BACKDROP = "ambient_backdrop"
    internal const val KEY_SYSTEM_FONT = "system_font"
    internal const val KEY_MINI_PLAYER_VIDEO = "mini_player_video"
    internal const val KEY_PREFER_MUSIC_ONLY = "prefer_music_only"
}
