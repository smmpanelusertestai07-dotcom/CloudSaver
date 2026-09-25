package com.pocketide.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

/**
 * Shows an HTML file an agent made as a plain document: JavaScript off, no file, content or
 * network access, no new windows, and every request other than the document itself refused.
 * Links do nothing. Unlike Preview, this never runs the page's code.
 */
@Composable
fun SafeHtmlView(html: String, modifier: Modifier = Modifier) {
    var closed by remember { mutableStateOf(false) }
    if (closed) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                SAFE_VIEW_CLOSED,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }
    AndroidView(
        modifier = modifier,
        factory = { context -> createSafeWebView(context, onClosed = { closed = true }) },
        update = { view ->
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { view ->
            // After its renderer went away the view is already destroyed (see SafeClient).
            if ((view.webViewClient as? SafeClient)?.gone != true) {
                view.stopLoading()
                view.destroy()
            }
        },
    )
}

internal const val SAFE_VIEW_CLOSED = "Android closed this page to free memory. Open it again to see it."

private fun createSafeWebView(context: Context, onClosed: () -> Unit): WebView = WebView(context).apply {
    settings.apply {
        javaScriptEnabled = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        domStorageEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        blockNetworkLoads = true
        blockNetworkImage = true
        safeBrowsingEnabled = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setGeolocationEnabled(false)
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
    }
    webViewClient = SafeClient(onClosed)
}

// Lint flags the super-constructor call of every Kotlin WebViewClient, even one that overrides
// onRenderProcessGone as this one does; WebViewClientsTest holds every client to it instead.
@SuppressLint("MissingOnRenderProcessGone")
private class SafeClient(private val onClosed: () -> Unit) : WebViewClient() {
    var gone = false
        private set

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (WebPolicy.safeViewerAllows(request.url.toString(), request.isForMainFrame)) return null
        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    /**
     * The app's WebViews share one renderer. When Android reclaims it, every view's client is
     * asked; one that does not answer true takes the whole app down, the agent's room included.
     * This view is dropped and a short note takes its place.
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        gone = true
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        onClosed()
        return true
    }
}
