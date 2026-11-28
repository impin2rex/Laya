package com.music.laya.ui.tv.dialogs

import android.content.pm.PackageManager
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.music.laya.R
import com.music.laya.auth.LosslessVerifyDialog
import com.music.laya.data.sources.LosslessSource
import com.music.laya.data.sources.SourceConfig
import com.music.laya.data.sources.SourceHealth
import com.music.laya.data.sources.SourceKind
import com.music.laya.data.sources.SourceRegistry
import com.music.laya.data.sources.lossless.LosslessProvider
import com.music.laya.platform.localDateTime
import com.music.laya.ui.tv.components.TvDialog
import com.music.laya.ui.tv.components.TvDialogButton
import com.music.laya.ui.tv.components.TvListRow
import com.music.laya.ui.tv.components.TvTextField
import com.music.laya.ui.tv.components.tvInitialFocus
import com.music.laya.ui.tv.theme.TvGlass
import com.music.laya.ui.tv.theme.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's Sources screen for a remote: every source in the order it is tried. Select turns a
 * built-in on or off (Tidal and Qobuz verify through the broker's page when turned on) or opens an
 * addon's editor; Left and Right move an addon up or down, since only addons' order is the user's.
 * One alert at a time: the editor, the verify page and the JioSaavn warning each replace the list.
 */
@Composable
fun TvSourcesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val configs by SourceRegistry.configs.collectAsState()
    var editing by remember { mutableStateOf<SourceConfig?>(null) }
    var verifying by remember { mutableStateOf<Pair<LosslessProvider, String>?>(null) }
    var confirmJioSaavn by remember { mutableStateOf(false) }

    verifying?.let { (provider, configId) ->
        // Closed without a session, the source goes back off, as on the phone.
        LosslessVerifyDialog(provider) {
            if (!provider.session.verified.value) SourceRegistry.setEnabled(configId, false)
            verifying = null
        }
        return
    }
    editing?.let { config ->
        TvAddonEditorDialog(config = config, onDismiss = { editing = null })
        return
    }
    if (confirmJioSaavn) {
        TvDialog(
            title = stringResource(R.string.enable_jiosaavn),
            message = stringResource(R.string.jiosaavn_mismatch_warning),
            onDismissRequest = { confirmJioSaavn = false },
        ) {
            TvDialogButton(
                text = stringResource(R.string.enable_anyway),
                onClick = {
                    configs.firstOrNull { it.kind == SourceKind.JIOSAAVN }?.let { SourceRegistry.setEnabled(it.id, true) }
                    confirmJioSaavn = false
                },
            )
            Spacer(modifier = Modifier.height(6.dp))
            TvDialogButton(text = stringResource(R.string.cancel), initialFocus = true, onClick = { confirmJioSaavn = false })
        }
        return
    }

    val addons = configs.filter { it.kind.isUserAdded }
    val order = addons + configs.filterNot { it.kind.isUserAdded }.sortedBy { it.kind.rank }

    TvDialog(
        title = stringResource(R.string.source),
        message = stringResource(R.string.tv_sources_message),
        width = 512.dp,
        onDismissRequest = onDismiss,
    ) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 304.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            itemsIndexed(order, key = { _, config -> config.id }) { index, config ->
                TvListRow(
                    title = config.displayName,
                    subtitle = if (config.kind.isLossless) losslessStatus(config) else config.kind.label,
                    value = "${index + 1}",
                    leadingIcon = if (config.enabled) Icons.Rounded.Check else null,
                    modifier = Modifier
                        .tvInitialFocus(enabled = index == 0)
                        .onKeyEvent { event ->
                            if (!config.kind.isUserAdded || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            val target = when (event.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> index - 1
                                KeyEvent.KEYCODE_DPAD_RIGHT -> index + 1
                                else -> return@onKeyEvent false
                            }
                            if (target in addons.indices) {
                                SourceRegistry.reorderAddons(
                                    addons.map(SourceConfig::id).toMutableList().apply { add(target, removeAt(index)) },
                                )
                            }
                            true
                        },
                    onClick = {
                        val on = !config.enabled
                        when {
                            config.kind.needsServer -> editing = config
                            // Not switchable: see SourceRegistry.setEnabled.
                            config.kind == SourceKind.YOUTUBE -> Unit
                            config.kind == SourceKind.JIOSAAVN && on -> confirmJioSaavn = true
                            // The check is a web page; a TV without a WebView can't show it.
                            config.kind.isLossless && on && !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WEBVIEW) ->
                                Toast.makeText(context, context.getString(R.string.lossless_verify_failed), Toast.LENGTH_LONG).show()
                            config.kind.isLossless -> {
                                SourceRegistry.setEnabled(config.id, on)
                                LosslessSource.toggled(on, LosslessSource.providerFor(config.kind))?.let { verifying = it to config.id }
                            }
                            else -> SourceRegistry.setEnabled(config.id, on)
                        }
                    },
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        TvDialogButton(text = stringResource(R.string.add_addon), onClick = { editing = SourceConfig(kind = SourceKind.ADDON) })
        Spacer(modifier = Modifier.height(6.dp))
        TvDialogButton(text = stringResource(R.string.done), onClick = onDismiss)
    }
}

/** The phone's status line for a Tidal or Qobuz row. */
@Composable
private fun losslessStatus(config: SourceConfig): String {
    val session = LosslessSource.providerFor(config.kind).session
    val verified by session.verified.collectAsState()
    val expired by session.expired.collectAsState()
    val until by session.expiresAtMs.collectAsState()
    return when {
        config.enabled && verified -> until?.let { stringResource(R.string.lossless_verified_until, localDateTime(it)) }
            ?: stringResource(R.string.lossless_verified)
        expired -> stringResource(R.string.lossless_verify_expired)
        else -> stringResource(R.string.lossless_verify_on_enable)
    }
}

/**
 * An addon's address, as the phone's editor takes it: Test and Save both identify what is at the
 * URL, refuse a duplicate, then probe it; Save stores it once the format is known, even if the probe
 * came back unhappy. The user agent is kept as it was.
 */
@Composable
private fun TvAddonEditorDialog(config: SourceConfig, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isNew = SourceRegistry.config(config.id) == null
    val enabled = SourceRegistry.configs.collectAsState().value.firstOrNull { it.id == config.id }?.enabled ?: config.enabled
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun run(thenSave: Boolean) {
        busy = true
        status = null
        scope.launch {
            val identified = withContext(Dispatchers.IO) {
                runCatching { SourceRegistry.identify(baseUrl.trim(), config.takeUnless { isNew }) }
                    .getOrElse { Result.failure(it) }
            }
            val found = identified.getOrNull()
            val duplicate = found?.let { SourceRegistry.duplicateOf(it.baseUrl, exceptId = config.id.takeUnless { isNew }) }
            if (found == null || duplicate != null) {
                status = (duplicate?.let { context.getString(R.string.source_already_added, it.displayName) }
                    ?: identified.exceptionOrNull()?.message
                    ?: context.getString(R.string.source_unrecognised)) to false
                busy = false
                return@launch
            }
            val health = withContext(Dispatchers.IO) {
                runCatching { SourceRegistry.probeCandidate(found) }
                    .getOrElse { SourceHealth.Unreachable(it.message ?: "Failed") }
            }
            status = when (health) {
                is SourceHealth.Ok -> listOfNotNull(context.getString(R.string.connected), found.kind.label, health.detail).joinToString(" · ")
                is SourceHealth.Rejected -> health.reason
                is SourceHealth.Unreachable -> health.reason
            } to health.isOk
            busy = false
            if (thenSave) {
                if (isNew) SourceRegistry.add(found) else SourceRegistry.update(found)
                onDismiss()
            }
        }
    }

    TvDialog(
        title = if (isNew) stringResource(R.string.add_addon) else config.displayName,
        message = stringResource(R.string.addon_url_description),
        onDismissRequest = onDismiss,
    ) {
        TvTextField(
            value = baseUrl,
            onValueChange = {
                baseUrl = it
                status = null
            },
            placeholder = "https://my-addon.example.com",
            imeAction = ImeAction.Done,
            capitalization = KeyboardCapitalization.None,
            modifier = Modifier.tvInitialFocus(),
        )
        status?.let { (text, good) ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = text, style = TvType.Caption, color = if (good) TvGlass.TextPrimary else TvGlass.TextTertiary)
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (!isNew) {
            TvListRow(
                title = stringResource(R.string.enabled),
                value = if (enabled) stringResource(R.string.tv_on) else stringResource(R.string.off),
                onClick = { SourceRegistry.setEnabled(config.id, !enabled) },
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        TvDialogButton(text = stringResource(R.string.save), enabled = baseUrl.isNotBlank() && !busy, onClick = { run(thenSave = true) })
        Spacer(modifier = Modifier.height(6.dp))
        TvDialogButton(text = stringResource(R.string.test), enabled = baseUrl.isNotBlank() && !busy, onClick = { run(thenSave = false) })
        if (!isNew) {
            Spacer(modifier = Modifier.height(6.dp))
            TvDialogButton(
                text = stringResource(R.string.remove),
                destructive = true,
                onClick = {
                    SourceRegistry.remove(config.id)
                    onDismiss()
                },
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        TvDialogButton(text = stringResource(R.string.cancel), onClick = onDismiss)
    }
}
