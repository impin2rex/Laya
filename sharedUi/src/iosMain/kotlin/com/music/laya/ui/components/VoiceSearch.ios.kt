package com.music.laya.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.music.laya.data.VoiceLanguages
import com.music.laya.sharedui.resources.*
import kotlinx.cinterop.ExperimentalForeignApi
import org.jetbrains.compose.resources.stringResource
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.setActive
import platform.Foundation.NSLocale
import platform.Foundation.NSTimer
import platform.Foundation.NSUserDefaults
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognitionTaskHintSearch
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.Speech.SFSpeechRecognitionResult
import platform.Speech.SFTranscriptionSegment
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private const val KEY_LANGUAGE = "voice_search_language"

/** How long a pause ends the query, and how long to wait for a first word. */
private const val SILENCE_SECONDS = 1.5
private const val FIRST_WORD_SECONDS = 6.0

/**
 * Apple's own recognizer (Speech framework): text appears in the field as it
 * is heard, and a short pause ends the query and searches it. Tap again to
 * stop early. Apple hears one language per recognizer, so Auto runs one per
 * candidate language on the same audio and keeps the most confident.
 */
@Composable
actual fun VoiceSearchButton(onResult: (String) -> Unit, onPartial: (String) -> Unit) {
    val defaults = NSUserDefaults.standardUserDefaults
    var language by remember { mutableStateOf(defaults.stringForKey(KEY_LANGUAGE)) }
    var problem by remember { mutableStateOf<String?>(null) }
    val listener = remember { IosVoiceListener() }
    val languages = remember { appleVoiceLanguages() }
    DisposableEffect(listener) { onDispose { listener.cancel() } }

    val unavailable = stringResource(Res.string.voice_search_unavailable)
    val permission = stringResource(Res.string.voice_search_permission)

    VoiceMic(
        listening = listener.listening,
        language = language,
        languages = languages,
        onLanguageChange = {
            language = it
            if (it == null) defaults.removeObjectForKey(KEY_LANGUAGE) else defaults.setObject(it, KEY_LANGUAGE)
        },
        onClick = {
            if (listener.listening) {
                listener.finish()
            } else {
                val tag = language ?: VoiceLanguages.auto(NSLocale.currentLocale.localeIdentifier, languages.map { it.first })
                // Auto also listens for the South Asian three, as Android does.
                val tags = if (language == null) listOf(tag) + VoiceLanguages.southAsian else listOf(tag)
                listener.start(
                    tags = tags,
                    onPartial = onPartial,
                    onResult = onResult,
                    onUnavailable = { problem = unavailable },
                    onDenied = { problem = permission },
                )
            }
        },
    )

    problem?.let { message ->
        AlertDialog(
            onDismissRequest = { problem = null },
            confirmButton = { TextButton(onClick = { problem = null }) { Text("OK") } },
            text = { Text(message) },
        )
    }
}

/** The shared list, less what Apple's recognizer doesn't have on this device. */
private fun appleVoiceLanguages(): List<Pair<String, String>> {
    val supported = SFSpeechRecognizer.supportedLocales()
        .mapNotNull { (it as? NSLocale)?.localeIdentifier?.replace('_', '-')?.substringBefore('-') }
        .toSet()
    return VOICE_LANGUAGES.filter { it.first.substringBefore('-') in supported }
}

/** One language's recognition of the shared audio. */
@OptIn(ExperimentalForeignApi::class)
private class Lane {
    val request = SFSpeechAudioBufferRecognitionRequest().apply {
        shouldReportPartialResults = true
        taskHint = SFSpeechRecognitionTaskHintSearch
    }
    var task: SFSpeechRecognitionTask? = null
    var text = ""
    var confidence = 0f
    var done = false
}

/** Everything here runs on the main queue. */
@OptIn(ExperimentalForeignApi::class)
private class IosVoiceListener {
    var listening by mutableStateOf(false)
        private set

    private val engine = AVAudioEngine()
    private var lanes: List<Lane> = emptyList()
    private var finishing = false
    private var timer: NSTimer? = null
    private var onResult: (String) -> Unit = {}

    fun start(
        tags: List<String>,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onUnavailable: () -> Unit,
        onDenied: () -> Unit,
    ) {
        val recognizers = recognizersFor(tags)
        if (recognizers.isEmpty()) return onUnavailable()
        SFSpeechRecognizer.requestAuthorization { status ->
            onMain {
                if (status != SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                    return@onMain onDenied()
                }
                AVAudioSession.sharedInstance().requestRecordPermission { granted ->
                    onMain {
                        if (!granted) onDenied()
                        else if (!begin(recognizers, onPartial, onResult)) onUnavailable()
                    }
                }
            }
        }
    }

    private fun begin(
        recognizers: List<SFSpeechRecognizer>,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
    ): Boolean {
        val session = AVAudioSession.sharedInstance()
        // Record-only silences the app's own music while listening, so the
        // recognizer hears the voice rather than the song.
        session.setCategory(AVAudioSessionCategoryRecord, mode = AVAudioSessionModeMeasurement, options = 0u, error = null)
        session.setActive(true, error = null)

        val lanes = recognizers.map { Lane() }
        val input = engine.inputNode
        input.installTapOnBus(0u, bufferSize = 1024u, format = input.outputFormatForBus(0u)) { buffer, _ ->
            buffer?.let { b -> lanes.forEach { it.request.appendAudioPCMBuffer(b) } }
        }
        engine.prepare()
        if (!engine.startAndReturnError(null)) {
            input.removeTapOnBus(0u)
            restoreSession()
            return false
        }

        this.lanes = lanes
        this.onResult = onResult
        finishing = false
        listening = true
        armTimer(FIRST_WORD_SECONDS)
        recognizers.zip(lanes).forEach { (recognizer, lane) ->
            lane.task = recognizer.recognitionTaskWithRequest(lane.request) { result, error ->
                onMain { heard(lane, result, error != null, onPartial) }
            }
        }
        return true
    }

    private fun heard(lane: Lane, result: SFSpeechRecognitionResult?, failed: Boolean, onPartial: (String) -> Unit) {
        if (lane !in lanes) return
        if (result != null) {
            lane.text = result.bestTranscription.formattedString
            if (result.isFinal()) {
                lane.done = true
                val confidences = result.bestTranscription.segments.mapNotNull { (it as? SFTranscriptionSegment)?.confidence }
                lane.confidence = if (confidences.isEmpty()) 0f else confidences.average().toFloat()
            } else if (!finishing && lane.text.isNotBlank()) {
                // Live words from the first language, or from whichever has any.
                if (lanes.firstOrNull { it.text.isNotBlank() } === lane) onPartial(lane.text)
                armTimer(SILENCE_SECONDS)
            }
        }
        if (failed) lane.done = true
        // One language finishing with words ends the query; one giving up
        // empty-handed leaves the others listening.
        if (lane.done && !finishing && (lane.text.isNotBlank() || lanes.all { it.done })) finish()
        if (finishing && lanes.all { it.done }) deliver()
    }

    /** Stops listening and asks every language for its final answer. */
    fun finish() {
        if (lanes.isEmpty() || finishing) return
        finishing = true
        stopAudio()
        lanes.forEach { it.request.endAudio() }
        val waiting = lanes
        // Stragglers get a moment, then the answers in hand are compared.
        NSTimer.scheduledTimerWithTimeInterval(2.0, repeats = false) { _ -> if (lanes === waiting) deliver() }
        if (lanes.all { it.done }) deliver()
    }

    /** The most confident language wins; one that never finished counts as unsure. */
    private fun deliver() {
        if (lanes.isEmpty()) return
        val heard = lanes.filter { it.text.isNotBlank() }
        val best = heard.maxByOrNull { if (it.done) it.confidence else 0f } ?: heard.firstOrNull()
        val text = best?.text?.trim().orEmpty()
        lanes.forEach { it.task?.cancel() }
        lanes = emptyList()
        finishing = false
        listening = false
        if (text.isNotEmpty()) onResult(text)
    }

    /** Leaving the screen: stop without searching. */
    fun cancel() {
        if (lanes.isEmpty()) return
        if (!finishing) stopAudio()
        lanes.forEach { it.task?.cancel() }
        lanes = emptyList()
        finishing = false
        listening = false
    }

    private fun stopAudio() {
        timer?.invalidate()
        timer = null
        engine.stop()
        engine.inputNode.removeTapOnBus(0u)
        restoreSession()
    }

    private fun armTimer(seconds: Double) {
        timer?.invalidate()
        timer = NSTimer.scheduledTimerWithTimeInterval(seconds, repeats = false) { _ -> finish() }
    }

    /** Back to what AudioEngine.swift sets up for playback. */
    private fun restoreSession() {
        AVAudioSession.sharedInstance()
            .setCategory(AVAudioSessionCategoryPlayback, mode = AVAudioSessionModeDefault, options = 0u, error = null)
    }

    /**
     * A recognizer per tag this device supports, one per language. Apple
     * names Bangla by country differently across iOS versions, so any variant
     * of a language will do.
     */
    private fun recognizersFor(tags: List<String>): List<SFSpeechRecognizer> {
        val supported = SFSpeechRecognizer.supportedLocales()
            .mapNotNull { (it as? NSLocale)?.localeIdentifier?.replace('_', '-') }
        val languages = mutableSetOf<String>()
        return tags.mapNotNull { tag ->
            val lang = tag.substringBefore('-')
            if (lang in languages) return@mapNotNull null
            val id = supported.firstOrNull { it.equals(tag, ignoreCase = true) }
                ?: supported.firstOrNull { it.startsWith("$lang-") }
                ?: return@mapNotNull null
            SFSpeechRecognizer(locale = NSLocale(localeIdentifier = id))
                .takeIf { it.isAvailable() }
                ?.also { languages += lang }
        }
    }

    private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue(), block)
}
