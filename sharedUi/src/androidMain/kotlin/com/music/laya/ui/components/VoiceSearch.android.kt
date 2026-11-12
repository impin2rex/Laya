package com.music.laya.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.music.laya.data.VoiceLanguages
import com.music.laya.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

private const val PREFS = "voice_search"
private const val KEY_LANGUAGE = "language"

/**
 * Listens in-app through the system recognizer (Google's on most phones and
 * TVs — trained on native speakers, offline-capable): the mic asks for its
 * own permission, words appear in the field as they're heard, and a pause
 * ends the query. Playback pauses while it listens.
 */
@Composable
actual fun VoiceSearchButton(onResult: (String) -> Unit, onPartial: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var language by remember { mutableStateOf(prefs.getString(KEY_LANGUAGE, null)) }
    var listening by remember { mutableStateOf(false) }
    val unavailable = stringResource(Res.string.voice_search_unavailable)
    val denied = stringResource(Res.string.voice_search_permission)
    val result by rememberUpdatedState(onResult)
    val partial by rememberUpdatedState(onPartial)

    val focus = remember { VoiceFocus(context) }
    val recognizer = remember {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) null
        else SpeechRecognizer.createSpeechRecognizer(context)
    }
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(bundle: Bundle?) {
                bundle.firstText()?.let { partial(it) }
            }

            override fun onResults(bundle: Bundle?) {
                listening = false
                focus.release()
                bundle.firstText()?.let { result(it) }
            }

            override fun onError(error: Int) {
                listening = false
                focus.release()
                when (error) {
                    // Nothing said, or nothing it could make out: just stop.
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_CLIENT -> Unit
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        Toast.makeText(context, denied, Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose {
            recognizer?.destroy()
            focus.release()
        }
    }

    fun listen() {
        if (recognizer == null) {
            Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        listening = true
        focus.take()
        recognizer.startListening(recognizerIntent(language))
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else Toast.makeText(context, denied, Toast.LENGTH_SHORT).show()
    }

    VoiceMic(
        listening = listening,
        language = language,
        onLanguageChange = {
            language = it
            prefs.edit { putString(KEY_LANGUAGE, it) }
        },
        onClick = {
            when {
                listening -> recognizer?.stopListening()
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED -> listen()
                else -> permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
    )
}

private fun Bundle?.firstText(): String? =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }?.trim()

/** Pauses the app's music while listening, so the recognizer hears the voice, not the song. */
private class VoiceFocus(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    fun take() { audio?.requestAudioFocus(request) }
    fun release() { audio?.abandonAudioFocusRequest(request) }
}

private fun recognizerIntent(language: String?): Intent {
    val primary = language ?: autoVoiceLanguage(Locale.getDefault().toLanguageTag())
    // Auto also listens for the South Asian three, so an English phone still
    // hears a Bangla or Hindi song name (fully on Android 14+, by device before).
    val tags = (listOf(primary) + VoiceLanguages.southAsian).distinct()
    return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        // Tuned for short queries — names, titles — rather than dictation.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primary)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (language == null) {
            // Google's recognizer also listens for these alongside the primary one.
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", (tags - primary).toTypedArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, ArrayList(tags))
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, ArrayList(tags))
            }
        }
    }
}
