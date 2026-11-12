package com.music.laya.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.laya.data.Hinglish
import com.music.laya.data.VoiceLanguages
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import java.util.prefs.Preferences
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.swing.SwingUtilities

/**
 * The search field's mic on the desktop, each OS through its own recognizer:
 * macOS through Apple's Speech framework (what Siri and Dictation use), with
 * words appearing as they're heard; Windows through its own recognizer the same
 * way (laya_speech_win.cpp), or its voice typing (Win+H) where that can't hear the
 * language; Linux, which has none, through Vosk — English built in, other
 * languages' models downloaded when picked — also word by word. Draws nothing where
 * the native half didn't build.
 */
@Composable
internal fun DesktopVoiceButton(
    focusRequester: FocusRequester,
    onPartial: (String) -> Unit,
    onResult: (String) -> Unit,
) {
    if (!DesktopVoice.available) return
    val prefs = remember { Preferences.userRoot().node("com/music/laya/voice") }
    var language by remember { mutableStateOf(prefs.get("language", null)) }
    var listening by remember { mutableStateOf(false) }
    // macOS Auto: Hindi goes in as Hinglish, the way song titles are listed. Hindi picked by
    // hand stays in Devanagari.
    val partial by rememberUpdatedState<(String) -> Unit> { onPartial(if (DesktopPlatform.isMac && language == null) Hinglish.of(it) else it) }
    val result by rememberUpdatedState<(String) -> Unit> { onResult(if (DesktopPlatform.isMac && language == null) Hinglish.of(it) else it) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { DesktopVoice.stop() } }

    DesktopMic(
        listening = listening,
        language = language,
        onLanguageChange = { tag ->
            language = tag
            if (tag == null) prefs.remove("language") else prefs.put("language", tag)
        },
        onClick = {
            val tag = language ?: VoiceLanguages.auto(Locale.getDefault().toLanguageTag(), voiceLanguages.map { it.first })
            when {
                listening -> DesktopVoice.stop()
                DesktopVoice.hasSystemSpeech -> {
                    listening = true
                    // Auto also listens for the South Asian three, each its own system
                    // recognizer; the most confident wins (LayaSpeech.swift, laya_speech_win.cpp).
                    val tags = if (language == null) listOf(tag) + VoiceLanguages.southAsian else listOf(tag)
                    DesktopVoice.systemListen(tags) { kind, text ->
                        when (kind) {
                            0 -> partial(text)
                            1 -> { listening = false; if (text.isNotBlank()) result(text) }
                            else -> {
                                listening = false
                                // Windows without a speech pack for the language, or with online
                                // speech recognition off: its own voice typing still works.
                                if (DesktopPlatform.isWindows) {
                                    focusRequester.requestFocus()
                                    DesktopVoice.windowsVoiceTyping()
                                }
                            }
                        }
                    }
                }
                DesktopPlatform.isWindows -> {
                    focusRequester.requestFocus()
                    DesktopVoice.windowsVoiceTyping()
                }
                else -> {
                    listening = true
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            DesktopVoice.linuxListen(tag) { heard -> SwingUtilities.invokeLater { partial(heard) } }
                        }
                        listening = false
                        if (!text.isNullOrBlank()) result(text)
                    }
                }
            }
        },
    )
}


/**
 * macOS offers the phones' list, less what Apple's recognizer lacks (Bangla, among others);
 * Linux every language Vosk has a model for (no Bangla either).
 */
private val voiceLanguages: List<Pair<String, String>> =
    if (DesktopPlatform.isWindows) VoiceLanguages.all
    else if (DesktopPlatform.isMac) VoiceLanguages.all.filter { it.first.substringBefore('-') in DesktopVoice.macLanguages }
    else DesktopVoice.linuxModels.map { (tag, model) -> tag to model.label }

/** Tap listens (or stops), long press picks the language; Windows has no menu — it uses Windows' own. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesktopMic(listening: Boolean, language: String?, onLanguageChange: (String?) -> Unit, onClick: () -> Unit) {
    val downloads = DesktopVoice.downloads
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.voiceWaves(listening, DesktopAccent)) {
        Icon(
            Icons.Rounded.Mic,
            contentDescription = DesktopStrings["d_voice_search", "Voice search"],
            tint = if (listening) DesktopAccent else DesktopSecondary,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                    onLongClickLabel = DesktopStrings["d_voice_search_language", "Voice search language"],
                )
                .padding(4.dp),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            (listOf(null to DesktopStrings["d_voice_search_language_auto", "Auto"]) + voiceLanguages).forEach { (tag, name) ->
                // Linux: a language whose model isn't on this computer yet says so, and picking it
                // downloads the model. Until it's in, voice search keeps listening in English.
                val model = tag?.let(DesktopVoice::downloadable)
                val progress = tag?.let { downloads[it] }
                val label = when {
                    model == null || DesktopVoice.installed(tag) -> name
                    progress == null -> "$name · " + DesktopStrings.format("d_voice_download", model.sizeMb, fallback = "Download (%1${'$'}s MB)")
                    progress < 0f -> "$name · " + DesktopStrings["d_voice_download_failed", "Download failed, try again"]
                    else -> "$name · " + DesktopStrings.format("d_voice_downloading", (progress * 100).toInt(), fallback = "Downloading %1${'$'}s%%")
                }
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = { if (tag == language) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = {
                        if (tag != null && model != null && !DesktopVoice.installed(tag) && (progress == null || progress < 0f)) {
                            DesktopVoice.download(tag)
                        }
                        onLanguageChange(tag)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

/** Rings spreading out from the mic, like a drop in water, while it listens (as in sharedUi's VoiceMic). */
@Composable
private fun Modifier.voiceWaves(listening: Boolean, color: Color): Modifier {
    if (!listening) return this
    val phase by rememberInfiniteTransition(label = "voice").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "waves",
    )
    return drawBehind {
        repeat(3) { ring ->
            val p = (phase + ring / 3f) % 1f
            drawCircle(color.copy(alpha = 0.35f * (1f - p)), radius = size.minDimension / 2f * (0.5f + 0.9f * p))
        }
    }
}

internal object DesktopVoice {
    /** LayaSpeech.swift on macOS, laya_speech_win.cpp on Windows: the same three calls. */
    private interface SystemSpeech : Library {
        fun laya_speech_start(tags: String, callback: SpeechCallback)
        fun laya_speech_stop()
        fun laya_speech_languages(): String?
    }

    fun interface SpeechCallback : Callback {
        fun invoke(kind: Int, text: Pointer?)
    }

    /** The few calls of Vosk's C API voice search needs. */
    private interface Vosk : Library {
        fun vosk_set_log_level(level: Int)
        fun vosk_model_new(path: String): Pointer?
        fun vosk_model_free(model: Pointer)
        fun vosk_recognizer_new(model: Pointer, sampleRate: Float): Pointer?
        fun vosk_recognizer_accept_waveform(recognizer: Pointer, data: ByteArray, length: Int): Int
        fun vosk_recognizer_partial_result(recognizer: Pointer): Pointer
        fun vosk_recognizer_final_result(recognizer: Pointer): Pointer
        fun vosk_recognizer_free(recognizer: Pointer)
    }

    private val system: SystemSpeech? by lazy {
        if (!DesktopPlatform.isMac && !DesktopPlatform.isWindows) null
        else runCatching { Native.load(DesktopAnalysisRuntime.nativePath("laya_speech"), SystemSpeech::class.java) }
            .onFailure { DesktopTrackLog.log("voice search: ${it.message}") }.getOrNull()
    }

    private val vosk: Vosk? by lazy {
        if (DesktopPlatform.isMac || DesktopPlatform.isWindows) null
        else runCatching {
            Native.load(DesktopAnalysisRuntime.nativePath("vosk"), Vosk::class.java).apply { vosk_set_log_level(-1) }
        }.onFailure { DesktopTrackLog.log("voice search: ${it.message}") }.getOrNull()
    }

    /** A Vosk model: Indian English ships in the Linux package, the rest download when picked. */
    class VoskModel(val label: String, val name: String, val md5: String, val sizeMb: Int, val bundled: Boolean = false)

    /**
     * Every small model Vosk publishes (alphacephei.com/vosk/models), with the
     * hash it lists. Labels are endonyms. Vosk has no Bangla. Indian English
     * and Hindi lead; the rest follow by tag.
     */
    val linuxModels: Map<String, VoskModel> = linkedMapOf(
        "en-IN" to VoskModel("English (India)", "vosk-model-small-en-in-0.4", "62fd085f33c8c5fc01a235030e2a1fe3", 38, bundled = true),
        "hi-IN" to VoskModel("हिन्दी", "vosk-model-small-hi-0.22", "80f1265262c9a8a515f2707498e1b485", 44),
        "en-US" to VoskModel("English (US)", "vosk-model-small-en-us-0.15", "09ab50ccd62b674cbaa231b825f9c1cb", 41),
        "en-GB" to VoskModel("English (UK)", "vosk-model-small-en-gb-0.15", "6afd611b04b2b47c129c3615dc502383", 43),
        "ar" to VoskModel("العربية", "vosk-model-small-ar-0.3", "0eb578c0779f85ef039b7ee5bc1b64ed", 104),
        "ar-TN" to VoskModel("العربية (تونس)", "vosk-model-small-ar-tn-0.1-linto", "67bbe16beaa17e6cf14f8c295ee5ddd1", 166),
        "ca" to VoskModel("Català", "vosk-model-small-ca-0.4", "5dc7901815fef3dc8b784b31f309385c", 43),
        "cs" to VoskModel("Čeština", "vosk-model-small-cs-0.4-rhasspy", "df5ddac5ee8632f6b46a6fc751953286", 46),
        "de" to VoskModel("Deutsch", "vosk-model-small-de-0.15", "4f21f92c0897b48287ef8839420608eb", 46),
        "es" to VoskModel("Español", "vosk-model-small-es-0.42", "2d5c94f9859a84881a0ef744738ebd31", 40),
        "eo" to VoskModel("Esperanto", "vosk-model-small-eo-0.42", "3e9319ca789fa06fd3efa68d51c85d6b", 44),
        "fa" to VoskModel("فارسی", "vosk-model-small-fa-0.42", "cc2b18af256ffab2c44055f6a02ecb3d", 53),
        "fr" to VoskModel("Français", "vosk-model-small-fr-0.22", "8873b1234503f6edd55f54bfff31cf3e", 42),
        "gu" to VoskModel("ગુજરાતી", "vosk-model-small-gu-0.42", "4595a6f0cc0c88fee6eec7d80496fc10", 108),
        "it" to VoskModel("Italiano", "vosk-model-small-it-0.22", "fbd8f9c72cbb8c3dfa3e4581bd3585f4", 50),
        "ja" to VoskModel("日本語", "vosk-model-small-ja-0.22", "0e3163dd62dfb0d823353718ac3cbf79", 50),
        "ka" to VoskModel("ქართული", "vosk-model-small-ka-0.42", "7e95b6a69b60a5f1c45e29d1cd2ac353", 46),
        "kk" to VoskModel("Қазақ тілі", "vosk-model-small-kz-0.42", "1c7dc487ac5e0c24acfa7566c106ba2d", 60),
        "ko" to VoskModel("한국어", "vosk-model-small-ko-0.22", "fa8029a173787a159e0e72fe6135f890", 87),
        "ky" to VoskModel("Кыргызча", "vosk-model-small-ky-0.42", "541eb45589c2b6f8350905086a76f072", 51),
        "nl" to VoskModel("Nederlands", "vosk-model-small-nl-0.22", "4582e1f20d6849099da08511f9797017", 40),
        "pl" to VoskModel("Polski", "vosk-model-small-pl-0.22", "91cbbd6231320467da672be31827b6ac", 53),
        "pt" to VoskModel("Português", "vosk-model-small-pt-0.3", "458c69371c5a0b9ab6ee8fa417bf89da", 32),
        "ru" to VoskModel("Русский", "vosk-model-small-ru-0.22", "d1759dc83eb8fd87850129afbd9f4b7b", 46),
        "sv" to VoskModel("Svenska", "vosk-model-small-sv-rhasspy-0.15", "5ae431c65fe8636692118792daa22ecc", 304),
        "te" to VoskModel("తెలుగు", "vosk-model-small-te-0.42", "ae3c093fd7dd883d462983b15900ca61", 61),
        "tg" to VoskModel("Тоҷикӣ", "vosk-model-small-tg-0.22", "fca4e40de915a5bd2f30e32d60af45c0", 52),
        "tr" to VoskModel("Türkçe", "vosk-model-small-tr-0.3", "198511631860597639a0ebf779263fcf", 37),
        "uk" to VoskModel("Українська", "vosk-model-small-uk-v3-small", "3188e41b4a38369d8b9527d0149616ab", 144),
        "uz" to VoskModel("Oʻzbekcha", "vosk-model-small-uz-0.22", "4d75acfef76fe919c8fb68cd1179c182", 51),
        "vi" to VoskModel("Tiếng Việt", "vosk-model-small-vn-0.4", "b31b474a1ef75488c5fa575f8e2a1269", 34),
        "zh-CN" to VoskModel("中文", "vosk-model-small-cn-0.22", "d1a8e82933dcc7632667c036b0bb3dbb", 44),
    )

    /** The model a language would download, or null when there's nothing to download. */
    fun downloadable(tag: String): VoskModel? =
        linuxModels[tag]?.takeIf { !it.bundled && !DesktopPlatform.isMac && !DesktopPlatform.isWindows }

    /** Tag → progress 0..1 while a model downloads, -1 when it failed. */
    val downloads = androidx.compose.runtime.mutableStateMapOf<String, Float>()

    /** Models live beside the app's other unpacked files, and outlast updates. */
    private val voiceDir: Path by lazy { DesktopAnalysisRuntime.analysisHome().toPath().resolveSibling("voice") }

    private fun modelDir(model: VoskModel) = voiceDir.resolve(model.name)

    fun installed(tag: String): Boolean {
        val model = linuxModels[tag] ?: return false
        return model.bundled || Files.exists(modelDir(model).resolve(".unpacked"))
    }

    /** Fetches, checks and unpacks a model in the background; progress shows in [downloads]. */
    fun download(tag: String) {
        val model = downloadable(tag) ?: return
        downloads[tag] = 0f
        Thread {
            val zip = voiceDir.resolve("${model.name}.zip")
            runCatching {
                Files.createDirectories(voiceDir)
                val connection = URI("https://alphacephei.com/vosk/models/${model.name}.zip").toURL().openConnection()
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: (model.sizeMb * 1_000_000L)
                val digest = MessageDigest.getInstance("MD5")
                connection.getInputStream().use { input ->
                    Files.newOutputStream(zip).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            val progress = (done.toFloat() / total).coerceAtMost(0.99f)
                            SwingUtilities.invokeLater { downloads[tag] = progress }
                        }
                    }
                }
                val md5 = digest.digest().joinToString("") { "%02x".format(it) }
                check(md5 == model.md5) { "${model.name} failed its checksum" }
                unzip(zip, modelDir(model))
            }.onSuccess {
                SwingUtilities.invokeLater { downloads.remove(tag) }
            }.onFailure {
                DesktopTrackLog.log("voice model download: ${it.message}")
                SwingUtilities.invokeLater { downloads[tag] = -1f }
            }
            runCatching { Files.deleteIfExists(zip) }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Unpacks a model zip into [dir], dropping the zip's own top folder — whose
     * name doesn't always match the model's — then marks it done.
     */
    private fun unzip(zip: Path, dir: Path) {
        ZipFile(zip.toFile()).use { archive ->
            for (entry in archive.entries()) {
                val inner = entry.name.substringAfter('/', "")
                if (inner.isEmpty()) continue
                val target = dir.resolve(inner).normalize()
                check(target.startsWith(dir)) { "unexpected entry ${entry.name}" }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    archive.getInputStream(entry).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
                }
            }
        }
        Files.createFile(dir.resolve(".unpacked"))
    }

    /**
     * Loads the model to hear [tag] with: its own once installed, English
     * otherwise (including while it downloads). The bundled one is unzipped on
     * first use — Vosk reads a folder, and the jar can only carry the zip.
     * Loaded per search and freed after it (see [linuxListen]): a loaded model
     * holds ~300 MB, too much to keep for an occasional search.
     */
    private fun voskModel(lib: Vosk, tag: String): Pointer? {
        val model = linuxModels[tag]?.takeIf { installed(tag) } ?: linuxModels.getValue(BUNDLED)
        return runCatching {
            val dir = modelDir(model)
            if (!Files.exists(dir.resolve(".unpacked"))) {
                val zip = Path.of(DesktopAnalysisRuntime.modelPath("${model.name}.zip"))
                Files.createDirectories(voiceDir)
                unzip(zip, dir)
            }
            lib.vosk_model_new(dir.toString())
        }.onFailure { DesktopTrackLog.log("voice search model: ${it.message}") }.getOrNull()
    }

    val available: Boolean by lazy {
        when {
            DesktopPlatform.isWindows -> true
            DesktopPlatform.isMac -> system != null
            else -> vosk != null && javaClass.getResource("/models/vosk-model-small-en-in-0.4.zip") != null
        }
    }

    /** Windows' or Apple's own recognizer is here (Windows without it falls back to Win+H). */
    val hasSystemSpeech: Boolean get() = system != null

    /** The languages this Mac's recognizer has; everything when it can't say. */
    val macLanguages: Set<String> by lazy {
        system?.laya_speech_languages()?.split(',')?.toSet()?.takeIf { it.isNotEmpty() }
            ?: VoiceLanguages.all.map { it.first.substringBefore('-') }.toSet()
    }

    // Held so JNA's native thunk isn't collected while the native side still calls it.
    private var systemCallback: SpeechCallback? = null

    @Volatile private var stopRequested = false

    fun stop() {
        stopRequested = true
        system?.laya_speech_stop()
    }

    /** kind 0: heard so far, 1: final, 2: unavailable, 3: denied — always on the UI thread. */
    fun systemListen(tags: List<String>, onEvent: (kind: Int, text: String) -> Unit) {
        val lib = system ?: return onEvent(2, "")
        val callback = SpeechCallback { kind, text ->
            val value = text?.getString(0, "UTF-8").orEmpty()
            SwingUtilities.invokeLater { onEvent(kind, value) }
        }
        systemCallback = callback
        lib.laya_speech_start(tags.joinToString(","), callback)
    }

    /**
     * Streams the mic into Vosk, handing each new hearing to [onPartial], until
     * Vosk hears the end of what was said, the user taps stop, nothing is said
     * for [FIRST_WORD_SECONDS], or [MAX_SECONDS] pass. Returns the transcript.
     */
    fun linuxListen(tag: String, onPartial: (String) -> Unit): String? {
        stopRequested = false
        val lib = vosk ?: return null
        val model = voskModel(lib, tag) ?: return null
        val recognizer = lib.vosk_recognizer_new(model, SAMPLE_RATE.toFloat())
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val line = recognizer?.let {
            runCatching { AudioSystem.getTargetDataLine(format).apply { open(format); start() } }
                .onFailure { DesktopTrackLog.log("voice search mic: ${it.message}") }.getOrNull()
        }
        if (recognizer == null || line == null) {
            recognizer?.let(lib::vosk_recognizer_free)
            lib.vosk_model_free(model)
            return null
        }
        val chunk = ByteArray(SAMPLE_RATE / 10 * 2) // 100 ms
        var heard = ""
        var elapsed = 0
        try {
            while (!stopRequested && elapsed < MAX_SECONDS * 10) {
                val read = line.read(chunk, 0, chunk.size)
                elapsed++
                val endOfUtterance = lib.vosk_recognizer_accept_waveform(recognizer, chunk, read) != 0
                if (endOfUtterance && heard.isNotBlank()) break
                val partial = voskText(lib.vosk_recognizer_partial_result(recognizer))
                if (partial.isNotBlank() && partial != heard) {
                    heard = partial
                    onPartial(partial)
                }
                if (heard.isBlank() && elapsed >= FIRST_WORD_SECONDS * 10) break
            }
            return voskText(lib.vosk_recognizer_final_result(recognizer)).ifBlank { heard }.trim()
        } finally {
            line.stop()
            line.close()
            lib.vosk_recognizer_free(recognizer)
            // The ~300 MB goes back as soon as the search has its words.
            lib.vosk_model_free(model)
        }
    }

    /** Vosk answers `{ "partial" : "…" }` or `{ "text" : "…" }`; this is the "…". */
    private fun voskText(json: Pointer): String =
        Regex("\"(?:text|partial)\"\\s*:\\s*\"(.*)\"").find(json.getString(0, "UTF-8"))?.groupValues?.get(1).orEmpty()

    /** Win+H, after focus has had a moment to land in the field it should type into. */
    fun windowsVoiceTyping() {
        Thread {
            runCatching {
                val robot = java.awt.Robot()
                robot.delay(150)
                robot.keyPress(java.awt.event.KeyEvent.VK_WINDOWS)
                robot.keyPress(java.awt.event.KeyEvent.VK_H)
                robot.keyRelease(java.awt.event.KeyEvent.VK_H)
                robot.keyRelease(java.awt.event.KeyEvent.VK_WINDOWS)
            }
        }.start()
    }

    private const val BUNDLED = "en-IN"
    private const val SAMPLE_RATE = 16_000
    private const val MAX_SECONDS = 10
    private const val FIRST_WORD_SECONDS = 5
}
