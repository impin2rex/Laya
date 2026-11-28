package com.music.laya.data.sources

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.music.laya.MainActivity
import com.music.laya.R
import com.music.laya.data.sources.lossless.LosslessProvider
import com.music.laya.data.sources.lossless.LosslessReminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android's half of [LosslessReminders]: an alarm at each lead renews the session in the background, which needs
 * no captcha, and a notification goes up only when that fails. Tapping it opens the app, which tries once more and
 * says how it went ([LosslessRenewLink]).
 */
object LosslessReminderAlarms {
    private const val CHANNEL_ID = "lossless_sessions"
    internal const val EXTRA_PROVIDER = "lossless_provider"
    internal const val EXTRA_MINUTES = "lossless_minutes"

    /** Call once at startup, after the sources are loaded. */
    fun install(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        LosslessReminders.watch(
            scope,
            cancel = { provider -> LosslessReminders.LEADS_MINUTES.forEach { alarms(app).cancel(alarm(app, provider, it)) } },
            // Inexact: a reminder minutes late still lands inside the session's last hour, and needs no exact-alarm permission.
            schedule = { provider, minutes, at -> alarms(app).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(app, provider, minutes)) },
        )
    }

    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun alarm(context: Context, provider: LosslessProvider, minutes: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode(provider, minutes),
        Intent(context, LosslessReminderReceiver::class.java)
            .putExtra(EXTRA_PROVIDER, provider.id)
            .putExtra(EXTRA_MINUTES, minutes),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun requestCode(provider: LosslessProvider, minutes: Int) = provider.id.hashCode() * 100 + minutes

    internal fun notifyFailed(context: Context, provider: LosslessProvider, minutes: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.lossless_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            requestCode(provider, minutes),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_PROVIDER, provider.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(context.getString(R.string.lossless_expiring_title, provider.label))
            .setContentText(context.getString(R.string.lossless_renew_failed_body, minutes))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // Without the notification permission nothing shows; the in-app notice still covers the session's end.
        runCatching { NotificationManagerCompat.from(context).notify(provider.id.hashCode(), notification) }
    }

    internal fun dismiss(context: Context, provider: LosslessProvider) {
        NotificationManagerCompat.from(context).cancel(provider.id.hashCode())
    }
}

/** A reminder's alarm: renew quietly; only a failure reaches the shade. */
class LosslessReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val provider = intent.getStringExtra(LosslessReminderAlarms.EXTRA_PROVIDER)?.let(LosslessReminders::provider) ?: return
        val minutes = intent.getIntExtra(LosslessReminderAlarms.EXTRA_MINUTES, 0)
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (LosslessReminders.renew(provider) is LosslessReminders.Outcome.Failed) {
                    LosslessReminderAlarms.notifyFailed(app, provider, minutes)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** The failure notification, tapped: renew once more and say how it went. */
object LosslessRenewLink {
    fun consume(activity: MainActivity, intent: Intent?) {
        val provider = intent?.getStringExtra(LosslessReminderAlarms.EXTRA_PROVIDER)?.let(LosslessReminders::provider) ?: return
        intent.removeExtra(LosslessReminderAlarms.EXTRA_PROVIDER)
        val app = activity.applicationContext
        LosslessReminderAlarms.dismiss(app, provider)
        MainScope().launch {
            val message = when (val outcome = LosslessReminders.renew(provider)) {
                is LosslessReminders.Outcome.Renewed -> app.getString(
                    R.string.lossless_renewed_until, provider.label, formatEnd(app, outcome.untilMs),
                )
                LosslessReminders.Outcome.Failed -> app.getString(R.string.lossless_renew_failed, provider.label)
            }
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
    }

    /** "18:42", with the date when it isn't today (a session runs hours, so it can end tomorrow). */
    private fun formatEnd(context: Context, ms: Long): String {
        val flags = DateUtils.FORMAT_SHOW_TIME or if (DateUtils.isToday(ms)) 0 else DateUtils.FORMAT_SHOW_DATE
        return DateUtils.formatDateTime(context, ms, flags)
    }
}
