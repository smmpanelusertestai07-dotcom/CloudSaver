package com.pocketide.ui.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.pocketide.BuildConfig
import com.pocketide.agents.Agent
import org.json.JSONObject

/** What the computer screen shows over or instead of the page. */
sealed interface PageState {
    data class Loading(val progress: Int) : PageState
    data object Ready : PageState
    data class Failed(val message: String) : PageState

    /** Android stopped the page's renderer (often for memory); a reload brings it back. */
    data object Stopped : PageState
}

/** What the page asks of the screen that shows it. */
interface PageHost {
    /** [fromTap] is false when the page opened it by itself; the screen then asks first. */
    fun openInChrome(url: String, fromTap: Boolean)

    /** Opens the phone's picker for a file input; the callback must always be answered. */
    fun pickFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams)

    fun onPageState(state: PageState)
}

/**
 * The computer screen's one WebView, kept for the life of the process so that leaving the screen,
 * or the app, does not drop the page: coming back shows the agents as they were. It sits on a
 * [MutableContextWrapper] whose base is the activity while shown (dialogs and pickers need one)
 * and the application otherwise.
 *
 * The page is VS Code for the web on the owner's own codespace. It may navigate only to GitHub
 * (sign-in) and to the codespace; every other address opens in Chrome. No JavaScript interface
 * is added, so nothing on the page can call into the app.
 */
class ComputerWebView(private val app: Context) {
    private var web: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    private var host: PageHost? = null

    /** The codespace the page belongs to. */
    var computerName: String? = null
        private set

    /** The WebView for [name], created on first use and moved onto [activity]. */
    fun attach(activity: Activity, name: String, url: String, host: PageHost): WebView {
        this.host = host
        if (computerName != name) release()
        val view = web ?: create(url).also { computerName = name }
        wrapper?.baseContext = activity
        (view.parent as? ViewGroup)?.removeView(view)
        view.onResume()
        return view
    }

    /** Called when the screen goes away: the page keeps running, on the application's context. */
    fun detach() {
        web?.let { (it.parent as? ViewGroup)?.removeView(it) }
        wrapper?.baseContext = app
        host = null
    }

    /** Ends the page: the owner stopped or deleted the computer, or left the app. */
    fun release() {
        web?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        web = null
        wrapper = null
        computerName = null
    }

    /** [release], unless the computer screen is showing the page right now. */
    fun releaseIfHidden() {
        if (host == null) release()
    }

    fun reload() {
        web?.reload()
    }

    /** Loads the computer's address again from the start, for a page that lost its way. */
    fun reopen(url: String) {
        if (WebPolicy.isComputerPage(url)) web?.loadUrl(url)
    }

    /** [reopen], when the page belongs to [name]: its computer was stopped and started again since. */
    fun reopenIfFor(name: String, url: String) {
        if (computerName == name) reopen(url)
    }

    /** Brings [agent]'s tab to the front; [done] gets false when the page has no such tab yet. */
    fun showAgent(agent: Agent, done: (Boolean) -> Unit) {
        val view = web ?: return done(false)
        view.evaluateJavascript("window.__pocketide ? window.__pocketide.show(${JSONObject.quote(agent.label)}) : false") { result ->
            done(result == "true")
        }
    }

    /** A key press as if from a keyboard: the page gets real (trusted) key events. */
    fun sendKey(keyCode: Int, meta: Int = 0) {
        val view = web ?: return
        val now = SystemClock.uptimeMillis()
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    /** Back: closes the page's open menu, palette or dialog first; [otherwise] runs when none is open. */
    fun back(otherwise: () -> Unit) {
        val view = web ?: return otherwise()
        view.evaluateJavascript("window.__pocketide ? window.__pocketide.overlayOpen() : false") { open ->
            if (open == "true") sendKey(KeyEvent.KEYCODE_ESCAPE) else otherwise()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(url: String): WebView {
        val context = MutableContextWrapper(app).also { wrapper = it }
        val view = WebView(context)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            // VS Code opens sign-in pages with window.open; each one goes to Chrome (onCreateWindow).
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // The agents' panels are frames on another site (vscode-cdn.net) that keep their state.
            setAcceptThirdPartyCookies(view, true)
        }
        view.webViewClient = PageClient()
        view.webChromeClient = ChromeClient()
        view.setDownloadListener { downloadUrl, _, _, _, _ -> host?.openInChrome(downloadUrl, fromTap = true) }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, PageScript.SOURCE, setOf(PageScript.ORIGINS))
        }
        web = view
        if (WebPolicy.isComputerPage(url)) view.loadUrl(url)
        return view
    }

    // Lint flags every Kotlin WebViewClient, even one that overrides onRenderProcessGone as each
    // of these does; WebViewClientsTest holds them to it instead.
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class PageClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Frames inside the page (the agents' own panels) load as VS Code asks.
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            return when (WebPolicy.navigation(url)) {
                Navigation.STAY -> false
                Navigation.CHROME -> {
                    host?.openInChrome(url, fromTap = request.hasGesture())
                    true
                }
                Navigation.BLOCK -> true
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            host?.onPageState(PageState.Loading(0))
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && WebPolicy.navigation(url) == Navigation.STAY) {
                view.evaluateJavascript(PageScript.SOURCE, null)
            }
            host?.onPageState(PageState.Ready)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) host?.onPageState(PageState.Failed(PageText.offline(error.description?.toString())))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The renderer is gone, so this WebView is unusable: drop it, and let the owner reload.
            if (view === web) {
                val name = computerName
                release()
                computerName = name
            }
            host?.onPageState(PageState.Stopped)
            return true
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (newProgress < DONE) host?.onPageState(PageState.Loading(newProgress))
        }

        /** A new window is a link to open elsewhere: its first address goes to Chrome, and the window is never shown. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val probe = WebView(view.context)
            probe.webViewClient = ProbeClient(fromTap = isUserGesture)
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = probe
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            val screen = host ?: return false
            screen.pickFiles(callback, params)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // No camera, microphone or other device access for pages.
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }
    }

    /** Hands a new window's first address to Chrome, then ends the window. */
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class ProbeClient(private val fromTap: Boolean) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (WebPolicy.navigation(url) != Navigation.BLOCK) host?.openInChrome(url, fromTap)
            view.destroy()
            return true
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            view.destroy()
            return true
        }
    }

    private companion object {
        const val DONE = 100
    }
}

/** Sentences for the computer screen's page problems. */
internal object PageText {
    fun offline(detail: String?): String =
        "The cloud computer's page did not load" + (detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
            ". Check the internet connection, then tap Reload."
}

/**
 * The small script PocketIDE adds to the codespace's page (only on github.dev): phone-sized touch
 * targets, and two helpers the app calls: bring an agent's tab to the front, and tell whether a
 * menu or dialog is open (so Back closes it). It changes nothing else on the page.
 */
internal object PageScript {
    const val ORIGINS = "https://*.github.dev"

    private val STYLE = """
        .monaco-workbench .sash-container > .monaco-sash { display: none !important; }
        .monaco-workbench .part.auxiliarybar > .title .action-item { min-width: 44px !important; }
        .monaco-workbench .editor-group-container:has(> .editor-container > .editor-instance > [id^="webview-editor-element-"]) > .title { display: none !important; }
        .monaco-workbench .editor-group-container:has(> .editor-container > .editor-instance > [id^="webview-editor-element-"]) > .editor-container { height: 100% !important; }
    """.trimIndent()

    val SOURCE = """
        (() => {
          if (window.__pocketide) return;
          const style = ${JSONObject.quote(STYLE)};
          const addStyle = () => {
            if (document.getElementById('pocketide-style')) return;
            const node = document.createElement('style');
            node.id = 'pocketide-style';
            node.textContent = style;
            (document.head || document.documentElement).appendChild(node);
          };
          if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', addStyle, { once: true });
          else addStyle();
          const labelOf = (el) => (el.getAttribute('aria-label') || el.getAttribute('title') || el.textContent || '').trim().toLowerCase();
          const shown = (el) => el.getClientRects().length > 0 && getComputedStyle(el).display !== 'none';
          window.__pocketide = {
            show(label) {
              const wanted = label.toLowerCase();
              const tabs = document.querySelectorAll('.monaco-workbench .composite-bar .action-item .action-label');
              for (const tab of tabs) {
                if (labelOf(tab).startsWith(wanted)) { tab.click(); return true; }
              }
              return false;
            },
            overlayOpen() {
              return ['.quick-input-widget', '.monaco-dialog-box', '.context-view .monaco-menu']
                .some((selector) => [...document.querySelectorAll(selector)].some(shown));
            },
          };
        })();
    """.trimIndent()
}
