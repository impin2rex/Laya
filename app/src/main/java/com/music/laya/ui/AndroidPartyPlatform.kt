package com.music.laya.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.music.laya.ui.components.QrCode as QrCodeSquare
import com.music.laya.ui.screens.PartyPlatformHost

internal class AndroidPartyPlatform(private val app: Context) : PartyPlatformHost {
    @Composable
    override fun rememberShare(): (String) -> Unit {
        val context = LocalContext.current
        return { text ->
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    },
                    null,
                ),
            )
        }
    }

    override fun copyText(text: String) {
        app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, text))
    }

    override fun formatTime(epochMs: Long): String = DateFormat.format("HH:mm:ss", epochMs).toString()

    @Composable
    override fun QrCode(content: String, size: Dp) = QrCodeSquare(content = content, size = size)
}
