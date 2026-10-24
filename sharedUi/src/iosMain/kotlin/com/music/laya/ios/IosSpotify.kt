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
import com.music.laya.data.spotify.SpotifyLibrary
import com.music.laya.platform.HttpRequest
import com.music.laya.platform.NetHttp
import com.music.laya.platform.currentTimeMillis
import com.music.laya.platform.ioDispatcher
import com.music.laya.sharedui.resources.Res
import com.music.laya.sharedui.resources.close
import com.music.laya.sharedui.resources.spotify_sign_in
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
import platform.Foundation.NSUUID
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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// Same as Android's SpotifyLoginScreen.
private const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
private val LOGIN_HOST_SUFFIXES = listOf("spotify.com", "scdn.co", "google.com", "gstatic.com", "facebook.com", "apple.com")

/** The browser UA Spotify's token and canvas endpoints expect (Android's CANVAS_UA). */
internal const val SPOTIFY_UA =
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
@OptIn(ExperimentalEncodingApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
internal object IosSpotify {
    private const val KEY = "spotify_sp_dc"
    private const val HARVEST_TIMEOUT_MS = 20_000L
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val harvestMutex = Mutex()

    private class Harvested(val token: String, val expiresAt: Long, val clientId: String?)
    private class Session(val clientVersion: String, val deviceId: String)

    private var accessToken: String? = null
    private var accessExpiresAt = 0L
    private var clientId: String? = null
    private var clientToken: String? = null
    private var clientExpiresAt = 0L
    private var session: Session? = null

    /** Before the first Spotify call: Keychain -> the shared setting flow (not its prefs), and the library's token source. */
    fun restore() {
        Keychain.get(KEY)?.let { AppSettings.spotifySpdcToken.value = it }
        SpotifyLibrary.auth = SpotifyLibrary.Auth {
            accessToken()?.let { SpotifyLibrary.Tokens(it, clientToken()) }
        }
    }

    fun adopt(spDc: String) {
        invalidate()
        Keychain.set(KEY, spDc)
        AppSettings.spotifySpdcToken.value = spDc
    }

    /** Forgets the cookie and signs the web view out of Spotify, or its jar would sign the next "Sign in" straight back in. */
    suspend fun signOut() {
        invalidate()
        Keychain.set(KEY, null)
        AppSettings.spotifySpdcToken.value = ""
        IosAuth.deleteCookies { it.trimStart('.').endsWith("spotify.com") }
    }

    private fun invalidate() {
        accessToken = null; accessExpiresAt = 0L; clientId = null; clientToken = null; clientExpiresAt = 0L
    }

    private fun nowMs() = currentTimeMillis()

    /** The web player's bearer, cached until shortly before it expires; null when signed out or the harvest failed. */
    suspend fun accessToken(): String? {
        val cookie = AppSettings.spotifySpdcToken.value
        if (cookie.isBlank()) return null
        accessToken?.let { if (nowMs() < accessExpiresAt - 30_000) return it }
        return harvestMutex.withLock {
            accessToken?.let { if (nowMs() < accessExpiresAt - 30_000) return@withLock it }
            val h = withTimeoutOrNull(HARVEST_TIMEOUT_MS) { harvest(cookie) } ?: return@withLock null
            accessToken = h.token
            accessExpiresAt = h.expiresAt
            h.clientId?.let { clientId = it }
            h.token
        }
    }

    /**
     * Loads the real web player offscreen with the cookie applied and reads the token it mints for itself by
     * hooking fetch/XHR before its own bundle runs (see Android's SpotifyToken for why signing it ourselves fails).
     */
    private suspend fun harvest(cookie: String): Harvested? = withContext(Dispatchers.Main) {
        val done = CompletableDeferred<Harvested?>()
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
        val handler = TokenHandler { payload -> parseToken(payload)?.let { done.complete(it) } }
        val controller = WKUserContentController().apply {
            addUserScript(WKUserScript(HOOK_SCRIPT, WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart, false))
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

    private fun parseToken(payload: String): Harvested? = runCatching {
        val root = json.parseToJsonElement(payload).jsonObject
        val token = root["accessToken"]?.jsonPrimitive?.contentOrNull
        // The anonymous token minted before the cookie takes effect can't read canvases; keep waiting for the real one.
        if (token.isNullOrBlank() || root["isAnonymous"]?.jsonPrimitive?.contentOrNull == "true") return null
        val expires = root["accessTokenExpirationTimestampMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?.takeIf { it > nowMs() } ?: (nowMs() + 3_600_000L)
        Harvested(token, expires, root["clientId"]?.jsonPrimitive?.contentOrNull)
    }.getOrNull()

    /**
     * The second header api-partner and spclient demand next to the bearer. Best-effort: null just means the
     * caller sends the bearer alone. Needs [accessToken] to have succeeded once (the client id comes off it).
     */
    suspend fun clientToken(): String? = withContext(ioDispatcher) {
        clientToken?.let { if (nowMs() < clientExpiresAt - 30_000) return@withContext it }
        val id = clientId ?: return@withContext null
        val s = session() ?: return@withContext null
        val payload = buildJsonObject {
            putJsonObject("client_data") {
                put("client_version", s.clientVersion)
                put("client_id", id)
                putJsonObject("js_sdk_data") {
                    put("device_brand", "unknown"); put("device_model", "unknown"); put("os", "ios")
                    put("os_version", "18.0"); put("device_id", s.deviceId); put("device_type", "smartphone")
                }
            }
        }
        val body = runCatching {
            NetHttp.execute(
                HttpRequest(
                    "https://clienttoken.spotify.com/v1/clienttoken", "POST",
                    mapOf("Accept" to "application/json", "User-Agent" to SPOTIFY_UA),
                    payload.toString().encodeToByteArray(),
                    // Bare type, no charset: clienttoken 400s on the charset form.
                    "application/json",
                ),
            ).takeIf { it.isSuccessful }?.text()
        }.getOrNull() ?: return@withContext null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@withContext null
        if (root["response_type"]?.jsonPrimitive?.contentOrNull != "RESPONSE_GRANTED_TOKEN_RESPONSE") return@withContext null
        val granted = root["granted_token"]?.jsonObject
        val token = granted?.get("token")?.jsonPrimitive?.contentOrNull ?: return@withContext null
        val ttl = granted["expires_after_seconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
        clientToken = token
        clientExpiresAt = nowMs() + ttl * 1000
        token
    }

    /** Client version and device id, both off the plain web player page; fetched once per process. */
    private fun session(): Session? {
        session?.let { return it }
        val response = runCatching {
            NetHttp.execute(HttpRequest("https://open.spotify.com", headers = mapOf("User-Agent" to SPOTIFY_UA)))
        }.getOrNull() ?: return null
        val config = Regex("""<script id="appServerConfig" type="text/plain">([^<]+)</script>""")
            .find(response.text())?.groupValues?.get(1) ?: return null
        val version = runCatching {
            json.parseToJsonElement(Base64.decode(config).decodeToString()).jsonObject["clientVersion"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: return null
        val spT = response.headers.entries.firstOrNull { it.key.equals("Set-Cookie", true) }?.value
            ?.firstNotNullOfOrNull { Regex("""sp_t=([^;]+)""").find(it)?.groupValues?.get(1) }
        return Session(version, spT ?: NSUUID().UUIDString).also { session = it }
    }

    private val HOOK_SCRIPT = """
        (function () {
          if (window.__layaTokenHook) return;
          window.__layaTokenHook = true;
          var report = function (body) {
            try { window.webkit.messageHandlers.$BRIDGE.postMessage(String(body)); } catch (e) {}
          };
          var isToken = function (u) { try { return String(u).indexOf('/api/token') !== -1; } catch (e) { return false; } };
          var origFetch = window.fetch;
          if (origFetch) {
            window.fetch = function (input, init) {
              var url = (input && input.url) ? input.url : input;
              var result = origFetch.apply(this, arguments);
              if (isToken(url)) {
                try { result.then(function (res) { res.clone().text().then(report).catch(function () {}); }).catch(function () {}); } catch (e) {}
              }
              return result;
            };
          }
          var origOpen = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function (method, url) { this.__layaUrl = url; return origOpen.apply(this, arguments); };
          var origSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send = function () {
            var xhr = this;
            try { xhr.addEventListener('load', function () { if (isToken(xhr.__layaUrl)) { try { report(xhr.responseText); } catch (e) {} } }); } catch (e) {}
            return origSend.apply(this, arguments);
          };
        })();
    """.trimIndent()
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
