package com.music.laya.ios

import com.music.laya.data.sources.SourcesHost
import platform.Foundation.NSBundle

/**
 * iOS's answers for the shared source machinery. The source list lives in the Keychain (an addon's
 * address can carry its owner's token in the path, which is why Android keeps it encrypted too).
 * Only addons and YouTube exist here: modules need a JS sandbox and JioSaavn a service this build
 * doesn't carry.
 */
internal object IosSourcesHost : SourcesHost {
    override val appVersion: String
        get() = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "1.0"

    // AVPlayer has no way to be told a stream is a DASH/HLS manifest through the range loader the
    // engine feeds it, so those are passed over like any other stream it can't open. Atmos only
    // ever arrives that way (Tidal's is a DASH manifest), which is why the Dolby row stays hidden.
    override fun acceptTransport(url: String, transport: String) = false

    // What AVPlayer plays out of a plain file: FLAC and ALAC natively, plus the usual lossy ones.
    // Ogg/Opus/Vorbis/WebM are not among them. Unknown (null) is tried rather than refused.
    override fun canDecode(codec: String?) = codec == null || codec in PLAYABLE

    override var storedSources: String?
        get() = Keychain.get(KEY)
        set(value) = Keychain.set(KEY, value)

    override var jioSaavnOptInDone = true

    private const val KEY = "sources"
    private val PLAYABLE = setOf("flac", "alac", "wav", "aiff", "mp3", "aac", "he-aac", "m4a", "mp4")
}
