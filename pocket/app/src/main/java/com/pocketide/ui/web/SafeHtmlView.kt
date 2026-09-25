package com.pocketide.ui.web

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

/**
 * Shows an HTML file an agent made as a plain document: JavaScript off, no file, content or
 * network access, no new windows, and every request other than the document itself refused.
 * Links do nothing. Unlike Preview, this never runs the page's code.
 */
@Composable
fun SafeHtmlView(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context -> createSafeWebView(context) },
        update = { view ->
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.destroy()
        },
    )
}

private fun createSafeWebView(context: Context): WebView = WebView(context).apply {
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
    webViewClient = SafeClient()
}

private class SafeClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (WebPolicy.safeViewerAllows(request.url.toString(), request.isForMainFrame)) return null
        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}
