package com.music.laya.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import kotlin.concurrent.Volatile

/** What the Listen Together screen needs from the platform that Compose does not have. */
interface PartyPlatformHost {
    /** Opens the system share sheet with [text]; remembered so it can capture the current context. */
    @Composable
    fun rememberShare(): (String) -> Unit

    fun copyText(text: String)

    /** A wall-clock time as `HH:mm:ss` in the device's time zone. */
    fun formatTime(epochMs: Long): String

    /** [content] as a QR code, [size] square. */
    @Composable
    fun QrCode(content: String, size: Dp)
}

object PartyPlatform {
    @Volatile
    var host: PartyPlatformHost = object : PartyPlatformHost {
        @Composable
        override fun rememberShare(): (String) -> Unit = {}
        override fun copyText(text: String) = Unit
        override fun formatTime(epochMs: Long): String = ""

        @Composable
        override fun QrCode(content: String, size: Dp) = Unit
    }
}
