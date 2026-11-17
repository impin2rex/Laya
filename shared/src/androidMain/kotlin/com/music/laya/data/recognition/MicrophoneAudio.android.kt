package com.music.laya.data.recognition

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

// The RECORD_AUDIO check is the caller's: without it AudioRecord comes up
// uninitialised (or throws), which is reported as a denied permission below.
// Recording takes no audio focus, so whatever is playing — on this phone too — carries on.
@SuppressLint("MissingPermission")
actual fun microphoneAudio(): Flow<ShortArray> = flow {
    val bufferBytes = AudioRecord.getMinBufferSize(
        RECOGNITION_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
    ).coerceAtLeast(8_192)
    val record = try {
        AudioRecord(
            MediaRecorder.AudioSource.MIC, RECOGNITION_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes,
        )
    } catch (e: SecurityException) {
        throw AudioCaptureException(permissionDenied = true, "RECORD_AUDIO not granted")
    }
    try {
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            throw AudioCaptureException(permissionDenied = true, "AudioRecord did not initialise")
        }
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            // Another app holds the microphone (a call, a recorder).
            throw AudioCaptureException(permissionDenied = false, "microphone busy")
        }
        val buffer = ShortArray(bufferBytes / 2)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = record.read(buffer, 0, buffer.size)
            if (read < 0) throw AudioCaptureException(permissionDenied = false, "AudioRecord.read = $read")
            if (read > 0) emit(buffer.copyOf(read))
        }
    } finally {
        runCatching { record.stop() }
        record.release()
    }
}.flowOn(Dispatchers.IO)
