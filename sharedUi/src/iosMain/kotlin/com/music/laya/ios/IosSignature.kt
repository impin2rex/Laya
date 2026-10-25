package com.music.laya.ios

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.music.laya.data.canvas.ArtistSignature
import com.music.laya.data.canvas.ArtistSignatureRepository
import com.music.laya.data.settings.AppSettings
import com.music.laya.sharedui.resources.Res
import com.music.laya.sharedui.resources.artist_signatures_subtitle
import com.music.laya.sharedui.resources.artist_signatures_test_failed
import com.music.laya.sharedui.resources.artist_signatures_url
import com.music.laya.sharedui.resources.artist_signatures_url_hint
import com.music.laya.sharedui.resources.cancel
import com.music.laya.sharedui.resources.connected
import com.music.laya.sharedui.resources.remove
import com.music.laya.sharedui.resources.save
import com.music.laya.ui.components.PillTextField
import com.music.laya.ui.screens.DetailHost
import coil3.request.transformations
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The artist-signature lookups for the artist page; same repository Android uses. */
internal object IosDetailHost : DetailHost {
    override val signatureUpdates: StateFlow<Int> get() = ArtistSignatureRepository.updates
    override fun signatureBase(enabled: Boolean, url: String) = ArtistSignatureRepository.effectiveBaseUrl(enabled, url)
    override fun cachedSignature(name: String, base: String?): ArtistSignature? = ArtistSignatureRepository.cached(name, base)
    override suspend fun signatureFor(name: String, base: String?): ArtistSignature? = ArtistSignatureRepository.signatureFor(name, base)
    override fun coil3.request.ImageRequest.Builder.signatureInk() { transformations(IosSignatureInk) }
}

/** Port of Android's SignatureApiEditor: saves only once `GET /v1/health` answers 2xx; blank forgets the provider. */
@Composable
internal fun IosSignatureApiEditor(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val stored by AppSettings.signatureApiUrl.collectAsState()
    var input by remember { mutableStateOf(stored) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val connected = stringResource(Res.string.connected)
    val failed = stringResource(Res.string.artist_signatures_test_failed)

    fun save() {
        val normalized = AppSettings.normalizeSignatureApiUrl(input)
        if (!ArtistSignatureRepository.isValidBaseUrl(normalized)) {
            status = failed to false
            return
        }
        busy = true
        status = null
        scope.launch {
            val ok = ArtistSignatureRepository.checkHealth(normalized)
            busy = false
            status = (if (ok) connected else failed) to ok
            if (ok) {
                AppSettings.setSignatureApiUrl(normalized)
                ArtistSignatureRepository.clear()
                onDismiss()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.artist_signatures_url)) },
        text = {
            Column {
                Text(
                    status?.first ?: stringResource(Res.string.artist_signatures_subtitle),
                    color = if (status?.second == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                PillTextField(
                    value = input,
                    onValueChange = { input = it; status = null },
                    placeholder = stringResource(Res.string.artist_signatures_url_hint),
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = { TextButton(onClick = ::save, enabled = input.isNotBlank() && !busy) { Text(stringResource(Res.string.save)) } },
        dismissButton = {
            // The build's own default is not a saved address, so there is nothing to remove.
            if (stored.isNotBlank() && stored != AppSettings.defaultSignatureApiUrl) {
                TextButton(onClick = {
                    AppSettings.setSignatureApiUrl("")
                    ArtistSignatureRepository.clear()
                    onDismiss()
                }, enabled = !busy) { Text(stringResource(Res.string.remove)) }
            } else {
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
}
