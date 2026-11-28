package com.music.laya.data.sources.lossless

import com.music.laya.platform.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reminders before a Tidal / Qobuz session ends, so it is renewed (no captcha) rather than lost to an app that
 * was closed or suspended through its last hour. Each platform delivers them its own way: iOS as scheduled
 * notifications (it can't run code in the background), Android and desktop by renewing then and notifying only
 * when that fails. Tapping one renews and says how it went ([renew]).
 */
object LosslessReminders {
    /** Minutes before the end at which a reminder is due. */
    val LEADS_MINUTES = listOf(60, 30)

    sealed interface Outcome {
        data class Renewed(val untilMs: Long) : Outcome
        data object Failed : Outcome
    }

    fun provider(id: String): LosslessProvider? = LosslessProvider.all.firstOrNull { it.id == id }

    suspend fun renew(provider: LosslessProvider): Outcome =
        provider.session.renewNow()?.let { Outcome.Renewed(it) } ?: Outcome.Failed

    /**
     * Keeps each session's reminders in step with its end: on every change [cancel] drops the old ones, then
     * [schedule] gets each lead still ahead (provider, minutes left, epoch ms). A session ended or dropped only cancels.
     */
    fun watch(
        scope: CoroutineScope,
        cancel: (LosslessProvider) -> Unit,
        schedule: (LosslessProvider, Int, Long) -> Unit,
    ) {
        LosslessProvider.all.forEach { provider ->
            scope.launch {
                provider.session.expiresAtMs.collect { end ->
                    cancel(provider)
                    if (end == null) return@collect
                    val now = currentTimeMillis()
                    LEADS_MINUTES.forEach { lead ->
                        val at = end - lead * 60_000L
                        if (at > now) schedule(provider, lead, at)
                    }
                }
            }
        }
    }
}
