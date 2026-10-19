package com.music.laya.ui.tv.dialogs

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.music.laya.data.canvas.ArtistSignatureRepository
import com.music.laya.data.settings.AppSettings
import com.music.laya.ui.tv.components.TvDialog
import com.music.laya.ui.tv.components.TvDialogButton
import com.music.laya.ui.tv.components.TvTextField
import com.music.laya.ui.tv.components.tvInitialFocus
import com.music.laya.ui.tv.theme.TvGlass
import com.music.laya.ui.tv.theme.TvType
import kotlinx.coroutines.launch

/**
 * The artist-signature API's address, as the phone's editor takes it.
 *
 * Saves only once `GET /v1/health` answers 2xx; removing forgets the saved address (the one this
 * build shipped with, if any, then applies again).
 */
@Composable
fun TvSignatureApiDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val stored by AppSettings.signatureApiUrl.collectAsState()
    // A draft of the stored address, seeded once.
    var input by remember { mutableStateOf(stored) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun test(onGood: () -> Unit) {
        val normalized = AppSettings.normalizeSignatureApiUrl(input)
        if (!ArtistSignatureRepository.isValidBaseUrl(normalized)) {
            status = "Could not reach the API at that URL" to false
            return
        }
        busy = true
        status = null
        scope.launch {
            val ok = ArtistSignatureRepository.checkHealth(normalized)
            busy = false
            status = (if (ok) "Connected" else "Could not reach the API at that URL") to ok
            if (ok) onGood()
        }
    }

    TvDialog(
        title = "Signature API",
        message = "The address of the artist-signature API to ask for signatures.",
        onDismissRequest = onDismiss,
    ) {
        TvTextField(
            value = input,
            onValueChange = {
                input = it
                status = null
            },
            placeholder = "https://signatures.example.com",
            imeAction = ImeAction.Done,
            capitalization = KeyboardCapitalization.None,
            modifier = Modifier.tvInitialFocus(),
        )
        status?.let { (text, good) ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = text,
                style = TvType.Caption,
                color = if (good) TvGlass.TextPrimary else TvGlass.TextTertiary,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        TvDialogButton(
            text = "Save",
            enabled = input.isNotBlank() && !busy,
            onClick = {
                test {
                    AppSettings.setSignatureApiUrl(input)
                    ArtistSignatureRepository.clear()
                    onDismiss()
                }
            },
        )
        Spacer(modifier = Modifier.height(6.dp))
        TvDialogButton(text = "Test", enabled = input.isNotBlank() && !busy, onClick = { test {} })
        // The build's own default is not a saved address, so there is nothing to remove.
        if (stored.isNotBlank() && stored != AppSettings.defaultSignatureApiUrl) {
            Spacer(modifier = Modifier.height(6.dp))
            TvDialogButton(
                text = "Remove",
                destructive = true,
                onClick = {
                    AppSettings.setSignatureApiUrl("")
                    ArtistSignatureRepository.clear()
                    onDismiss()
                },
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        TvDialogButton(text = "Cancel", onClick = onDismiss)
    }
}
