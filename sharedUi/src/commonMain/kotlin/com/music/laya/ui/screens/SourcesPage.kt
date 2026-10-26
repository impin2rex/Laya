package com.music.laya.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.settings.AudioQuality
import com.music.laya.data.settings.permits
import com.music.laya.data.sources.AddonSource
import com.music.laya.data.sources.SourceConfig
import com.music.laya.data.sources.SourceHealth
import com.music.laya.data.sources.SourceKind
import com.music.laya.data.sources.SourceRegistry
import com.music.laya.data.sources.addon.AddonClient
import com.music.laya.platform.ioDispatcher
import com.music.laya.sharedui.resources.*
import com.music.laya.ui.components.PillTextField
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * The Sources page: the addons the user added, in the order they are asked, then YouTube. The
 * shared counterpart of the Sources group on Android's SourcesScreen, minus the remote-library
 * sections and the lossless-output gate, which don't exist off Android yet. Addons reorder with
 * arrows (a drag handle needs the Android-only reorderable list).
 */
@Composable
fun SourcesPage(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val configs by SourceRegistry.configs.collectAsStateWithLifecycle()
    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
    val health = remember { mutableStateMapOf<String, SourceHealth>() }
    var editing by remember { mutableStateOf<SourceConfig?>(null) }

    // Probed again whenever an address changes, and an addon that names itself fills its label in.
    val probeKey = configs.filter { it.kind.needsServer }.joinToString { "${it.id}@${it.baseUrl}" }
    LaunchedEffect(probeKey) {
        configs.filter { it.kind.needsServer && it.isComplete }.forEach { config ->
            val source = SourceRegistry.instance(config.id) ?: return@forEach
            health[config.id] = withContext(ioDispatcher) {
                runCatching { source.health() }.getOrElse { SourceHealth.Unreachable(it.message ?: "Failed") }
            }
            if (source is AddonSource) {
                val named = withContext(ioDispatcher) { runCatching { source.manifestName() }.getOrNull() }
                if (named != null && named != config.label) SourceRegistry.update(config.copy(label = named))
            }
        }
    }

    val ceiling = if (metered == true) cellularQuality else wifiQuality
    val cappedByQuality = ceiling != AudioQuality.LOSSLESS
    val anyLosslessSource = configs.any { it.enabled && it.isComplete && it.kind.canServeLossless }
    val addons = configs.filter { it.kind.isUserAdded }
    val ordered = addons + configs.filterNot { it.kind.isUserAdded }.sortedBy { it.kind.rank }
    fun move(config: SourceConfig, by: Int) {
        val ids = addons.map { it.id }.toMutableList()
        val at = ids.indexOf(config.id)
        if (at + by !in ids.indices) return
        ids.add(at + by, ids.removeAt(at))
        SourceRegistry.reorderAddons(ids)
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(contentPadding),
    ) {
        Text(
            text = stringResource(Res.string.source),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        )
        SettingsGroup(
            header = stringResource(Res.string.sources_order_header),
            footer = buildString {
                append(stringResource(Res.string.sources_order_footer))
                if (cappedByQuality) {
                    append("\n\n").append(stringResource(Res.string.sources_quality_capped))
                } else if (!anyLosslessSource) {
                    append("\n\n").append(stringResource(Res.string.sources_no_lossless))
                }
            },
        ) {
            val skippedOn = stringResource(if (metered == true) Res.string.mobile_data else Res.string.wifi)
            val ceilingLabel = ceiling.localizedLabel()
            ordered.forEach { config ->
                val skipped = config.enabled && !ceiling.permits(config.kind)
                SettingsRow(
                    icon = when (config.kind) {
                        SourceKind.YOUTUBE -> Icons.Rounded.PlayCircle
                        SourceKind.JIOSAAVN -> Icons.Rounded.GraphicEq
                        else -> Icons.Rounded.Extension
                    },
                    title = config.displayName,
                    subtitle = if (skipped) {
                        stringResource(Res.string.source_skipped_by_quality, skippedOn, ceilingLabel)
                    } else {
                        config.statusLine(health[config.id])
                    },
                    enabled = config.enabled || config.kind == SourceKind.YOUTUBE,
                    onClick = if (config.kind.needsServer) ({ editing = config }) else null,
                    trailing = {
                        if (config.kind == SourceKind.YOUTUBE) {
                            Text(
                                stringResource(Res.string.always_on),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else Row {
                            if (config.kind.isUserAdded && addons.size > 1) {
                                val at = addons.indexOfFirst { it.id == config.id }
                                IconButton(onClick = { move(config, -1) }, enabled = at > 0, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(Res.string.drag_to_reorder))
                                }
                                IconButton(onClick = { move(config, 1) }, enabled = at < addons.lastIndex, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(Res.string.drag_to_reorder))
                                }
                            }
                            Switch(
                                checked = config.enabled,
                                onCheckedChange = { SourceRegistry.setEnabled(config.id, it) },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    },
                )
                RowDivider()
            }
            SettingsRow(
                icon = Icons.Rounded.Add,
                title = stringResource(Res.string.add_addon),
                subtitle = stringResource(Res.string.add_addon_detail),
                onClick = { editing = SourceConfig(kind = SourceKind.ADDON) },
                trailing = {},
            )
        }
        Spacer(Modifier.height(32.dp))
    }

    editing?.let { AddonEditor(it, onDismiss = { editing = null }) }
}

@Composable
private fun AudioQuality.localizedLabel(): String = stringResource(
    when (this) {
        AudioQuality.LOW -> Res.string.low
        AudioQuality.MEDIUM -> Res.string.medium
        AudioQuality.HIGH -> Res.string.high
        AudioQuality.LOSSLESS -> Res.string.lossless
    },
)

@Composable
private fun SourceConfig.statusLine(health: SourceHealth?): String = when {
    !isComplete -> stringResource(Res.string.source_setup_required)
    health is SourceHealth.Ok -> listOfNotNull(
        health.detail,
        kind.labels.take(3).joinToString(" · "),
        stringResource(Res.string.source_downloads_off).takeIf { !allowDownloads },
    ).joinToString(" · ")
    health is SourceHealth.Rejected -> health.reason
    health is SourceHealth.Unreachable -> stringResource(Res.string.source_unreachable, health.reason)
    kind.needsServer -> stringResource(Res.string.checking)
    else -> kind.labels.take(3).joinToString(" · ")
}

/** Add or edit one addon: the address is identified, health-checked, then saved (Android's SourceEditorAlert). */
@Composable
private fun AddonEditor(config: SourceConfig, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val isNew = SourceRegistry.config(config.id) == null
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var userAgent by remember { mutableStateOf(config.userAgent) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val connected = stringResource(Res.string.connected)
    val unreadable = stringResource(Res.string.source_unrecognised)
    val alreadyAdded = stringResource(Res.string.source_already_added)

    fun run(thenSave: Boolean) {
        busy = true
        status = null
        scope.launch {
            val identified = runCatching { SourceRegistry.identify(baseUrl.trim(), config.takeUnless { isNew }, userAgent) }
                .getOrElse { Result.failure(it) }
            val found = identified.getOrNull()
            if (found == null) {
                status = (identified.exceptionOrNull()?.message ?: unreadable) to false
                busy = false
                return@launch
            }
            SourceRegistry.duplicateOf(found.baseUrl, exceptId = config.id.takeUnless { isNew })?.let { existing ->
                status = alreadyAdded.replace("%1\$s", existing.displayName) to false
                busy = false
                return@launch
            }
            val probe = runCatching { SourceRegistry.probeCandidate(found) }
                .getOrElse { SourceHealth.Unreachable(it.message ?: "Failed") }
            status = when (probe) {
                is SourceHealth.Ok -> listOfNotNull(connected, found.kind.label, probe.detail).joinToString(" · ")
                is SourceHealth.Rejected -> probe.reason
                is SourceHealth.Unreachable -> probe.reason
            } to probe.isOk
            busy = false
            if (thenSave) {
                if (isNew) SourceRegistry.add(found) else SourceRegistry.update(found)
                onDismiss()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (isNew) stringResource(Res.string.add_addon) else config.displayName) },
        text = {
            Column {
                Text(
                    status?.first ?: stringResource(Res.string.addon_url_description),
                    color = when (status?.second) {
                        false -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                PillTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; status = null },
                    placeholder = "https://my-addon.example.com",
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                PillTextField(
                    value = userAgent,
                    onValueChange = { userAgent = it; status = null }, // a new agent is a new question
                    placeholder = AddonClient.DEFAULT_USER_AGENT,
                    enabled = !busy,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = { run(thenSave = false) }, enabled = baseUrl.isNotBlank() && !busy) {
                    Text(stringResource(if (busy) Res.string.testing else Res.string.test))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { run(thenSave = true) }, enabled = baseUrl.isNotBlank() && !busy) {
                Text(stringResource(Res.string.save))
            }
        },
        dismissButton = {
            if (isNew) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(Res.string.cancel)) }
            } else {
                TextButton(onClick = { SourceRegistry.remove(config.id); onDismiss() }, enabled = !busy) {
                    Text(stringResource(Res.string.remove_source))
                }
            }
        },
    )
}
