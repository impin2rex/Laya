package com.music.laya.desktop

import com.music.laya.data.sources.lossless.LosslessProvider
import com.music.laya.data.sources.lossless.LosslessReminders
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The desktop's half of [LosslessReminders], while Laya runs: at each lead the session is renewed, and only a
 * failure becomes a system notification. Clicking it raises the window, renews once more and says how it went.
 */
internal object DesktopLosslessReminders {
    private val jobs = mutableMapOf<String, MutableList<Job>>()

    fun install(scope: CoroutineScope) {
        LosslessReminders.watch(
            scope,
            cancel = { provider -> jobs.remove(provider.id)?.forEach { it.cancel() } },
            schedule = { provider, minutes, at ->
                jobs.getOrPut(provider.id) { mutableListOf() } += scope.launch {
                    delay(at - System.currentTimeMillis())
                    if (LosslessReminders.renew(provider) is LosslessReminders.Outcome.Failed) {
                        DesktopRecognitionNotifier.post(
                            DesktopStrings.format("lossless_expiring_title", provider.label, fallback = "%1\$s session ending soon"),
                            DesktopStrings.format(
                                "lossless_renew_failed_body", minutes,
                                fallback = "Couldn’t renew it automatically. It ends in %1\$d min; tap to try again.",
                            ),
                        ) { scope.launch { renewAndTell(provider) } }
                    }
                }
            },
        )
    }

    private suspend fun renewAndTell(provider: LosslessProvider) {
        val message = when (val outcome = LosslessReminders.renew(provider)) {
            is LosslessReminders.Outcome.Renewed -> DesktopStrings.format(
                "lossless_renewed_until", provider.label, formatEnd(outcome.untilMs), fallback = "%1\$s renewed until %2\$s",
            )
            LosslessReminders.Outcome.Failed -> DesktopStrings.format(
                "lossless_renew_failed", provider.label, fallback = "Couldn’t renew %1\$s. Renew it yourself from the addon.",
            )
        }
        DesktopPlayerHost.showMessage(message)
    }

    /** The locale's short time, with the date when it isn't today. */
    private fun formatEnd(ms: Long): String {
        val at = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        val style = if (at.toLocalDate() == LocalDate.now()) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        return at.format(style)
    }
}
