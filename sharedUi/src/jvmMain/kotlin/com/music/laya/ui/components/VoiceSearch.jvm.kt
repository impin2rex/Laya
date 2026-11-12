package com.music.laya.ui.components

import androidx.compose.runtime.Composable

/** The desktop wires its own field to the OS's voice typing; see DesktopSearchField. */
@Composable
actual fun VoiceSearchButton(onResult: (String) -> Unit, onPartial: (String) -> Unit) = Unit
