package com.music.laya.recognition

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.music.laya.MainActivity
import com.music.laya.R
import com.music.laya.data.recognition.MusicRecognizer
import com.music.laya.data.recognition.RecognitionError
import com.music.laya.data.recognition.RecognitionShortcutHint
import com.music.laya.data.recognition.RecognitionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** "Open the recognition page" — from the tile's notifications and its long-press into [MainActivity]. */
object RecognitionLink {
    private const val ACTION_OPEN = "com.music.laya.action.RECOGNIZE"
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun consume(intent: Intent?): Boolean {
        // Also the tile's long-press, which the system sends as QS_TILE_PREFERENCES.
        if (intent?.action != ACTION_OPEN && intent?.action != TileService.ACTION_QS_TILE_PREFERENCES) return false
        intent.action = null
        _pending.value = true
        return true
    }

    fun handled() {
        _pending.value = false
    }

    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

fun hasMicrophonePermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * Starts a session the way every entry point does: through [RecognizeActivity],
 * which asks for the microphone, then hands over to [RecognitionService]. [notifyResult]
 * posts the outcome as a notification — for the tile, where no page shows it.
 */
/**
 * The launcher's long-press "Recognize music": Laya on its Recognize page, listening. The page goes first, so
 * RecognizeActivity (translucent) starts the microphone over it and finishes back onto it.
 */
fun recognizeShortcutIntents(context: Context): Array<Intent> = arrayOf(
    RecognitionLink.intent(context),
    // A shortcut's intents all need an action; RecognizeActivity ignores it.
    Intent(Intent.ACTION_VIEW, null, context, RecognizeActivity::class.java).putExtra(EXTRA_NOTIFY, false),
)

fun listen(context: Context, notifyResult: Boolean) {
    context.startActivity(
        Intent(context, RecognizeActivity::class.java)
            .putExtra(EXTRA_NOTIFY, notifyResult)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * Starts [RecognitionService], so it keeps the microphone when the app leaves
 * the screen. Only from the foreground (Android refuses a microphone service
 * started from the background).
 */
private fun startRecognition(context: Context, notifyResult: Boolean) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, RecognitionService::class.java).putExtra(EXTRA_NOTIFY, notifyResult),
    )
}

private const val EXTRA_NOTIFY = "notify"
private const val ACTION_CANCEL = "com.music.laya.action.RECOGNIZE_CANCEL"
/** "Listening…" while the service runs: quiet. (The first build's single "recognition" channel is retired.) */
private const val PROGRESS_CHANNEL = "recognition_progress"

/**
 * The outcome: high importance, so it pops up instead of landing unseen in the shade, but
 * with no sound or vibration. A channel's sound can't be changed once made, hence "_silent".
 */
private const val RESULT_CHANNEL = "recognition_result_silent"
/** One notification for the whole session: "Listening…", then the outcome in its place. */
private const val NOTIFICATION_ID = 0x5A2A

/** Holds the microphone (as a microphone-type foreground service) for the length of one session. */
class RecognitionService : Service() {
    private val scope = MainScope()
    private var watch: Job? = null
    private var notifyResult = false

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            MusicRecognizer.cancel()
            return START_NOT_STICKY
        }
        createChannel()
        notifyResult = notifyResult || intent?.getBooleanExtra(EXTRA_NOTIFY, false) == true
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            progress(MusicRecognizer.state.value.takeIf { it.isActive } ?: RecognitionState.Listening),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        MusicRecognizer.start()
        if (watch == null) {
            watch = scope.launch {
                MusicRecognizer.state.collect { state ->
                    RecognitionTileService.refresh(this@RecognitionService)
                    when {
                        state.isActive -> post(NOTIFICATION_ID, progress(state))
                        else -> {
                            // Also when listening began in the app but the app has since been left.
                            val outcome = if (notifyResult || !inForeground()) result(state) else null
                            // Detached, not removed: "Listening…" becomes the outcome rather than vanishing
                            // for a second notification to appear. The update, on the result channel, pops up.
                            ServiceCompat.stopForeground(
                                this@RecognitionService,
                                if (outcome != null) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE,
                            )
                            outcome?.let { post(NOTIFICATION_ID, it) }
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // The system can stop us mid-session; don't leave the recognizer listening without a service.
        if (MusicRecognizer.state.value.isActive) MusicRecognizer.cancel()
        RecognitionTileService.refresh(this)
        super.onDestroy()
    }

    private fun progress(state: RecognitionState) = NotificationCompat.Builder(this, PROGRESS_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification_logo)
        .setContentTitle(getString(R.string.recognize_title))
        .setContentText(
            getString(if (state == RecognitionState.Identifying) R.string.recognize_identifying else R.string.recognize_listening),
        )
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp())
        .addAction(
            0,
            getString(R.string.recognize_cancel),
            PendingIntent.getService(
                this, 1,
                Intent(this, RecognitionService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun result(state: RecognitionState) = when (state) {
        is RecognitionState.Recognized -> getString(R.string.recognize_title) to "${state.track.title} — ${state.track.artist}"
        RecognitionState.NotRecognized -> getString(R.string.recognize_not_found) to null
        is RecognitionState.Failed -> getString(R.string.recognize_failed) to null
        else -> null
    }?.let { (title, text) ->
        val builder = NotificationCompat.Builder(this, RESULT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        if (text == null) builder.setContentTitle(title) else builder.setContentTitle(text).setContentText(title)
        if (state !is RecognitionState.Recognized) builder.addAction(0, getString(R.string.recognize_try_again), retry(state))
        builder.build()
    }

    /**
     * Straight back to listening: a notification's own action may start the
     * microphone service with nothing on screen, so whatever is playing isn't
     * covered (and paused). A refused permission goes through the activity, to ask again.
     */
    private fun retry(state: RecognitionState): PendingIntent {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (state == RecognitionState.Failed(RecognitionError.MICROPHONE_PERMISSION)) {
            PendingIntent.getActivity(
                this, 2,
                Intent(this, RecognizeActivity::class.java).putExtra(EXTRA_NOTIFY, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                flags,
            )
        } else {
            PendingIntent.getForegroundService(this, 2, Intent(this, RecognitionService::class.java).putExtra(EXTRA_NOTIFY, true), flags)
        }
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, RecognitionLink.intent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission") // Without POST_NOTIFICATIONS this is a silent no-op, which is fine.
    private fun post(id: Int, notification: android.app.Notification) {
        NotificationManagerCompat.from(this).notify(id, notification)
    }

    /** Whether a Laya screen is showing (the service alone counts as "foreground service", not this). */
    private fun inForeground() = ActivityManager.RunningAppProcessInfo()
        .also(ActivityManager::getMyMemoryState)
        .importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // Earlier builds' channels: the single shared one, and the result one that made a sound.
        manager.deleteNotificationChannel("recognition")
        manager.deleteNotificationChannel("recognition_result")
        manager.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL, getString(R.string.recognize_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        manager.createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL, getString(R.string.recognize_channel_results), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    setSound(null, null)
                    enableVibration(false)
                },
        )
    }
}

/** Android 13 lets an app offer its tile with the system's own "Add this button?" sheet. */
val canOfferTile get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** Shows "Add this button?" for the recognition tile; the card that offered it goes once it's in. */
fun offerTile(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    context.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
        ComponentName(context, RecognitionTileService::class.java),
        context.getString(R.string.recognize_title),
        Icon.createWithResource(context, R.drawable.ic_notification_logo),
        context.mainExecutor,
    ) { result ->
        if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
            result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
        ) {
            RecognitionShortcutHint.dismiss()
        }
    }
}

/** The Quick Settings tile: tap to listen, tap again to stop. */
class RecognitionTileService : TileService() {
    /** Added from the system's own editor too: nothing left for the in-app card to suggest. */
    override fun onTileAdded() = RecognitionShortcutHint.dismiss()

    override fun onStartListening() {
        val tile = qsTile ?: return
        val state = MusicRecognizer.state.value
        tile.state = if (state.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.recognize_title)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification_logo)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when (state) {
                    RecognitionState.Listening -> R.string.recognize_listening
                    RecognitionState.Identifying -> R.string.recognize_identifying
                    else -> R.string.recognize_tile_idle
                },
            )
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (MusicRecognizer.state.value.isActive) {
            MusicRecognizer.cancel()
            return
        }
        // Through an activity: a microphone service may only start while the app is in the foreground.
        val intent = Intent(this, RecognizeActivity::class.java)
            .putExtra(EXTRA_NOTIFY, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun refresh(context: Context) =
            requestListeningState(context, ComponentName(context, RecognitionTileService::class.java))
    }
}

/**
 * Invisible, and shown over the lock screen: brings the app to the foreground
 * for the instant it takes to ask for the microphone (first time only), then
 * starts the service and gets out of the way. A refusal shows on the
 * recognition page, opened for it when there's no app on screen.
 */
class RecognizeActivity : ComponentActivity() {
    private val notifyResult get() = intent.getBooleanExtra(EXTRA_NOTIFY, false)

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startRecognition(this, notifyResult)
        } else {
            MusicRecognizer.reportPermissionDenied()
            if (notifyResult) startActivity(RecognitionLink.intent(this))
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (hasMicrophonePermission(this)) {
            startRecognition(this, notifyResult)
            finish()
        } else {
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
