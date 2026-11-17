package com.music.laya.wear.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.music.laya.wear.WearProtocol as P

/** Where a recognition from this watch has got to. */
sealed interface Recognition {
    data object Idle : Recognition
    data object Listening : Recognition
    data object Identifying : Recognition
    data class Recognized(
        val id: String,
        val title: String,
        val artist: String,
        val art: ImageBitmap?,
        /** The phone looked for it on Laya, to play it, and it isn't there. */
        val notOnLaya: Boolean = false,
    ) : Recognition
    data object NotFound : Recognition
    data object Failed : Recognition
    data object NoPhone : Recognition
    data object NoMicrophone : Recognition

    val isActive get() = this == Listening || this == Identifying
}

/**
 * "What's playing?" from the wrist: this watch's microphone, streamed to the
 * phone over [P.PATH_RECOGNIZE], which names the song and answers with
 * [P.PATH_RECOGNITION] messages. The watch only records and shows; nothing
 * here talks to the internet.
 */
class Recognizer(context: Context, private val scope: CoroutineScope, private val link: PhoneLink) :
    MessageClient.OnMessageReceivedListener {

    private val messages = Wearable.getMessageClient(context)
    private val channels = Wearable.getChannelClient(context)

    private val _state = MutableStateFlow<Recognition>(Recognition.Idle)
    val state: StateFlow<Recognition> = _state.asStateFlow()

    private var job: Job? = null

    @SuppressLint("MissingPermission") // The screen asks for RECORD_AUDIO before calling this.
    fun start() {
        if (_state.value.isActive) return
        _state.value = Recognition.Listening
        messages.addListener(this)
        job = scope.launch(Dispatchers.IO) {
            val node = link.phone() ?: return@launch finish(Recognition.NoPhone)
            val channel = runCatching { channels.openChannel(node, P.PATH_RECOGNIZE).await() }.getOrNull()
                ?: return@launch finish(Recognition.NoPhone)
            val out = runCatching { channels.getOutputStream(channel).await() }.getOrNull()
                ?: return@launch finish(Recognition.NoPhone)
            val record = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, RATE)
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            }.getOrNull()
            if (record == null) {
                runCatching { out.close() }
                return@launch finish(Recognition.NoMicrophone)
            }
            try {
                record.startRecording()
                val samples = ShortArray(RATE / 10)
                val bytes = ByteArray(samples.size * 2)
                var sent = 0
                // Until the phone closes the channel on its answer (the write throws), or a cap.
                while (isActive && sent < RATE * MAX_SECONDS) {
                    val n = record.read(samples, 0, samples.size)
                    if (n <= 0) continue
                    for (i in 0 until n) {
                        bytes[2 * i] = samples[i].toInt().toByte()
                        bytes[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
                    }
                    out.write(bytes, 0, n * 2)
                    out.flush()
                    sent += n
                }
            } catch (_: Exception) {
                // The phone has its answer.
            } finally {
                runCatching { record.stop() }
                record.release()
                runCatching { out.close() }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        finish(Recognition.Idle)
    }

    /** Back to the start once a result has been seen. */
    fun dismiss() {
        if (!_state.value.isActive) finish(Recognition.Idle)
    }

    fun play(result: Recognition.Recognized) = link.playRecognized(result.id)

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != P.PATH_RECOGNITION) return
        val map = runCatching { DataMap.fromByteArray(event.data) }.getOrNull() ?: return
        val current = _state.value
        when (map.getString(P.KEY_STATE)) {
            P.RECOGNITION_LISTENING -> if (current.isActive) _state.value = Recognition.Listening
            P.RECOGNITION_IDENTIFYING -> if (current.isActive) _state.value = Recognition.Identifying
            P.RECOGNITION_NOT_FOUND -> if (current.isActive) finish(Recognition.NotFound)
            P.RECOGNITION_FAILED -> if (current.isActive) finish(Recognition.Failed)
            P.RECOGNITION_RECOGNIZED -> if (current.isActive) {
                val art = map.getByteArray(P.KEY_ART)
                    ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                // Kept listening for a "not on Laya" answer to Play.
                _state.value = Recognition.Recognized(
                    id = map.getString(P.KEY_RECOGNITION_ID).orEmpty(),
                    title = map.getString(P.KEY_TITLE).orEmpty(),
                    artist = map.getString(P.KEY_ARTIST).orEmpty(),
                    art = art,
                )
                job?.cancel()
            }
            P.RECOGNITION_NOT_ON_LAYA -> (current as? Recognition.Recognized)?.let { _state.value = it.copy(notOnLaya = true) }
        }
    }

    /** Stops listening for the phone, except after a song, whose Play can still be answered. */
    private fun finish(state: Recognition) {
        _state.value = state
        if (state !is Recognition.Recognized) messages.removeListener(this)
    }

    fun release() {
        job?.cancel()
        messages.removeListener(this)
    }

    companion object {
        private const val RATE = 16_000
        private const val MAX_SECONDS = 20

        /** Set by the tile; the app opens straight onto recognition and starts. */
        val requested = MutableStateFlow(false)
        const val EXTRA_RECOGNIZE = "recognize"
    }
}
