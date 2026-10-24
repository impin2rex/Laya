package com.music.laya.ios

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.music.laya.data.DebugLog
import com.music.laya.data.TrackLog
import com.music.laya.data.innertube.InnerTubeXResolver
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController

/** Entry point called from Swift: hosts the UI with the Swift-side AVFoundation [engine]. */
fun MainViewController(engine: AudioEngine): UIViewController {
    // Not NSLog: Kotlin strings passed through its C varargs crash (EXC_BAD_ACCESS).
    DebugLog.sink = DebugLog.Sink { level, tag, message, error -> println("$tag/$level: $message ${error ?: ""}") }
    TrackLog.echo = { level, tag, message, error -> println("$tag/$level: $message ${error ?: ""}") }
    SingletonImageLoader.setSafe { ctx -> ImageLoader.Builder(ctx).components { add(KtorNetworkFetcherFactory()) }.build() }
    val dir = NSFileManager.defaultManager
        .URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first().let { (it as platform.Foundation.NSURL).path!! }
    InnerTubeXResolver.init(
        filesDir = dir.toPath(),
        // ponytail: in-memory, so cached player config is refetched each launch; NSUserDefaults when that hurts.
        store = object : InnerTubeXResolver.ConfigStore {
            private val m = mutableMapOf<String, Any>()
            override fun getString(key: String) = m[key] as? String
            override fun getLong(key: String) = m[key] as? Long
            override fun putString(key: String, value: String) { m[key] = value }
            override fun putLong(key: String, value: Long) { m[key] = value }
        },
        poTokenProvider = null,
    )
    return ComposeUIViewController { MaterialTheme { Surface(Modifier.fillMaxSize()) { DebugPlayerScreen(engine) } } }
}
