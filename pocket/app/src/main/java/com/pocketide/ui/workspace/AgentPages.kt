package com.pocketide.ui.workspace

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.pocketide.BuildConfig
import com.pocketide.agents.Agent
import org.json.JSONObject

/** What an agent's screen shows over or instead of its page. */
sealed interface PageState {
    data class Loading(val progress: Int) : PageState
    data object Ready : PageState
    data class Failed(val message: String) : PageState

    /** Android stopped the page's renderer (often for memory); a reload brings it back. */
    data object Stopped : PageState
}

/** What a page asks of the screen that shows it. */
interface PageHost {
    /** The page opened [url] in a new window (a link, a sign-in); [fromTap] when the owner tapped. */
    fun openWindow(url: String, fromTap: Boolean)

    /** The page itself moved away from VS Code, to [url]. */
    fun leftPage(url: String)

    /** Opens the phone's picker for a file input; the callback must always be answered. */
    fun pickFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams)

    fun downloadRefused()

    fun onPageState(agent: Agent, state: PageState)
}

/**
 * The agents' VS Code pages, one WebView each, kept for the life of the app so that switching
 * agents, leaving the screen or opening a sign-in in Chrome never reloads one: coming back shows
 * the agent as it was, still working. At most [KEEP] stay (a phone's memory); the one used longest
 * ago goes first. Each sits on a [MutableContextWrapper] whose base is the screen's activity while
 * shown (dialogs and pickers need one) and the application otherwise.
 *
 * A page is VS Code in Cloud Shell, through PocketIDE's door. It may navigate only there; every
 * other address goes to the screen ([PageHost]). No JavaScript interface is added, so nothing on a
 * page can call into the app.
 */
class AgentPages(private val app: Context) {
    private val pages = LinkedHashMap<Agent, AgentPage>(KEEP, LOAD_FACTOR, true)

    /** [agent]'s page at [url], created on first use (or when [url] changed) and moved onto [activity]. */
    fun attach(agent: Agent, url: String, activity: Activity, host: PageHost): WebView {
        val page = pages[agent]?.takeIf { it.url == url && it.web != null } ?: AgentPage(app, agent, url).also {
            pages.remove(agent)?.release()
            pages[agent] = it
            trim(keep = agent)
        }
        return page.attach(activity, host)
    }

    fun detach(agent: Agent, host: PageHost) {
        pages[agent]?.detach(host)
    }

    fun page(agent: Agent): AgentPage? = pages[agent]

    fun has(agent: Agent, url: String): Boolean = pages[agent]?.let { it.url == url && it.web != null } == true

    /** Ends [agent]'s page; the next attach loads it again. */
    fun release(agent: Agent) {
        pages.remove(agent)?.release()
    }

    fun releaseAll() {
        pages.values.forEach { it.release() }
        pages.clear()
    }

    private fun trim(keep: Agent) {
        while (pages.size > KEEP) {
            val oldest = pages.keys.firstOrNull { it != keep } ?: return
            pages.remove(oldest)?.release()
        }
    }

    private companion object {
        const val KEEP = 2
        const val LOAD_FACTOR = 0.75f
    }
}

/** One agent's VS Code page. */
class AgentPage(private val app: Context, val agent: Agent, val url: String) {
    var web: WebView? = null
        private set
    private var wrapper: MutableContextWrapper? = null
    private var host: PageHost? = null
    private var zoom = 1f
    private var fittedWidth = 0

    /**
     * The whole IDE is on screen around the agent (PocketIDE's IDE button), drawn smaller
     * ([PageFit.IDE_ZOOM]) so its files, editor and agent fit side by side, as on a computer.
     */
    var wide by mutableStateOf(false)
        private set
    private var tallest = 0
    private val origin = url.trimEnd('/')

    fun attach(activity: Activity, host: PageHost): WebView {
        this.host = host
        val view = web ?: create()
        wrapper?.baseContext = activity
        (view.parent as? ViewGroup)?.removeView(view)
        view.onResume()
        return view
    }

    /** The screen [host] went away: the page keeps running, on the application's context. */
    fun detach(host: PageHost) {
        if (this.host !== host) return
        web?.let { (it.parent as? ViewGroup)?.removeView(it) }
        wrapper?.baseContext = app
        this.host = null
    }

    fun release() {
        web?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        web = null
        wrapper = null
    }

    fun reload() {
        wide = false
        web?.reload()
    }

    /**
     * A key press as if from a keyboard: the page gets real (trusted) key events. Android sends them
     * without the key's code, which VS Code's shortcuts need: good for Esc, Tab, the arrows, Enter and
     * Ctrl+C in a terminal; PocketIDE's own commands go through [run].
     */
    fun sendKey(keyCode: Int, meta: Int = 0) {
        val view = web ?: return
        val now = SystemClock.uptimeMillis()
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    /** Types [text] where the cursor is (the editor, the terminal, an agent's box); false when nothing took it. */
    fun type(text: String, done: (Boolean) -> Unit) {
        val view = web ?: return done(false)
        view.evaluateJavascript("window.__pocketide ? window.__pocketide.type(${JSONObject.quote(text)}) : false") { result ->
            done(result == "true")
        }
    }

    /**
     * PocketIDE's own command in this VS Code (assets/workspace/pagescript.js): back, agent, ide,
     * terminal, files, commands, vsix or tools; or escape, which closes a menu or dialog. The IDE
     * draws the page smaller ([wide]); back, the agent and the terminal leave it.
     */
    fun run(command: String) {
        val view = web ?: return
        val nowWide = when (command) {
            IDE -> true
            AGENT, TERMINAL, BACK -> false
            else -> wide
        }
        if (nowWide != wide) {
            wide = nowWide
            applyZoom(view)
        }
        val call = if (command == ESCAPE) "escape()" else "run(${JSONObject.quote(command)})"
        view.evaluateJavascript("window.__pocketide && window.__pocketide.$call", null)
    }

    /**
     * Back: closes the page's open menu, dialog, palette or notice first; then what covers the agent
     * (a file, a diff, a terminal, the whole IDE) steps aside and the agent is back; [otherwise] runs
     * when the agent alone is on screen.
     */
    fun back(otherwise: () -> Unit) {
        val view = web ?: return otherwise()
        view.evaluateJavascript("window.__pocketide ? window.__pocketide.backTarget() : 'none'") { target ->
            when (target?.trim('"')) {
                "overlay" -> run(ESCAPE)
                "vscode" -> run(BACK)
                else -> otherwise()
            }
        }
    }

    /** Zooms the page out on a short screen (see [PageFit]), from its height with the keyboard closed. */
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
        val drawn = if (wide) minOf(zoom, PageFit.IDE_ZOOM) else zoom
        view.evaluateJavascript("window.__pocketide && window.__pocketide.fit($drawn, $widthDp)", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView {
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
            cacheMode = WebSettings.LOAD_DEFAULT
            // VS Code opens links and sign-in pages with window.open; each goes to the screen (onCreateWindow).
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            // The page's own viewport counts, so PageScript's fit() can zoom it out on a short screen.
            useWideViewPort = true
            // VS Code sizes its own text; the phone's font size would only break its layout.
            textZoom = FULL
        }
        // VS Code follows the app's light or dark theme itself; the WebView must not darken it again.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Antigravity's panel is a frame from another of the door's addresses, which keeps its state.
            setAcceptThirdPartyCookies(view, true)
        }
        view.webViewClient = PageClient()
        view.webChromeClient = ChromeClient()
        view.addOnLayoutChangeListener { changed, _, _, _, _, _, _, _, _ -> fit(changed as WebView) }
        view.setDownloadListener { _, _, _, _, _ -> host?.downloadRefused() }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, PageScript.source(app), setOf(origin))
        }
        web = view
        view.loadUrl(url)
        return view
    }

    private fun isOwn(url: String) = url == origin || url.startsWith("$origin/") || url.startsWith("$origin?")

    // Lint flags every Kotlin WebViewClient, even one that overrides onRenderProcessGone as each of these does.
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class PageClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Frames inside the page (the agents' own panels) load as VS Code asks.
            if (!request.isForMainFrame) return false
            val next = request.url.toString()
            if (isOwn(next)) return false
            host?.leftPage(next)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            host?.onPageState(agent, PageState.Loading(0))
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && isOwn(url)) {
                view.evaluateJavascript(PageScript.source(app), null)
            }
            if (zoom < 1f || wide) applyZoom(view)
            host?.onPageState(agent, PageState.Ready)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) host?.onPageState(agent, PageState.Failed(PageText.unreachable(error.description?.toString())))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The renderer is gone, so this WebView is unusable: drop it; a reload makes a new one.
            if (view === web) release()
            host?.onPageState(agent, PageState.Stopped)
            return true
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (newProgress < FULL) host?.onPageState(agent, PageState.Loading(newProgress))
        }

        /** A new window is a link to open elsewhere: its first address goes to the screen, and the window never shows. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val probe = WebView(view.context)
            probe.webViewClient = ProbeClient(fromTap = isUserGesture) { url -> host?.openWindow(url, isUserGesture) }
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

    companion object {
        private const val FULL = 100
        const val BACK = "back"
        const val ESCAPE = "escape"
        const val IDE = "ide"
        const val AGENT = "agent"
        const val TERMINAL = "terminal"
    }
}

/** Hands a new window's first address on, then ends the window. */
@SuppressLint("MissingOnRenderProcessGone")
internal class ProbeClient(private val fromTap: Boolean, private val open: (String) -> Unit) : WebViewClient() {
    private var done = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        take(view, request.url.toString())
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        // A window opened with an address and no navigation of its own starts here instead.
        if (url != "about:blank") take(view, url)
    }

    private fun take(view: WebView, url: String) {
        if (done) return
        done = true
        view.stopLoading()
        open(url)
        view.post { view.destroy() }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        view.destroy()
        return true
    }
}

/**
 * How far the page zooms out on a short screen. VS Code lays out for the window it has, but an
 * agent's own screen may need more height: Antigravity's welcome cuts off its button in a window
 * under about 705 CSS pixels tall (measured at 360 wide), and a 720x1600 phone at 2x gives the page
 * less. A page shorter than [HEIGHT_DP] is drawn smaller until it has that much, never below [MIN_ZOOM].
 */
internal object PageFit {
    const val HEIGHT_DP = 740f
    const val MIN_ZOOM = 0.8f

    /** The whole IDE: 600 CSS pixels on a 360 dp phone, room for the files, an editor and the agent. */
    const val IDE_ZOOM = 0.6f

    /** The zoom for a WebView [heightDp] tall with the keyboard closed, in steps of 0.01. */
    fun zoom(heightDp: Float): Float {
        if (heightDp <= 0f) return 1f
        val exact = (heightDp / HEIGHT_DP).coerceIn(MIN_ZOOM, 1f)
        return kotlin.math.floor(exact * STEPS) / STEPS
    }

    private const val STEPS = 100f
}

/** Sentences for a page that did not load. */
internal object PageText {
    fun unreachable(detail: String?): String =
        "VS Code's page did not load" + (detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
            ". Tap Reload. If it keeps failing, disconnect and connect again."
}

/**
 * The script PocketIDE adds to the agents' VS Code pages, and only there: assets/workspace/pagescript.js
 * (phone-sized dialogs, notices and menus; Back, Tools and the Paste key's helpers). CI loads the
 * same file into each agent's VS Code at a phone's size and checks it. Read once.
 */
internal object PageScript {
    const val ASSET = "workspace/pagescript.js"

    @Volatile
    private var source: String? = null

    fun source(context: Context): String =
        source ?: context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }.also { source = it }
}
