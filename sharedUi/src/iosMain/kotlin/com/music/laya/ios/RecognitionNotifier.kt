package com.music.laya.ios

import com.music.laya.data.recognition.MusicRecognizer
import com.music.laya.data.recognition.RecognitionError
import com.music.laya.data.recognition.RecognitionState
import com.music.laya.sharedui.resources.Res
import com.music.laya.sharedui.resources.recognize_error_microphone
import com.music.laya.sharedui.resources.recognize_error_network
import com.music.laya.sharedui.resources.recognize_error_permission
import com.music.laya.sharedui.resources.recognize_error_service
import com.music.laya.sharedui.resources.recognize_identifying
import com.music.laya.sharedui.resources.recognize_listening
import com.music.laya.sharedui.resources.recognize_not_found
import com.music.laya.sharedui.resources.recognize_title
import com.music.laya.sharedui.resources.recognize_try_again
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionForeground
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionNone
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * The result of a recognition finished while Laya wasn't on screen — listening
 * carries on in the background, the app's audio mode keeps it up — as a
 * notification: the song, or "couldn't recognize" with Try again. Tapping it
 * opens the Recognize page; Try again opens it listening (iOS starts the
 * microphone only for an app on screen). While it listens, a Live Activity.
 */
/**
 * Swift's half of the Live Activity (RecognitionLiveActivityImpl): ActivityKit has no Objective-C face, so the
 * app's Swift starts, updates and ends it. [phase] is "listening" or "identifying"; the texts arrive localized.
 */
interface RecognitionLiveActivityHost {
    fun show(phase: String, title: String, subtitle: String)
    fun end()
}

object RecognitionNotifier {
    /** Set by the app at launch; null where Live Activities don't exist. */
    var liveActivity: RecognitionLiveActivityHost? = null

    private const val CATEGORY_RETRY = "laya.recognition.retry"
    internal const val ACTION_RETRY = "retry"
    internal const val ID = "laya.recognition.result"

    private val center get() = UNUserNotificationCenter.currentNotificationCenter()
    private var installed = false

    /** The center keeps only a weak reference to its delegate. */
    private val delegate = Delegate()

    fun install() {
        if (installed) return
        installed = true
        center.delegate = delegate
        MainScope().launch {
            // Fetched before the call, never inside its arguments: Kotlin/Native turns the arguments before it into
            // autoreleased NSStrings, and the suspension drains their pool — a dangling pointer, a crash at launch.
            val retryTitle = text(Res.string.recognize_try_again)
            center.setNotificationCategories(
                setOf(
                    UNNotificationCategory.categoryWithIdentifier(
                        CATEGORY_RETRY,
                        actions = listOf(
                            UNNotificationAction.actionWithIdentifier(
                                ACTION_RETRY,
                                title = retryTitle,
                                options = UNNotificationActionOptionForeground,
                            ),
                        ),
                        intentIdentifiers = emptyList<String>(),
                        options = UNNotificationCategoryOptionNone,
                    ),
                ),
            )
            var wasActive = false
            MusicRecognizer.state.collect { state ->
                // "Listening…" / "Identifying…" on the lock screen and in the Dynamic Island, which iOS shows once
                // Laya is left. It has to start now, while Laya is in front: iOS won't start one from the background.
                when (state) {
                    RecognitionState.Listening -> liveActivity?.show(
                        "listening", text(Res.string.recognize_title), text(Res.string.recognize_listening),
                    )
                    RecognitionState.Identifying -> liveActivity?.show(
                        "identifying", text(Res.string.recognize_title), text(Res.string.recognize_identifying),
                    )
                    // The outcome is the notification's: it pops up, which a Live Activity can't do silently.
                    else -> if (wasActive) liveActivity?.end()
                }
                if (state.isActive && !wasActive) {
                    // Asked once, on the first listen: by the time a result needs it the app may be in the background.
                    center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ -> }
                    center.removeDeliveredNotificationsWithIdentifiers(listOf(ID))
                }
                if (wasActive && !state.isActive && !onScreen()) post(state)
                wasActive = state.isActive
            }
        }
    }

    /**
     * Loaded off the main thread. Compose's stringResource blocks the main thread on the same resource cache; a load
     * begun here on Main can't finish while it waits: a deadlock, and iOS kills the app as hung.
     */
    private suspend fun text(res: StringResource) = withContext(Dispatchers.Default) { getString(res) }

    private fun onScreen() = UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive

    private suspend fun post(state: RecognitionState) {
        val content = UNMutableNotificationContent()
        when (state) {
            is RecognitionState.Recognized -> {
                content.setTitle(state.track.title)
                content.setBody(state.track.artist)
            }
            RecognitionState.NotRecognized -> {
                content.setTitle(text(Res.string.recognize_title))
                content.setBody(text(Res.string.recognize_not_found))
                content.setCategoryIdentifier(CATEGORY_RETRY)
            }
            is RecognitionState.Failed -> {
                content.setTitle(text(Res.string.recognize_title))
                content.setBody(
                    text(
                        when (state.error) {
                            RecognitionError.MICROPHONE_PERMISSION -> Res.string.recognize_error_permission
                            RecognitionError.MICROPHONE_UNAVAILABLE -> Res.string.recognize_error_microphone
                            RecognitionError.NETWORK -> Res.string.recognize_error_network
                            RecognitionError.SERVICE -> Res.string.recognize_error_service
                        },
                    ),
                )
                content.setCategoryIdentifier(CATEGORY_RETRY)
            }
            else -> return
        }
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(ID, content, trigger = null), withCompletionHandler = null)
    }
}

/** Answers taps on [RecognitionNotifier]'s notifications; an Objective-C object, so not a Kotlin `object`. */
private class Delegate : NSObject(), UNUserNotificationCenterDelegateProtocol {
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        if (didReceiveNotificationResponse.notification.request.identifier == RecognitionNotifier.ID) {
            IosWidgets.perform(
                if (didReceiveNotificationResponse.actionIdentifier == RecognitionNotifier.ACTION_RETRY) "recognize" else "recognize_open",
            )
        }
        withCompletionHandler()
    }

    /** Laya on screen shows the result itself; nothing to present over it. */
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
        withCompletionHandler(0u)
    }
}
