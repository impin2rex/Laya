package com.music.laya.data.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import com.music.laya.data.DebugLog as Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android's half of [SpotifyToken]: loads the real web player in an offscreen
 * WebView with the listener's cookie applied and reads the token it mints for
 * itself by hooking fetch/XHR before its own bundle runs. Signing the request
 * ourselves isn't enough — see the note in [SpotifyToken].
 */
internal object AndroidSpotifyHarvest {

    private const val TAG = "SpotifyToken"
    private const val BRIDGE_NAME = "LayaSpotifyTokenBridge"
    private const val HARVEST_TIMEOUT_MS = 20_000L

    /** Wires [SpotifyToken] to this harvester; called once from the Application. */
    fun install(context: Context) {
        val app = context.applicationContext
        SpotifyToken.os = "android"
        SpotifyToken.osVersion = android.os.Build.VERSION.RELEASE.orEmpty()
        SpotifyToken.harvester = { cookie -> withContext(Dispatchers.Main) { harvestViaWebView(app, cookie) } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun harvestViaWebView(context: Context, cookie: String): SpotifyToken.Harvested? {
        val deferred = CompletableDeferred<SpotifyToken.Harvested?>()

        val cookieManager = CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie("https://open.spotify.com/", "sp_dc=$cookie; Domain=.spotify.com; Path=/; Secure")
            setCookie("https://accounts.spotify.com/", "sp_dc=$cookie; Domain=.spotify.com; Path=/; Secure")
            flush()
        }
        // The player caches its token in web storage and skips a fresh
        // /api/token request if a live one is already sitting there, leaving
        // the hook with nothing to see — wipe storage so every harvest forces
        // a real mint.
        runCatching { WebStorage.getInstance().deleteAllData() }

        val hookScript = SpotifyToken.hookScript("$BRIDGE_NAME.onTokenPayload")
        var webView: WebView? = null
        return try {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = CANVAS_UA
                cookieManager.setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(TokenBridge(deferred), BRIDGE_NAME)

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view.evaluateJavascript(hookScript, null)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        // Re-assert in case the player navigated client-side
                        // after the onPageStarted injection ran.
                        view.evaluateJavascript(hookScript, null)
                    }
                }
                loadUrl("https://open.spotify.com/")
            }

            withTimeoutOrNull(HARVEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            Log.w(TAG, "token harvest threw: ${e.message}")
            null
        } finally {
            runCatching {
                webView?.removeJavascriptInterface(BRIDGE_NAME)
                webView?.stopLoading()
                webView?.destroy()
            }
        }
    }

    /** Receives raw `/api/token` response bodies from the hooked page. */
    private class TokenBridge(private val deferred: CompletableDeferred<SpotifyToken.Harvested?>) {
        @JavascriptInterface
        fun onTokenPayload(payload: String?) {
            if (deferred.isCompleted) return
            SpotifyToken.parseHarvest(payload)?.let(deferred::complete)
        }
    }
}
