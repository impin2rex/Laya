package com.music.laya.ios

import com.music.laya.data.sources.lossless.LosslessProvider
import com.music.laya.data.sources.lossless.LosslessReminders
import com.music.laya.sharedui.resources.Res
import com.music.laya.sharedui.resources.lossless_expiring_body
import com.music.laya.sharedui.resources.lossless_expiring_title
import com.music.laya.sharedui.resources.lossless_renew_failed
import com.music.laya.sharedui.resources.lossless_renewed_until
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSCalendar
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS's half of [LosslessReminders]: iOS gives a suspended app no time to renew in, so each lead is a scheduled
 * notification, which fires whether or not Laya runs. Tapping it opens Laya, which renews and says how it went.
 */
internal object IosLosslessReminders {
    private const val PREFIX = "laya.lossless."
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()
    private var asked = false
    private var installed = false
    /** A reminder tapped before [install] (it cold-started Laya): answered once the sessions are loaded. */
    private var pending: String? = null

    fun install(scope: CoroutineScope) {
        installed = true
        pending?.let { pending = null; renewAndTell(it) }
        LosslessReminders.watch(
            scope,
            cancel = { provider -> center.removePendingNotificationRequestsWithIdentifiers(LosslessReminders.LEADS_MINUTES.map { id(provider, it) }) },
            schedule = { provider, minutes, at ->
                // Asked first: renewed in the background in time, the session's end moves and these never fire.
                IosLosslessBackground.request(at)
                scope.launch { post(provider, minutes, at) }
            },
        )
    }

    private fun id(provider: LosslessProvider, minutes: Int) = "$PREFIX${provider.id}.$minutes"

    private suspend fun post(provider: LosslessProvider, minutes: Int, at: Long) {
        // Asked when a reminder is first due to be set, i.e. right after verifying: the moment it makes sense.
        if (!asked) {
            asked = true
            center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ -> }
        }
        // Loaded before building the content, never inside its arguments (see RecognitionNotifier.text).
        val title = withContext(Dispatchers.Default) { getString(Res.string.lossless_expiring_title, provider.label) }
        val body = withContext(Dispatchers.Default) { getString(Res.string.lossless_expiring_body, minutes) }
        val seconds = (at - com.music.laya.platform.currentTimeMillis()).toDouble() / 1000
        if (seconds < 1) return
        val content = UNMutableNotificationContent()
        content.setTitle(title)
        content.setBody(body)
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds, repeats = false)
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(id(provider, minutes), content, trigger), withCompletionHandler = null)
    }

    /** True when [identifier] was a reminder, which this then answers: renew, then the notice. */
    fun handleTap(identifier: String): Boolean {
        if (!identifier.startsWith(PREFIX)) return false
        val id = identifier.removePrefix(PREFIX).substringBeforeLast('.')
        if (installed) renewAndTell(id) else pending = id
        return true
    }

    private fun renewAndTell(id: String) {
        val provider = LosslessReminders.provider(id) ?: return
        MainScope().launch {
            val message = when (val outcome = LosslessReminders.renew(provider)) {
                is LosslessReminders.Outcome.Renewed -> withContext(Dispatchers.Default) {
                    getString(Res.string.lossless_renewed_until, provider.label, formatEnd(outcome.untilMs))
                }
                LosslessReminders.Outcome.Failed -> withContext(Dispatchers.Default) {
                    getString(Res.string.lossless_renew_failed, provider.label)
                }
            }
            IosPlayerHost.showMessage(message)
        }
    }

    /** The device's short time, with the date when it isn't today. */
    private fun formatEnd(ms: Long): String {
        val date = NSDate.dateWithTimeIntervalSince1970(ms / 1000.0)
        val formatter = NSDateFormatter()
        formatter.timeStyle = NSDateFormatterShortStyle
        formatter.dateStyle = if (NSCalendar.currentCalendar.isDateInToday(date)) NSDateFormatterNoStyle else NSDateFormatterShortStyle
        return formatter.stringFromDate(date)
    }
}
