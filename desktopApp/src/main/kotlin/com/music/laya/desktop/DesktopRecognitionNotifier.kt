package com.music.laya.desktop

import com.music.laya.data.recognition.MusicRecognizer
import com.music.laya.data.recognition.RecognitionError
import com.music.laya.data.recognition.RecognitionState
import com.music.laya.desktop.notify.Notifications
import com.music.laya.sharedui.resources.Res
import com.music.laya.sharedui.resources.recognize_error_microphone
import com.music.laya.sharedui.resources.recognize_error_network
import com.music.laya.sharedui.resources.recognize_error_permission
import com.music.laya.sharedui.resources.recognize_error_service
import com.music.laya.sharedui.resources.recognize_not_found
import com.music.laya.sharedui.resources.recognize_title
import com.music.laya.sharedui.resources.recognize_try_again
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.jetbrains.compose.resources.getString

/**
 * The result of a recognition that finished while Laya's window wasn't in front,
 * as a system notification: the song, or "couldn't recognize" with Try again.
 * Clicking it brings the window up on the Recognize page ([requests]).
 *
 * macOS through native/notify/LayaNotify.swift (inside Laya.app only), Windows
 * through native/notify/laya_notify_win.cpp (installed copies, which have the
 * Start-menu shortcut it needs) or else a tray message, which has no buttons —
 * Try again is then the page's own. Linux through the freedesktop server.
 */
internal object DesktopRecognitionNotifier {
    private val _requests = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)

    /** true: listen again; false: just show the page. */
    val requests: SharedFlow<Boolean> = _requests

    /** A Windows tray message just went up, so the next tray activation is a click on it. */
    @Volatile private var trayMessageAt = 0L

    /** Watches recognition for as long as the caller's coroutine runs. */
    suspend fun watch() {
        var wasActive = false
        MusicRecognizer.state.collect { state ->
            if (wasActive && !state.isActive && !windowInFront()) {
                runCatching { post(state) }.onFailure { DesktopTrackLog.log("recognition notification: ${it.message}") }
            }
            wasActive = state.isActive
        }
    }

    /** For the tray's activation: true (once) when it was a click on our message. */
    fun consumeTrayClick(): Boolean {
        val recent = System.currentTimeMillis() - trayMessageAt < TRAY_CLICK_WINDOW_MS
        trayMessageAt = 0L
        return recent
    }

    private fun windowInFront() =
        DesktopWindowVisibility.visible.value && DesktopWindowVisibility.window?.isFocused == true

    private suspend fun post(state: RecognitionState) {
        val (title, body) = when (state) {
            is RecognitionState.Recognized -> state.track.title to state.track.artist
            RecognitionState.NotRecognized -> getString(Res.string.recognize_title) to getString(Res.string.recognize_not_found)
            is RecognitionState.Failed -> getString(Res.string.recognize_title) to getString(
                when (state.error) {
                    RecognitionError.MICROPHONE_PERMISSION -> Res.string.recognize_error_permission
                    RecognitionError.MICROPHONE_UNAVAILABLE -> Res.string.recognize_error_microphone
                    RecognitionError.NETWORK -> Res.string.recognize_error_network
                    RecognitionError.SERVICE -> Res.string.recognize_error_service
                },
            )
            else -> return
        }
        val retry = if (state is RecognitionState.Recognized) null else getString(Res.string.recognize_try_again)
        when {
            native != null -> native?.laya_notify_post(title, body, retry, nativeCallback)
            DesktopPlatform.isLinux -> linux(title, body, retry)
            DesktopPlatform.isWindows -> if (DesktopAwtTray.notify(title, body)) trayMessageAt = System.currentTimeMillis()
        }
    }

    // ---- macOS and Windows: native/notify ----

    private interface NativeNotify : Library {
        fun laya_notify_available(): Boolean
        fun laya_notify_post(title: String, body: String, retryLabel: String?, callback: NotifyCallback)
    }

    fun interface NotifyCallback : Callback {
        fun invoke(action: Int)
    }

    /** Held for good: native code calls it whenever a notification is clicked. */
    private val nativeCallback = NotifyCallback { action -> _requests.tryEmit(action == 1) }

    /** Null on Linux, and where the library can't notify (outside Laya.app; a Windows copy with no shortcut). */
    private val native: NativeNotify? by lazy {
        if (!DesktopPlatform.isMac && !DesktopPlatform.isWindows) return@lazy null
        runCatching {
            // The texts go over as UTF-8, which both sides read: JNA's default on Windows is the ANSI code page.
            Native.load(
                DesktopAnalysisRuntime.nativePath("laya_notify"),
                NativeNotify::class.java,
                mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"),
            )
        }
            .onFailure { DesktopTrackLog.log("notifications: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.laya_notify_available() }
    }

    // ---- Linux ----

    private var bus: DBusConnection? = null
    private var shown: UInt32? = null

    @Synchronized
    private fun linux(title: String, body: String, retry: String?) {
        val connection = bus ?: DBusConnectionBuilder.forSessionBus().withShared(false).build().also { connection ->
            connection.addSigHandler(Notifications.ActionInvoked::class.java) { signal ->
                if (signal.id == shown) _requests.tryEmit(signal.actionKey == ACTION_RETRY)
            }
            bus = connection
        }
        val server = connection.getRemoteObject(Notifications.BUS_NAME, Notifications.OBJECT_PATH, Notifications::class.java)
        val actions = buildList {
            add("default")
            add("")
            if (retry != null) {
                add(ACTION_RETRY)
                add(retry)
            }
        }
        shown = server.Notify(
            "Laya", shown ?: UInt32(0), "audio-x-generic", title, body,
            actions, emptyMap<String, Variant<*>>(), -1,
        )
    }

    private const val ACTION_RETRY = "retry"
    private const val TRAY_CLICK_WINDOW_MS = 30_000L
}
