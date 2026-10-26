package com.music.laya.ui

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.SingletonImageLoader
import com.music.laya.AppIcon
import com.music.laya.R
import com.music.laya.data.LocalMediaRepository
import com.music.laya.data.listentogether.ListenTogether
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.sources.DeviceCodecs
import com.music.laya.data.stats.Backup
import com.music.laya.playback.AudioCache
import com.music.laya.playback.AudioOutputStatus
import com.music.laya.ui.components.languageDisplayNameRes
import com.music.laya.ui.performance.resolvePerformanceRefreshRate
import com.music.laya.ui.performance.supportedPerformanceRefreshRates
import com.music.laya.ui.screens.AudioOutputInfo
import com.music.laya.ui.screens.BackupActions
import com.music.laya.ui.screens.LocalMusicFolder
import com.music.laya.ui.screens.PartyInfo
import com.music.laya.ui.screens.RefreshRates
import com.music.laya.ui.screens.ROW_INSET
import com.music.laya.ui.screens.SettingsHost
import com.music.laya.ui.screens.SettingsRow
import com.music.laya.ui.screens.WearOsSettingsRow
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

object AndroidSettingsHost : SettingsHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    override val playsDolbyAtmos: Boolean get() = DeviceCodecs.playsDolbyAtmos

    override val party: StateFlow<PartyInfo> = ListenTogether.state
        .map { PartyInfo(it.code, if (it.inParty) it.members.size else 0) }
        .stateIn(scope, SharingStarted.Eagerly, PartyInfo(null, 0))

    override val audioOutput: StateFlow<AudioOutputInfo?> = AudioOutputStatus.current
        .map(::outputInfo)
        .stateIn(scope, SharingStarted.Eagerly, outputInfo(AudioOutputStatus.current.value))

    override val dspAvailable: StateFlow<Boolean> = AudioOutputStatus.current
        .map { it.dspAvailable }
        .stateIn(scope, SharingStarted.Eagerly, AudioOutputStatus.current.value.dspAvailable)

    /** Hands the device's own effect panel this app's audio session. */
    override val openSystemEqualizer: () -> Unit = {
        appContext?.let { context ->
            val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, AppSettings.audioSessionId.value)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure {
                Toast.makeText(context, context.getString(R.string.no_equalizer), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun outputInfo(status: AudioOutputStatus.Snapshot) = AudioOutputInfo(
        summary = buildString {
            append(status.sink)
            append(" · ")
            append(status.deviceName)
            (status.actualSampleRateHz ?: status.sampleRatesHz.firstOrNull())
                ?.let { append(" · ${it / 1000.0} kHz") }
            append(" · ")
            append(AudioOutputStatus.encodingLabel(status))
        },
        isUsb = status.isUsb,
        isFloat = status.actualEncoding == AudioFormat.ENCODING_PCM_FLOAT,
    )

    override val hasDownloads get() = true
    override val hasSongCache get() = true
    override val hasBackgroundService get() = true

    override val appIconRow: (@Composable () -> Unit) = { AppIconPicker() }

    @Composable
    override fun versionName(): String {
        val context = LocalContext.current
        return remember(context) {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "1.0"
        }
    }

    @Composable
    override fun appLanguageName(): String {
        val tag = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
            ?: Locale.getDefault().toLanguageTag()
        return stringResource(languageDisplayNameRes(tag))
    }

    @Composable
    override fun WearOsRow(onClick: () -> Unit) = WearOsSettingsRow(onClick)

    @Composable
    override fun rememberRefreshRates(stored: Int, onBatteryReturn: () -> Unit): RefreshRates {
        val context = LocalContext.current
        val display = LocalView.current.display
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onBatteryReturn()
        }
        return RefreshRates(
            supported = remember(display) { display.supportedPerformanceRefreshRates() },
            selected = remember(display, stored) { display.resolvePerformanceRefreshRate(stored) },
            openBatterySettings = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                val settingsIntent = intent.takeIf {
                    it.resolveActivity(context.packageManager) != null
                } ?: Intent(Settings.ACTION_SETTINGS)
                launcher.launch(settingsIntent)
            },
        )
    }

    @Composable
    override fun rememberLocalFolder(uri: String): LocalMusicFolder {
        val context = LocalContext.current
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
            if (folder == null) return@rememberLauncherForActivityResult
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    folder,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            AppSettings.setLocalMusicFolderUri(folder.toString())
        }
        return LocalMusicFolder(LocalMediaRepository.selectedFolderLabel(uri)) { picker.launch(null) }
    }

    /**
     * Both halves go through the system document picker rather than a path of
     * this app's own choosing. That is what puts the file somewhere the user can
     * actually find it — Drive, Files, a folder they already back up — and it
     * means neither direction needs a storage permission, since the grant
     * arrives with the document they picked.
     */
    @Composable
    override fun rememberBackup(onExported: (String) -> Unit, onImported: (String) -> Unit): BackupActions {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val exportPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { target ->
            if (target == null) return@rememberLauncherForActivityResult
            scope.launch {
                onExported(
                    Backup.exportTo(context, target).fold(
                        onSuccess = { months ->
                            context.getString(R.string.export_succeeded, context.countOfMonths(months))
                        },
                        onFailure = {
                            context.getString(R.string.export_failed, it.message ?: context.getString(R.string.unknown_error))
                        },
                    ),
                )
            }
        }
        val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
            if (source == null) return@rememberLauncherForActivityResult
            scope.launch {
                onImported(
                    Backup.importFrom(context, source).fold(
                        onSuccess = {
                            context.getString(R.string.import_succeeded, context.countOfMonths(it.months), it.from)
                        },
                        onFailure = {
                            context.getString(R.string.import_failed, it.message ?: context.getString(R.string.unknown_error))
                        },
                    ),
                )
            }
        }
        return BackupActions(
            export = { exportPicker.launch(Backup.suggestedName()) },
            import = { importPicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
        )
    }

    override fun clearSongCache() {
        val context = appContext ?: return
        AudioCache.clear {
            Toast.makeText(context, context.getString(R.string.song_cache_cleared), Toast.LENGTH_SHORT).show()
        }
    }

    override fun clearImageCache() {
        val context = appContext ?: return
        val loader = SingletonImageLoader.get(context)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
        Toast.makeText(context, context.getString(R.string.image_cache_cleared), Toast.LENGTH_SHORT).show()
    }
}

/** "3 months of listening" — the unit a backup is actually measured in. */
private fun Context.countOfMonths(months: Int): String = if (months == 0) {
    getString(R.string.no_listening_history)
} else {
    resources.getQuantityString(R.plurals.listening_month_count, months, months)
}

@Composable
private fun AppIconPicker() {
    val context = LocalContext.current
    var selectedIcon by remember { mutableStateOf(AppIcon.current(context)) }
    SettingsRow(icon = Icons.Rounded.Apps, title = stringResource(R.string.app_icon))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon.entries.forEach { option ->
            val selected = option == selectedIcon
            Column(
                modifier = Modifier
                    .width(84.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        selectedIcon = option
                        AppIcon.apply(context, option)
                    }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // A glass foreground holds the finished icon in its own shape on the
                // central 72 of 108 dp: scaled 1.5× it fills the tile as designed.
                // Only the default goes through an adaptive mask.
                val preview = option.previewRes
                if (preview == null) {
                    AndroidView(
                        factory = { ctx ->
                            android.widget.ImageView(ctx).apply { setImageResource(option.iconRes) }
                        },
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(22.dp)),
                    )
                } else {
                    Image(
                        painter = painterResource(preview),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(68.dp)
                            .scale(1.5f),
                    )
                }
                Text(
                    text = stringResource(option.labelRes),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
