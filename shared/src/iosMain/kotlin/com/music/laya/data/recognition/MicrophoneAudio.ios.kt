package com.music.laya.data.recognition

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.value
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioConverterInputStatus_HaveData
import platform.AVFAudio.AVAudioConverterInputStatus_NoDataNow
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetoothA2DP
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionPortBuiltInReceiver
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.currentRoute
import platform.AVFAudio.AVAudioSessionPortOverrideNone
import platform.AVFAudio.AVAudioSessionPortOverrideSpeaker
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.otherAudioPlaying
import platform.AVFAudio.setActive
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The microphone through AVAudioEngine, converted to 16 kHz mono by
 * AVAudioConverter (a real resampler: plain decimation would fold the top of
 * the spectrum into the bands the fingerprint reads). Records alongside
 * whatever is playing — another app's music keeps going, as with Shazam, and
 * that music on this iPhone's speaker is often the very song to name.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun microphoneAudio(): Flow<ShortArray> = callbackFlow {
    val session = AVAudioSession.sharedInstance()
    val granted = suspendCoroutine { c -> session.requestRecordPermission { c.resume(it) } }
    if (!granted) throw AudioCaptureException(permissionDenied = true, "microphone permission denied")

    // Another app playing means Laya's player isn't (its Playback session isn't mixable).
    val othersPlaying = session.otherAudioPlaying
    // Mixable: Record, or Playback-and-record without this, would stop the other app's music.
    session.setCategory(
        AVAudioSessionCategoryPlayAndRecord,
        // Default, not Measurement: Measurement ignores DefaultToSpeaker, and Laya's own music, if it's playing,
        // went to the earpiece while listening.
        mode = AVAudioSessionModeDefault,
        // A2DP: music on AirPods or other Bluetooth headphones stays there at full quality, and the
        // iPhone's own mic listens (the headset mic would drop everything to call quality).
        options = AVAudioSessionCategoryOptionMixWithOthers or AVAudioSessionCategoryOptionDefaultToSpeaker or
            AVAudioSessionCategoryOptionAllowBluetoothA2DP,
        error = null,
    )
    session.setActive(true, error = null)
    // Off the earpiece only: headphones, AirPods or a car keep the music.
    fun toSpeaker() {
        val onEarpiece = session.currentRoute.outputs.any { (it as? AVAudioSessionPortDescription)?.portType == AVAudioSessionPortBuiltInReceiver }
        if (onEarpiece) session.overrideOutputAudioPort(AVAudioSessionPortOverrideSpeaker, error = null)
    }
    toSpeaker()
    fun restore() {
        session.overrideOutputAudioPort(AVAudioSessionPortOverrideNone, error = null)
        // Stepping down first: going back to Playback while still active would interrupt the
        // other app after all. With Laya's own music playing, the session stays up for it.
        if (othersPlaying) session.setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
        session.setCategory(AVAudioSessionCategoryPlayback, mode = AVAudioSessionModeDefault, options = 0u, error = null)
    }

    val engine = AVAudioEngine()
    val input = engine.inputNode
    val inFormat = input.outputFormatForBus(0u)
    val outFormat = AVAudioFormat(
        commonFormat = AVAudioPCMFormatInt16,
        sampleRate = RECOGNITION_SAMPLE_RATE.toDouble(),
        channels = 1u,
        interleaved = false,
    )
    val converter = AVAudioConverter(fromFormat = inFormat, toFormat = outFormat)
    if (inFormat.sampleRate <= 0.0) {
        restore()
        throw AudioCaptureException(permissionDenied = false, "no input device")
    }

    input.installTapOnBus(0u, bufferSize = 4096u, format = inFormat) { buffer, _ ->
        if (buffer == null) return@installTapOnBus
        val capacity = (buffer.frameLength.toDouble() * RECOGNITION_SAMPLE_RATE / inFormat.sampleRate).toUInt() + 32u
        val out = AVAudioPCMBuffer(pCMFormat = outFormat, frameCapacity = capacity)
        var fed = false
        converter.convertToBuffer(out, error = null) { _, status ->
            if (fed) {
                status?.pointed?.value = AVAudioConverterInputStatus_NoDataNow
                null
            } else {
                fed = true
                status?.pointed?.value = AVAudioConverterInputStatus_HaveData
                buffer
            }
        }
        val samples = out.int16ChannelData?.get(0) ?: return@installTapOnBus
        trySend(ShortArray(out.frameLength.toInt()) { samples[it] })
    }
    engine.prepare()
    if (!engine.startAndReturnError(null)) {
        input.removeTapOnBus(0u)
        restore()
        throw AudioCaptureException(permissionDenied = false, "audio engine did not start")
    }
    // Again once the input is live: starting it can re-route.
    toSpeaker()
    awaitClose {
        engine.stop()
        input.removeTapOnBus(0u)
        restore()
    }
}
