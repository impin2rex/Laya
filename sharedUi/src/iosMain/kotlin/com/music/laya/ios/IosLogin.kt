package com.music.laya.ios

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.music.laya.auth.normalizeDataSyncId
import com.music.laya.sharedui.resources.Res
import com.music.laya.sharedui.resources.checking
import com.music.laya.sharedui.resources.close
import com.music.laya.sharedui.resources.profile_unavailable
import com.music.laya.sharedui.resources.sign_in_youtube_music
import com.music.laya.sharedui.resources.switch_profile_hint
import com.music.laya.sharedui.resources.use_this_profile
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.jetbrains.compose.resources.stringResource
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val MUSIC_HOST = "music.youtube.com"

// Same URL as Android's YtMusicLoginScreen.
private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin" +
        "?ltmpl=music&service=youtube&prompt=select_account" +
        "&continue=https%3A%2F%2Fmusic.youtube.com%2F"

// Google rejects embedded browsers; Mobile Safari's agent is what makes its login page usable (proven in BitChord-iOS).
internal const val SAFARI_AGENT =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/18.7 Mobile/15E148 Safari/604.1"

// Android's probe, stringified so WebKit returns a String rather than an NSDictionary.
private const val YTCFG_PROBE = """
JSON.stringify((function () {
  try {
    if (!window.ytcfg || !window.ytcfg.get) return null;
    var get = function (key) {
      var value = window.ytcfg.get(key);
      return (value === undefined || value === null || value === '') ? null : String(value);
    };
    return {
      loggedIn: String(!!window.ytcfg.get('LOGGED_IN')),
      pageId: get('DELEGATED_SESSION_ID'),
      dataSyncId: get('DATASYNC_ID'),
      authUser: get('SESSION_INDEX'),
      visitorData: get('VISITOR_DATA'),
      clientVersion: get('INNERTUBE_CLIENT_VERSION')
    };
  } catch (e) {
    return null;
  }
})())
"""

private class PageDelegate(val onFinished: (String?) -> Unit) : NSObject(), WKNavigationDelegateProtocol {
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        onFinished(webView.URL?.absoluteString)
    }
}

/** Full-screen Google sign-in. Like Android: the user signs in, lands on YT Music, then confirms the profile shown. */
@Composable
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal fun IosLoginScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pageReady by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val delegate = remember { PageDelegate { url -> pageReady = url?.startsWith("https://$MUSIC_HOST") == true } }
    val webView = remember {
        WKWebView(
            frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
            configuration = WKWebViewConfiguration().apply { websiteDataStore = WKWebsiteDataStore.defaultDataStore() },
        ).also { it.customUserAgent = SAFARI_AGENT; it.navigationDelegate = delegate }
    }
    // Forget the previous Google login first, or the page waves straight through as the last account.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        IosAuth.clearGoogleCookies()
        webView.loadRequest(NSURLRequest.requestWithURL(NSURL.URLWithString(LOGIN_URL)!!))
    }

    fun confirm() {
        failed = false
        checking = true
        scope.launch {
            val session = capture(webView)
            val ok = session != null && IosAuth.adopt(session)
            checking = false
            if (ok) onClose() else failed = true
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(Res.string.close)) }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.sign_in_youtube_music), style = MaterialTheme.typography.titleMedium)
                    if (pageReady) {
                        Text(
                            stringResource(if (failed) Res.string.profile_unavailable else Res.string.switch_profile_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
                if (pageReady) {
                    TextButton(onClick = { confirm() }, enabled = !checking) {
                        Text(stringResource(if (checking) Res.string.checking else Res.string.use_this_profile))
                    }
                }
            }
            UIKitView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The session as the live page has it, or null while mid-login / signed out. */
private suspend fun capture(view: WKWebView): WebSession? {
    val cookies = IosAuth.cookieHeader(MUSIC_HOST)?.takeIf(IosAuth::hasApiSid) ?: return null
    val raw = suspendCoroutine<String?> { c -> view.evaluateJavaScript(YTCFG_PROBE) { r, _ -> c.resume(r as? String) } }
    val config = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
    if ((config["loggedIn"] as? JsonPrimitive)?.content != "true") return null
    fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val pageId = config.string("pageId")
    return WebSession(
        cookie = cookies,
        pageId = pageId,
        dataSyncId = pageId ?: normalizeDataSyncId(config.string("dataSyncId")),
        authUser = config.string("authUser"),
        visitorData = config.string("visitorData"),
        clientVersion = config.string("clientVersion"),
    )
}
