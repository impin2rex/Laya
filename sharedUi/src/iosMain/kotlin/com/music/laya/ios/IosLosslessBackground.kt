package com.music.laya.ios

import com.music.laya.data.sources.lossless.LosslessProvider
import com.music.laya.data.sources.lossless.LosslessReminders
import com.music.laya.data.sources.lossless.LosslessStore
import com.music.laya.data.TrackLog
import com.music.laya.platform.currentTimeMillis
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970

/**
 * Renews Tidal / Qobuz sessions in the background, as Android's alarms do, when iOS grants the time. A refresh is
 * asked for at each reminder ([IosLosslessReminders]); iOS may run it late or not at all (rarely used app, Low Power
 * Mode, force-quit), and then the reminder notifications still fire. A renewal moves the session's end, which drops
 * and reschedules them, so a session renewed here shows nothing.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
object IosLosslessBackground {
    private const val TASK_ID = "com.music.laya.lossless-renew"
    /** A reminder's lead is 60 or 30 min; anything ending within this is renewed when the task runs. */
    private const val DUE_MS = 65 * 60_000L

    private var started = false
    /** The earliest start asked for; one request per identifier, so a later ask would push an earlier one back. */
    private var requestedAt: Long? = null

    /** From the app delegate's launch, as BGTaskScheduler requires. */
    fun register() {
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(TASK_ID, usingQueue = null) { task ->
            task?.let(::run)
        }
    }

    /**
     * The sessions' store and reminders, without the UI: a background launch has no window, so MainViewController,
     * which otherwise calls this, never runs. Once per process.
     */
    internal fun startSessions() {
        if (started) return
        started = true
        LosslessStore.read = { Keychain.get(it) }
        LosslessStore.write = { key, value -> Keychain.set(key, value) }
        // A session that ran out while the app was closed is reported (and its source switched off) like any other.
        LosslessProvider.all.forEach { it.session.reload() }
        IosLosslessReminders.install(MainScope())
    }

    /** Asks iOS to run the renewal at [atMs] or after, unless an earlier run is already asked for. */
    internal fun request(atMs: Long) {
        val now = currentTimeMillis()
        val pending = requestedAt?.takeIf { it > now }
        if (pending != null && pending <= atMs) return
        val request = BGAppRefreshTaskRequest(TASK_ID)
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSince1970(atMs / 1000.0)
        if (BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)) requestedAt = atMs
    }

    private fun run(task: BGTask) {
        requestedAt = null
        startSessions()
        val job = MainScope().launch {
            val now = currentTimeMillis()
            val due = LosslessProvider.all.filter { p -> p.session.expiresAtMs.value?.let { it - now in 1..DUE_MS } == true }
            var ok = true
            due.forEach { provider ->
                val outcome = LosslessReminders.renew(provider)
                TrackLog.d("Laya", "background renewal of ${provider.label}: ${outcome::class.simpleName}")
                if (outcome is LosslessReminders.Outcome.Failed) ok = false
            }
            if (isActive) task.setTaskCompletedWithSuccess(ok)
        }
        task.expirationHandler = {
            job.cancel()
            task.setTaskCompletedWithSuccess(false)
        }
    }
}
