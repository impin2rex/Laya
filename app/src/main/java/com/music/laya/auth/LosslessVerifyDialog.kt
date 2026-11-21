package com.music.laya.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.ui.res.stringResource
import com.music.laya.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.laya.data.sources.lossless.LosslessProvider
import com.music.laya.data.sources.lossless.ZarzSession
import kotlinx.coroutines.launch

/**
 * The broker's one-time check for [provider], in a WebView. The page ends by redirecting to
 * [ZarzSession.CALLBACK] with a grant, which is caught here before Android tries to open it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LosslessVerifyDialog(provider: LosslessProvider, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf<String?>(null) }
    var finishing by remember { mutableStateOf(false) }

    fun fail(message: String?) {
        Toast.makeText(context, "${provider.label}: ${message ?: context.getString(R.string.lossless_verify_failed)}", Toast.LENGTH_LONG).show()
        onDone()
    }

    LaunchedEffect(provider) {
        runCatching { provider.session.verificationUrl() }
            .onSuccess { if (it.isEmpty()) onDone() else url = it }
            .onFailure { fail(it.message) }
    }

    fun intercept(target: String): Boolean {
        if (target.startsWith("http://") || target.startsWith("https://")) return false
        val grant = ZarzSession.grantFrom(target) ?: target.takeIf { it.startsWith("gr_") }
        if (grant != null && !finishing) {
            finishing = true
            scope.launch {
                runCatching { provider.session.completeGrant(grant) }
                    .onSuccess {
                        Toast.makeText(context, context.getString(R.string.lossless_verified_toast, provider.label), Toast.LENGTH_SHORT).show()
                        onDone()
                    }
                    .onFailure { fail(it.message) }
            }
        }
        // Any other non-web scheme (intent:, market:) is not this page's to open.
        return true
    }

    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Text(
                        stringResource(R.string.lossless_verify_title, provider.label),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                    )
                    TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterEnd)) { Text(stringResource(R.string.cancel)) }
                }
                val current = url
                if (current == null || finishing) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString = browserUserAgent(settings.userAgentString)
                                // The page's first choice: WebView drops its script-started
                                // mornye:// redirect, so it hands the grant to this bridge instead.
                                addJavascriptInterface(
                                    object {
                                        @JavascriptInterface
                                        fun postMessage(message: String) {
                                            post { intercept(message) }
                                        }
                                    },
                                    "MornyeGrant",
                                )
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                                        intercept(request.url.toString())

                                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                        if (intercept(url)) view.stopLoading()
                                    }
                                }
                                loadUrl(current)
                            }
                        },
                        onRelease = { it.destroy() },
                    )
                }
            }
        }
    }
}
