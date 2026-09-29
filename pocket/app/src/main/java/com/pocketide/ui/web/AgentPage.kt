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
import com.pocketide.ide.IdeState
import org.json.JSONObject

/** What the agent screen shows over or instead of the page. */
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

    /** The page tried to download a file to the phone, which PocketIDE does not do. */
    fun downloadRefused()

    fun onPageState(state: PageState)
}

/**
 * The agent screen's one WebView, kept for the life of the process so that leaving the screen, or
 * the app, does not drop the page: coming back shows the agents as they were, still working. It
 * sits on a [MutableContextWrapper] whose base is the activity while shown (dialogs and pickers
 * need one) and the application otherwise.
 *
 * The page is code-server on this phone (127.0.0.1). It may navigate only there; every other web
 * address opens in Chrome. No JavaScript interface is added, so nothing on the page can call into
 * the app.
 */
class AgentPage(private val app: Context) {
    private var web: WebView? = null
    private var zoom = 1f
    private var fittedWidth = 0
    private var tallest = 0
    private var wrapper: MutableContextWrapper? = null
    private var host: PageHost? = null

    /** The code-server the page belongs to, and the folder it has open. */
    private var server: IdeState.On? = null
    var folder: String? = null
        private set

    /** The WebView for [server] and [folder], created on first use and moved onto [activity]. */
    fun attach(activity: Activity, server: IdeState.On, folder: String, host: PageHost): WebView {
        this.host = host
        if (this.server != server) release()
        val view = web ?: create(server)
        wrapper?.baseContext = activity
        (view.parent as? ViewGroup)?.removeView(view)
        if (this.folder != folder) {
            this.folder = folder
            view.loadUrl(server.url(folder))
        }
        view.onResume()
        return view
    }

    /** Called when the screen goes away: the page keeps running, on the application's context. */
    fun detach() {
        web?.let { (it.parent as? ViewGroup)?.removeView(it) }
        wrapper?.baseContext = app
        host = null
    }

    /** Ends the page: code-server stopped, or the owner deleted the computer. */
    fun release() {
        web?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        web = null
        wrapper = null
        server = null
        folder = null
    }

    fun reload() {
        web?.reload()
    }

    /** A key press as if from a keyboard: the page gets real (trusted) key events. */
    fun sendKey(keyCode: Int, meta: Int = 0) {
        val view = web ?: return
        val now = SystemClock.uptimeMillis()
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    /**
     * Back: closes the page's open menu, palette or dialog first; [onEditor] runs when an editor
     * covers the agent (a terminal, an agent's settings, a file), [otherwise] when neither is open.
     */
    fun back(onEditor: () -> Unit, otherwise: () -> Unit) {
        val view = web ?: return otherwise()
        view.evaluateJavascript("window.__pocketide ? window.__pocketide.backTarget() : 'none'") { target ->
            when (target?.trim('"')) {
                "overlay" -> sendKey(KeyEvent.KEYCODE_ESCAPE)
                "editor" -> onEditor()
                else -> otherwise()
            }
        }
    }

    /**
     * Zooms the page out on a short screen (see [PageFit]), from the WebView's height with the
     * keyboard closed: the tallest it has been at its present width.
     */
    private fun fit(view: WebView) {
        if (view.width == 0 || view.height == 0) return
        if (view.width != fittedWidth) {
            fittedWidth = view.width
            tallest = 0
        }
        tallest = maxOf(tallest, view.height)
        val next = PageFit.zoom(tallest / view.resources.displayMetrics.density)
        if (next != zoom) {
            zoom = next
            applyZoom(view)
        }
    }

    private fun applyZoom(view: WebView) {
        val widthDp = view.width / view.resources.displayMetrics.density
        view.evaluateJavascript("window.__pocketide && window.__pocketide.fit($zoom, $widthDp)", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(server: IdeState.On): WebView {
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
            // The page's own viewport (width and scale) counts, so PageScript's fit() can zoom it out.
            useWideViewPort = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // An agent's own page (Antigravity's) is a frame from <port>.localhost, which keeps its state.
            setAcceptThirdPartyCookies(view, true)
            // code-server's password, as the session cookie it sets after a sign-in: the page never asks.
            setCookie(server.origin, "${server.cookie}; Path=/; HttpOnly; SameSite=Lax")
            flush()
        }
        view.webViewClient = PageClient()
        view.webChromeClient = ChromeClient()
        zoom = 1f
        fittedWidth = 0
        tallest = 0
        view.addOnLayoutChangeListener { changed, _, _, _, _, _, _, _, _ -> fit(changed as WebView) }
        view.setDownloadListener { _, _, _, _, _ -> host?.downloadRefused() }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, PageScript.SOURCE, setOf(server.origin))
        }
        web = view
        this.server = server
        folder = null
        return view
    }

    private fun port(): Int? = server?.port

    // Lint flags every Kotlin WebViewClient, even one that overrides onRenderProcessGone as each
    // of these does; WebViewClientsTest holds them to it instead.
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class PageClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Frames inside the page (the agents' own screens) load as VS Code asks.
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            return when (WebPolicy.navigation(url, port())) {
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
            val port = port()
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && port != null && WebPolicy.isIdePage(url, port)) {
                view.evaluateJavascript(PageScript.SOURCE, null)
            }
            // A page that loaded again starts at its own scale.
            if (zoom < 1f) applyZoom(view)
            host?.onPageState(PageState.Ready)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) host?.onPageState(PageState.Failed(PageText.unreachable(error.description?.toString())))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The renderer is gone, so this WebView is unusable: drop it, and let the owner reload.
            if (view === web) {
                val keep = server
                release()
                server = keep
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
            if (WebPolicy.navigation(url, port()) != Navigation.BLOCK) host?.openInChrome(url, fromTap)
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

/**
 * How far the page zooms out on a short screen. VS Code lays out for the window it has, but an
 * agent's own screen may need more height: Antigravity's welcome cuts off its Next button in a
 * window under about 705 CSS pixels tall (measured at 360 wide), and a 720x1600 phone at 2x gives
 * the page 672. A page shorter than [HEIGHT_DP] is drawn smaller until it has that much, never
 * below [MIN_ZOOM]; at 0.9 that phone's page is 401x747 and the whole welcome shows.
 */
internal object PageFit {
    const val HEIGHT_DP = 740f
    const val MIN_ZOOM = 0.8f

    /** The zoom for a WebView [heightDp] tall with the keyboard closed, in steps of 0.01. */
    fun zoom(heightDp: Float): Float {
        if (heightDp <= 0f) return 1f
        val exact = (heightDp / HEIGHT_DP).coerceIn(MIN_ZOOM, 1f)
        return kotlin.math.floor(exact * STEPS) / STEPS
    }

    private const val STEPS = 100f
}

/** Sentences for the agent screen's page problems. */
internal object PageText {
    fun unreachable(detail: String?): String =
        "code-server's page did not load" + (detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
            ". Tap Reload. If it keeps failing, restart the computer."
}

/**
 * The small script PocketIDE adds to code-server's page (only there): phone-sized touch targets,
 * no editor tab bar over an agent's screen, and one helper the app calls: whether a menu or dialog
 * is open (so Back closes it). It changes nothing else on the page.
 */
internal object PageScript {
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
          const shown = (el) => el.getClientRects().length > 0 && getComputedStyle(el).display !== 'none';
          const overlayOpen = () => ['.quick-input-widget', '.monaco-dialog-box', '.context-view .monaco-menu']
            .some((selector) => [...document.querySelectorAll(selector)].some(shown));
          window.__pocketide = {
            overlayOpen,
            // What Back closes first: a menu or dialog, then an editor over the agent (a terminal,
            // an agent's settings or a file), else nothing.
            backTarget() {
              if (overlayOpen()) return 'overlay';
              const editor = document.querySelector('.monaco-workbench .part.editor');
              const open = editor && shown(editor) && editor.getBoundingClientRect().width > 40 &&
                editor.querySelector('.editor-instance');
              return open ? 'editor' : 'none';
            },
            // Draws the page at [zoom] (0.5 to 1) of a WebView [widthDp] wide: the workbench then
            // lays out for a taller window, so a screen made for one fits.
            fit(zoom, widthDp) {
              const meta = document.querySelector('meta[name="viewport"]');
              if (!meta) return false;
              const z = Math.min(1, Math.max(0.5, Number(zoom) || 1));
              const width = Math.round((Number(widthDp) || window.innerWidth) / z);
              const content = z >= 1
                ? 'width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no'
                : `width=${'$'}{width}, initial-scale=${'$'}{z}, minimum-scale=${'$'}{z}, maximum-scale=${'$'}{z}, user-scalable=no`;
              if (meta.getAttribute('content') !== content) meta.setAttribute('content', content);
              return true;
            },
          };
        })();
    """.trimIndent()
}
