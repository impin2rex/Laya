package com.music.laya.ios

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.canvas.SpotifyToken
import com.music.laya.data.spotify.SpotifyLibrary
import com.music.laya.sharedui.resources.Res
import com.music.laya.sharedui.resources.close
import com.music.laya.sharedui.resources.spotify_sign_in
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookieExpires
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieSecure
import platform.Foundation.NSHTTPCookieValue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.distantPast
import platform.UIKit.UIApplication
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.WKWebsiteDataTypeIndexedDBDatabases
import platform.WebKit.WKWebsiteDataTypeLocalStorage
import platform.WebKit.WKWebsiteDataTypeSessionStorage
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// Same as Android's SpotifyLoginScreen.
private const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
private val LOGIN_HOST_SUFFIXES = listOf("spotify.com", "scdn.co", "google.com", "gstatic.com", "facebook.com", "apple.com")

/** The browser UA the web player is loaded with; the same as the shared canvas code's. */
private const val SPOTIFY_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

private const val BRIDGE = "layaSpotifyToken"

private class NavDelegate(val onFinished: (String?) -> Unit) : NSObject(), WKNavigationDelegateProtocol {
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) = onFinished(webView.URL?.absoluteString)

    // Spotify's own pages and the identity providers its sign-in hands off to; anything else is not part of logging in.
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction, decisionHandler: (WKNavigationActionPolicy) -> Unit) {
        val url = decidePolicyForNavigationAction.request.URL
        val host = url?.host?.lowercase()
        val ok = decidePolicyForNavigationAction.targetFrame?.mainFrame != true ||
            (url?.scheme == "https" && host != null && LOGIN_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") })
        decisionHandler(if (ok) WKNavigationActionPolicy.WKNavigationActionPolicyAllow else WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
    }
}

private class TokenHandler(val onPayload: (String) -> Unit) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
        (didReceiveScriptMessage.body as? String)?.let(onPayload)
    }
}

/**
 * Spotify session on iOS: the `sp_dc` cookie lives in the Keychain only, and the bearer / client tokens the web
 * player mints from it are held in memory. Mirrors Android's SpotifyToken (offscreen web-player harvest).
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal object IosSpotify {
    private const val KEY = "spotify_sp_dc"
    private const val HARVEST_TIMEOUT_MS = 20_000L

    /** Before the first Spotify call: Keychain -> the shared setting flow (not its prefs), and the shared token's harvester. */
    fun restore() {
        Keychain.get(KEY)?.let { AppSettings.spotifySpdcToken.value = it }
        SpotifyToken.os = "ios"
        SpotifyToken.osVersion = "18.0"
        SpotifyToken.harvester = { cookie -> withTimeoutOrNull(HARVEST_TIMEOUT_MS) { harvest(cookie) } }
        SpotifyLibrary.auth = SpotifyLibrary.Auth {
            SpotifyToken.accessToken()?.let { SpotifyLibrary.Tokens(it, SpotifyToken.clientToken()) }
        }
    }

    fun adopt(spDc: String) {
        SpotifyToken.invalidate()
        Keychain.set(KEY, spDc)
        AppSettings.spotifySpdcToken.value = spDc
    }

    /** Forgets the cookie and signs the web view out of Spotify, or its jar would sign the next "Sign in" straight back in. */
    suspend fun signOut() {
        SpotifyToken.invalidate()
        Keychain.set(KEY, null)
        AppSettings.spotifySpdcToken.value = ""
        IosAuth.deleteCookies { it.trimStart('.').endsWith("spotify.com") }
    }

    /**
     * Loads the real web player offscreen with the cookie applied and reads the token it mints for itself by
     * hooking fetch/XHR before its own bundle runs (see Android's SpotifyToken for why signing it ourselves fails).
     */
    private suspend fun harvest(cookie: String): SpotifyToken.Harvested? = withContext(Dispatchers.Main) {
        val done = CompletableDeferred<SpotifyToken.Harvested?>()
        val store = WKWebsiteDataStore.defaultDataStore()
        val sp = NSHTTPCookie.cookieWithProperties(
            mapOf(
                NSHTTPCookieName to "sp_dc", NSHTTPCookieValue to cookie, NSHTTPCookieDomain to ".spotify.com",
                NSHTTPCookiePath to "/", NSHTTPCookieSecure to "TRUE", NSHTTPCookieExpires to NSDate.dateWithTimeIntervalSinceNow(31_536_000.0),
            ),
        )
        if (sp != null) suspendCoroutine { c -> store.httpCookieStore.setCookie(sp) { c.resume(Unit) } }
        // The player skips minting when a live token is in web storage, leaving the hook nothing to see.
        suspendCoroutine { c ->
            store.removeDataOfTypes(
                setOf(WKWebsiteDataTypeLocalStorage, WKWebsiteDataTypeSessionStorage, WKWebsiteDataTypeIndexedDBDatabases),
                NSDate.distantPast,
            ) { c.resume(Unit) }
        }
        val handler = TokenHandler { payload -> SpotifyToken.parseHarvest(payload)?.let { done.complete(it) } }
        val controller = WKUserContentController().apply {
            addUserScript(WKUserScript(SpotifyToken.hookScript("window.webkit.messageHandlers.$BRIDGE.postMessage"), WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart, false))
            addScriptMessageHandler(handler, BRIDGE)
        }
        val view = WKWebView(
            CGRectMake(0.0, 0.0, 1.0, 1.0),
            WKWebViewConfiguration().apply { websiteDataStore = store; userContentController = controller },
        ).apply { customUserAgent = SPOTIFY_UA; alpha = 0.01; userInteractionEnabled = false }
        // Attached so WebKit does not suspend it; 1pt and near-transparent.
        UIApplication.sharedApplication.keyWindow?.addSubview(view)
        try {
            view.loadRequest(NSURLRequest.requestWithURL(NSURL.URLWithString("https://open.spotify.com/")!!))
            done.await()
        } finally {
            controller.removeScriptMessageHandlerForName(BRIDGE)
            view.stopLoading()
            view.removeFromSuperview()
        }
    }
}

/** Full-screen Spotify sign-in: the real accounts page, closing itself once it lands on open.spotify.com with an `sp_dc` cookie. */
@Composable
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal fun IosSpotifyLoginScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sent = false
    val delegate = remember {
        NavDelegate { url ->
            if (!sent && url?.startsWith("https://open.spotify.com") == true) scope.launch {
                val token = IosAuth.cookieHeader("open.spotify.com")?.split(";")?.map { it.trim() }
                    ?.firstOrNull { it.startsWith("sp_dc=") }?.substringAfter("=")?.takeIf { it.isNotBlank() }
                if (token != null && !sent) { sent = true; IosSpotify.adopt(token); onClose() }
            }
        }
    }
    val webView = remember {
        WKWebView(
            frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
            configuration = WKWebViewConfiguration().apply { websiteDataStore = WKWebsiteDataStore.defaultDataStore() },
        ).also { it.customUserAgent = SAFARI_AGENT; it.navigationDelegate = delegate }
    }
    LaunchedEffect(Unit) { webView.loadRequest(NSURLRequest.requestWithURL(NSURL.URLWithString(LOGIN_URL)!!)) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(Res.string.close)) }
                Text(stringResource(Res.string.spotify_sign_in), style = MaterialTheme.typography.titleMedium)
            }
            UIKitView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }
}
