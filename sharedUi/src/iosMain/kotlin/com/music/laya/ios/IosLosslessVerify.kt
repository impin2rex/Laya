package com.music.laya.ios

import com.music.laya.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.music.laya.data.sources.lossless.LosslessProvider
import com.music.laya.data.sources.lossless.ZarzSession
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/** Catches the captcha page's hand-off to [ZarzSession.CALLBACK], however it is navigated to. */
private class GrantDelegate(val onCallback: (String) -> Unit) : NSObject(), WKNavigationDelegateProtocol {
    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString.orEmpty()
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:")) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        } else {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            onCallback(url)
        }
    }

    // A server redirect to an unknown scheme can surface only as a failed load.
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
        (withError.userInfo["NSErrorFailingURLStringKey"] as? String)?.let(onCallback)
    }
}

/** The page's preferred hand-off: it posts the grant to `window.MornyeGrant` when one exists. */
private class GrantBridge(val onCallback: (String) -> Unit) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
        (didReceiveScriptMessage.body as? String)?.let(onCallback)
    }
}

/** Full-screen broker check for [provider]; closes itself once verified or on failure. */
@Composable
@OptIn(ExperimentalForeignApi::class)
internal fun IosLosslessVerifyScreen(provider: LosslessProvider, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val failedText = stringResource(Res.string.lossless_verify_failed)
    val unreachableText = stringResource(Res.string.lossless_verify_unreachable)
    var status by remember { mutableStateOf("") }
    var finishing by remember { mutableStateOf(false) }
    val onCallback: (String) -> Unit = { url ->
        val grant = ZarzSession.grantFrom(url) ?: url.takeIf { it.startsWith("gr_") }
        if (grant != null && !finishing) {
            finishing = true
            status = "Finishing…"
            scope.launch {
                runCatching { provider.session.completeGrant(grant) }
                    .onSuccess { onClose() }
                    .onFailure { status = it.message ?: failedText; finishing = false }
            }
        }
    }
    val delegate = remember { GrantDelegate { onCallback(it) } }
    val bridge = remember { GrantBridge { onCallback(it) } }
    val webView = remember {
        val configuration = WKWebViewConfiguration().apply {
            userContentController.addScriptMessageHandler(bridge, "mornyeGrant")
            userContentController.addUserScript(
                WKUserScript(
                    source = "window.MornyeGrant={postMessage:function(m){window.webkit.messageHandlers.mornyeGrant.postMessage(String(m))}};",
                    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                    forMainFrameOnly = true,
                ),
            )
        }
        WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration)
            .also { it.customUserAgent = SAFARI_AGENT; it.navigationDelegate = delegate }
    }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(provider) {
        runCatching { provider.session.verificationUrl() }
            .onSuccess { url ->
                if (url.isEmpty()) onClose() else {
                    webView.loadRequest(NSURLRequest.requestWithURL(NSURL.URLWithString(url)!!))
                    ready = true
                }
            }
            .onFailure { status = it.message ?: unreachableText }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(Res.string.close)) }
                Text(stringResource(Res.string.lossless_verify_title, provider.label), style = MaterialTheme.typography.titleMedium)
            }
            if (ready && !finishing) {
                UIKitView(factory = { webView }, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(status, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
