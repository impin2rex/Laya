package com.music.laya.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import com.music.laya.data.VoiceLanguages
import com.music.laya.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

private const val PREFS = "voice_search"
private const val KEY_LANGUAGE = "language"

/**
 * Hands off to the system recognizer (Google's on most phones and TVs —
 * trained on native speakers, offline-capable, and needing no microphone
 * permission of our own). Its dialog takes audio focus, so playback pauses
 * while it listens, as it does in other music apps.
 */
@Composable
actual fun VoiceSearchButton(onResult: (String) -> Unit, onPartial: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var language by remember { mutableStateOf(prefs.getString(KEY_LANGUAGE, null)) }
    val prompt = stringResource(Res.string.voice_search_prompt)
    val unavailable = stringResource(Res.string.voice_search_unavailable)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull { it.isNotBlank() }
            ?.let { onResult(it.trim()) }
    }

    VoiceMic(
        listening = false,
        language = language,
        onLanguageChange = {
            language = it
            prefs.edit { putString(KEY_LANGUAGE, it) }
        },
        onClick = {
            try {
                launcher.launch(recognizerIntent(language, prompt))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
            }
        },
    )
}

private fun recognizerIntent(language: String?, prompt: String): Intent {
    val primary = language ?: autoVoiceLanguage(Locale.getDefault().toLanguageTag())
    // Auto also listens for the South Asian three, so an English phone still
    // hears a Bangla or Hindi song name (fully on Android 14+, by device before).
    val tags = (listOf(primary) + VoiceLanguages.southAsian).distinct()
    return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        // Tuned for short queries — names, titles — rather than dictation.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primary)
        putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
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
