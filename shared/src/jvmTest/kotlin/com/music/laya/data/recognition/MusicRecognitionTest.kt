package com.music.laya.data.recognition

import com.music.laya.platform.HttpException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MusicRecognitionTest {
    private val track = RecognizedTrack(id = "1", title = "Song", artist = "Artist")

    /** Endless tone audio in 100 ms chunks, like a microphone. */
    private fun tones(): Flow<ShortArray> = flow {
        var i = 0
        while (true) {
            emit(ShortArray(1600) { (9000 * sin(2 * PI * (500 + (i / 4000) % 9 * 200) * i++ / 16000.0)).toInt().toShort() })
        }
    }

    @Test fun crc32MatchesStandardCheckValue() {
        assertEquals(0xCBF43926.toInt(), crc32("123456789".encodeToByteArray()))
    }

    @Test fun signatureIsValidShazamBlob() {
        val pcm = ShortArray(16_000 * 4) { (9000 * sin(2 * PI * (600 + it / 3000 * 150) * it / 16000.0)).toInt().toShort() }
        val sig = assertNotNull(shazamSignature(pcm))
        val bytes = kotlin.io.encoding.Base64.decode(sig.uri.substringAfter("base64,"))
        assertEquals(0xCAFE2580.toInt(), le(bytes, 0))
        assertEquals(le(bytes, 4), crc32(bytes, 8))
        assertEquals(4000, sig.sampleDurationMs)
    }

    @Test fun recognisesOnFirstHit() = runBlocking {
        var calls = 0
        val result = identify(tones()) { calls++; if (calls == 2) track else null }
        assertEquals(RecognitionState.Recognized(track), result)
        assertEquals(2, calls)
    }

    @Test fun givesUpAfterLastAttempt() = runBlocking {
        var calls = 0
        assertEquals(RecognitionState.NotRecognized, identify(tones()) { calls++; null })
        assertEquals(3, calls)
    }

    @Test fun mapsFailures() = runBlocking {
        val denied = flow<ShortArray> { throw AudioCaptureException(permissionDenied = true, "no") }
        assertEquals(RecognitionState.Failed(RecognitionError.MICROPHONE_PERMISSION), identify(denied) { null })
        assertEquals(RecognitionState.Failed(RecognitionError.NETWORK), identify(tones()) { throw HttpException("offline") })
        assertEquals(RecognitionState.Failed(RecognitionError.SERVICE), identify(tones()) { throw ShazamException(true, "429") })
    }

    @Test fun parsesShazamResponse() {
        val json = """{"matches":[{"id":"1"}],"track":{"key":"42","title":"Blinding Lights","subtitle":"The Weeknd",
            "images":{"coverart":"lo","coverarthq":"hi"},"isrc":"USUG11904206","genres":{"primary":"Pop"},
            "sections":[{"type":"SONG","metadata":[{"title":"Album","text":"After Hours"},{"title":"Released","text":"2019"}]}]}}"""
        assertEquals(
            RecognizedTrack("42", "Blinding Lights", "The Weeknd", "After Hours", "hi", "2019", "Pop", "USUG11904206", null, 5),
            Shazam.parse(json, 5),
        )
        assertEquals(null, Shazam.parse("""{"matches":[]}""", 5))
    }

    private fun le(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or (b[at + 3].toInt() shl 24)
}
