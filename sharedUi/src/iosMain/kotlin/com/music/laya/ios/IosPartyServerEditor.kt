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
import com.music.laya.data.listentogether.ListenTogether
import com.music.laya.data.listentogether.ServerConnectionState
import com.music.laya.data.listentogether.ServerUrlValidationResult
import com.music.laya.sharedui.resources.*
import com.music.laya.ui.components.PillTextField
import com.music.laya.ui.screens.toMessageRes
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** The party server's address: Test probes it, Save switches to it (falling back if it is down), and it is locked while a party is live on it. */
@Composable
internal fun IosPartyServerEditor(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val customServer by ListenTogether.customServerUrl.collectAsState()
    val party by ListenTogether.state.collectAsState()
    var input by remember { mutableStateOf(customServer) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun say(res: StringResource, good: Boolean, vararg args: Any) = scope.launch {
        status = getString(res, *args) to good
    }

    fun save() {
        when (val validation = ListenTogether.parseAndNormalizeServerUrl(input)) {
            is ServerUrlValidationResult.Invalid -> say(validation.error.toMessageRes(), false)
            is ServerUrlValidationResult.Valid -> {
                busy = true
                status = null
                scope.launch {
                    val message = when (val resolved = ListenTogether.setCustomServerUrl(validation.normalizedUrl)) {
                        is ServerConnectionState.CustomOnline -> Res.string.listen_together_custom_server_connected
                        is ServerConnectionState.CustomFallback -> Res.string.listen_together_custom_server_unreachable_fallback
                        is ServerConnectionState.DefaultOnline -> Res.string.listen_together_switched_to_default
                        ServerConnectionState.Offline ->
                            if (ListenTogether.customServerUrl.value.isNotBlank()) Res.string.listen_together_status_all_offline
                            else Res.string.listen_together_server_offline
                        ServerConnectionState.Checking -> null
                    }
                    busy = false
                    message?.let { IosPlayerHost.showMessage(getString(it)) }
                    onDismiss()
                }
            }
        }
    }

    fun test() {
        when (val validation = ListenTogether.parseAndNormalizeServerUrl(input)) {
            is ServerUrlValidationResult.Invalid -> say(validation.error.toMessageRes(), false)
            is ServerUrlValidationResult.Valid -> {
                busy = true
                status = null
                scope.launch {
                    val probe = ListenTogether.probeHealthWithLatency(validation.normalizedUrl, 8_000L)
                    status = if (probe.isOnline) getString(Res.string.listen_together_server_online, probe.latencyMs) to true
                    else getString(Res.string.listen_together_server_offline) to false
                    busy = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.listen_together_custom_server)) },
        text = {
            Column {
                Text(
                    status?.first ?: stringResource(Res.string.listen_together_custom_server_footer),
                    color = if (status?.second == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                PillTextField(
                    value = input,
                    onValueChange = { input = it; status = null },
                    placeholder = "https://party.example.com",
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                TextButton(onClick = ::test, enabled = input.isNotBlank() && !busy) {
                    Text(stringResource(if (busy) Res.string.testing else Res.string.test))
                }
            }
        },
        confirmButton = { TextButton(onClick = ::save, enabled = input.isNotBlank() && !busy) { Text(stringResource(Res.string.save)) } },
        dismissButton = {
            if (customServer.isNotBlank() && !party.inParty) {
                TextButton(onClick = {
                    busy = true
                    scope.launch {
                        ListenTogether.setCustomServerUrl("")
                        busy = false
                        IosPlayerHost.showMessage(getString(Res.string.listen_together_switched_to_default))
                        onDismiss()
                    }
                }, enabled = !busy) { Text(stringResource(Res.string.listen_together_remove_server)) }
            } else {
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
}
