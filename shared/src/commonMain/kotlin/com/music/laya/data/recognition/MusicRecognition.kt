package com.music.laya.data.recognition

import com.music.laya.data.YtMusicRepository
import com.music.laya.data.model.SearchFilter
import com.music.laya.data.model.SearchResult
import com.music.laya.data.model.Song
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.sources.TrackMatcher
import com.music.laya.platform.HttpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/** A song Shazam identified. */
@Serializable
data class RecognizedTrack(
    /** Shazam's track key; stable across recognitions of the same recording. */
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val releaseYear: String? = null,
    val genre: String? = null,
    val isrc: String? = null,
    val shazamUrl: String? = null,
    /** Epoch millis. */
    val recognizedAt: Long = 0,
)

enum class RecognitionError { MICROPHONE_PERMISSION, MICROPHONE_UNAVAILABLE, NETWORK, SERVICE }

sealed interface RecognitionState {
    data object Idle : RecognitionState
    data object Listening : RecognitionState

    /** Still listening, but at least one sample is with Shazam. */
    data object Identifying : RecognitionState
    data class Recognized(val track: RecognizedTrack) : RecognitionState
    data object NotRecognized : RecognitionState
    data class Failed(val error: RecognitionError) : RecognitionState

    val isActive: Boolean get() = this == Listening || this == Identifying
}

/** Thrown by an audio flow that cannot record. */
class AudioCaptureException(val permissionDenied: Boolean, message: String) : Exception(message)

/** 16 kHz mono 16-bit PCM from the default microphone, in chunks, until cancelled. */
expect fun microphoneAudio(): Flow<ShortArray>

const val RECOGNITION_SAMPLE_RATE = 16_000

/**
 * The one recognition session the app has. Shared by every entry point — the
 * in-app screen, the Android tile and its service, the iOS control — so they all
 * show the same state and two taps never record twice.
 */
object MusicRecognizer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    val state: StateFlow<RecognitionState> = _state.asStateFlow()
    private var job: Job? = null

    /** Starts listening on [audio]; does nothing while a session is already running. */
    fun start(audio: Flow<ShortArray> = microphoneAudio()) {
        if (job?.isActive == true) return
        _state.value = RecognitionState.Listening
        job = scope.launch {
            // A source can go quiet without ending (a watch out of reach mid-stream);
            // give up rather than listen forever.
            val result = withTimeoutOrNull(60.seconds) {
                identify(audio, onIdentifying = { _state.value = RecognitionState.Identifying })
            } ?: RecognitionState.NotRecognized
            if (result is RecognitionState.Recognized) RecognitionHistory.add(result.track)
            _state.value = result
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = RecognitionState.Idle
    }

    /** The platform's permission prompt was refused before any recording began. */
    fun reportPermissionDenied() {
        if (!_state.value.isActive) _state.value = RecognitionState.Failed(RecognitionError.MICROPHONE_PERMISSION)
    }

    /** Back to [RecognitionState.Idle] once a result has been seen; a running session is left alone. */
    fun dismiss() {
        if (!_state.value.isActive) _state.value = RecognitionState.Idle
    }
}

/** Seconds of audio at which a sample is sent; one request each, and giving up after the last. */
private val ATTEMPT_SECONDS = intArrayOf(4, 8, 12)

/**
 * Listens on [audio] and asks Shazam at each of [ATTEMPT_SECONDS], with
 * everything heard so far, until it names the song. Recording carries on while a
 * request is out, so a miss at 4 s costs no extra listening time.
 */
internal suspend fun identify(
    audio: Flow<ShortArray>,
    onIdentifying: () -> Unit = {},
    recognize: suspend (ShazamSignature) -> RecognizedTrack? = Shazam::recognize,
): RecognitionState = try {
    coroutineScope {
        // Room for far more than a request's worth of audio, so a slow request never stalls the recorder.
        val chunks = audio.buffer(512).produceIn(this)
        val pcm = ShortArray(ATTEMPT_SECONDS.last() * RECOGNITION_SAMPLE_RATE)
        var size = 0
        var attempt = 0
        try {
            for (chunk in chunks) {
                val n = minOf(chunk.size, pcm.size - size)
                chunk.copyInto(pcm, size, 0, n)
                size += n
                if (size < ATTEMPT_SECONDS[attempt] * RECOGNITION_SAMPLE_RATE) continue
                onIdentifying()
                val track = shazamSignature(pcm.copyOf(size))?.let { recognize(it) }
                if (track != null) return@coroutineScope RecognitionState.Recognized(track)
                if (++attempt == ATTEMPT_SECONDS.size) break
            }
            RecognitionState.NotRecognized
        } finally {
            chunks.cancel()
        }
    }
} catch (e: AudioCaptureException) {
    RecognitionState.Failed(
        if (e.permissionDenied) RecognitionError.MICROPHONE_PERMISSION else RecognitionError.MICROPHONE_UNAVAILABLE,
    )
} catch (e: HttpException) {
    RecognitionState.Failed(RecognitionError.NETWORK)
} catch (e: ShazamException) {
    RecognitionState.Failed(RecognitionError.SERVICE)
}

/** Where recognition keeps its history: the app's settings store by default. */
object RecognitionStore {
    internal var read: (key: String) -> String? = { AppSettings.rawString(it) }
    internal var write: (key: String, value: String) -> Unit = { k, v -> AppSettings.putRawString(k, v) }

    /**
     * Somewhere other than [AppSettings] — the desktop, which has its own preferences
     * store and never initialises AppSettings. Call before anything is first read.
     */
    fun use(read: (key: String) -> String?, write: (key: String, value: String) -> Unit) {
        this.read = read
        this.write = write
    }
}

/**
 * The card inviting the user to put recognition in Quick Settings or Control
 * Center. Gone for good once closed, or once the tile is known to be added.
 */
object RecognitionShortcutHint {
    private const val KEY = "recognition_shortcut_hint_done"
    private val done by lazy { MutableStateFlow(RecognitionStore.read(KEY) == "1") }
    val dismissed: StateFlow<Boolean> get() = done

    fun dismiss() {
        if (done.value) return
        done.value = true
        RecognitionStore.write(KEY, "1")
    }
}

/** Recognised songs, newest first, kept in [RecognitionStore] so they survive restarts. */
object RecognitionHistory {
    private const val KEY = "recognition_history"
    private const val MAX = 200
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(RecognizedTrack.serializer())

    private val flow by lazy {
        MutableStateFlow(
            RecognitionStore.read(KEY)
                ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
                .orEmpty(),
        )
    }
    val entries: StateFlow<List<RecognizedTrack>> get() = flow

    fun add(track: RecognizedTrack) = save(listOf(track) + flow.value.filterNot { it.id == track.id })

    fun remove(track: RecognizedTrack) = save(flow.value.filterNot { it.id == track.id })

    fun clear() = save(emptyList())

    private fun save(list: List<RecognizedTrack>) {
        val capped = list.take(MAX)
        flow.value = capped
        RecognitionStore.write(KEY, json.encodeToString(serializer, capped))
    }
}

/**
 * The YouTube Music song for [track]: the exact recording when the matcher
 * finds one, else the same artist under an overlapping title (Shazam's
 * "Gale Lag Ja Na Ja (From "De Dana Dan")" is YouTube Music's "Gale Lag Ja
 * (Version 1)"), else YouTube Music's top result. Null only when the search
 * returns nothing. Playback then picks the best source for it (configured
 * lossless sources first) the same way it does for any song.
 */
suspend fun findOnLaya(track: RecognizedTrack): Song? {
    val target = TrackMatcher.Target(title = track.title, artist = track.artist, album = track.album)
    val all = mutableListOf<Song>()
    for (query in TrackMatcher.queries(target)) {
        val candidates = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
            ?.mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
            .orEmpty()
        TrackMatcher.best(candidates, target)?.let { return it }
        all += candidates
    }
    val wanted = TrackMatcher.searchableTitle(track.title, track.artist)
    fun bare(title: String) = title.lowercase().substringBefore(" (").substringBefore(" - ").trim()
    return all.firstOrNull { song ->
        val got = bare(song.title)
        got.isNotEmpty() && (wanted.contains(got) || got.contains(wanted)) && TrackMatcher.sharesArtist(track.artist, song.artist)
    } ?: all.firstOrNull()
}
