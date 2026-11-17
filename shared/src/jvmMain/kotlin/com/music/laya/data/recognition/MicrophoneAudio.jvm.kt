package com.music.laya.data.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException

/** The default capture line via Java Sound (CoreAudio, ALSA/PulseAudio/PipeWire, WASAPI), resampled by the mixer to 16 kHz. */
actual fun microphoneAudio(): Flow<ShortArray> = flow {
    val format = AudioFormat(RECOGNITION_SAMPLE_RATE.toFloat(), 16, 1, true, false)
    val line = try {
        AudioSystem.getTargetDataLine(format).apply { open(format) }
    } catch (e: LineUnavailableException) {
        throw AudioCaptureException(permissionDenied = false, e.message ?: "no capture line")
    } catch (e: IllegalArgumentException) {
        throw AudioCaptureException(permissionDenied = false, "no microphone")
    } catch (e: SecurityException) {
        throw AudioCaptureException(permissionDenied = true, e.message ?: "denied")
    }
    try {
        line.start()
        val bytes = ByteArray(3_200) // 100 ms
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = line.read(bytes, 0, bytes.size)
            if (read <= 0) continue
            emit(ShortArray(read / 2) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() })
        }
    } finally {
        line.stop()
        line.close()
    }
}.flowOn(Dispatchers.IO)
