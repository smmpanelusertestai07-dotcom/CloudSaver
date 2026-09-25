package com.pocketide.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketide.media.VideoFrames
import com.pocketide.ui.shell.External
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the page's callbacks reach; refreshed on every composition so nothing goes stale. */
internal class WebCallbacks {
    var isInternal: (String) -> Boolean = { false }
    var openExternal: (String) -> Unit = {}
    var notice: (String) -> Unit = {}
    var pageFinished: (WebView, String) -> Unit = { _, _ -> }
    /** Opens the photo picker; false when it could not be opened. */
    var chooseFiles: (PickerKind, Boolean) -> Boolean = { _, _ -> false }
    /** The owner touched the page or typed into it; already throttled (see [Throttle]). */
    var interaction: () -> Unit = {}
}

/**
 * Owns one WebView for as long as its screen needs it. Held in a `remember` keyed by what the
 * view shows (a session, a port), so recompositions and configuration changes (the activity
 * handles them itself) keep the page, its scroll position and its sockets alive. It is
 * destroyed only when the screen leaves composition for good, or when Android kills its
 * renderer (then the owner gets a Reload button, never a crash).
 */
@Stable
class WebViewHolder internal constructor() {
    internal var webView: WebView? = null
    internal var loadedUrl: String? = null
    internal var pendingFiles: ValueCallback<Array<Uri>>? = null
    /** Key frames per picked video for the waiting input; 0 when videos go to it as they are. */
    internal var framesPerVideo = 0
    /** Windows the page opened that are not yet handed to Chrome or dropped. */
    internal var popups = 0
    internal val callbacks = WebCallbacks()

    /** Bumped when a dead WebView is replaced, so the AndroidView is created afresh. */
    internal var generation by mutableIntStateOf(0)
    var loading by mutableStateOf(true)
        internal set
    /** Why the page is not showing (renderer gone, page did not answer), or null. */
    var problem by mutableStateOf<String?>(null)
        internal set
    /** An address the page tried to open in Chrome without a tap; the owner decides. */
    internal var askToOpen by mutableStateOf<String?>(null)

    /** Runs JavaScript in the page; ignored when there is no page. [result] gets its JSON value. */
    fun evaluate(script: String, result: ((String?) -> Unit)? = null) {
        webView?.evaluateJavascript(script, result?.let { callback -> ValueCallback<String> { value -> callback(value) } })
    }

    /** Clears the problem so the page is shown again; the next address given is loaded afresh. */
    internal fun retry() {
        problem = null
        loadedUrl = null
    }

    /** Loads the page again, rebuilding the WebView when its renderer died. */
    fun reload() {
        problem = null
        val view = webView
        if (view == null) {
            generation++
        } else {
            view.reload()
        }
    }

    /**
     * The page navigates away from the app's own addresses: Chrome, a question, or nothing.
     * code-server's address for a port on the phone leads nowhere (the rooms turn that route
     * off), so Chrome gets the port itself.
     */
    internal fun leave(url: String, userGesture: Boolean) {
        val target = WebPolicy.withoutEngineProxy(url)
        if (callbacks.isInternal(target)) return
        when (WebPolicy.externalOpen(target, userGesture)) {
            ExternalOpen.OPEN -> callbacks.openExternal(target)
            ExternalOpen.ASK -> askToOpen = target
            ExternalOpen.IGNORE -> Unit
        }
    }

    internal fun deliverFiles(uris: Array<Uri>?) {
        pendingFiles?.onReceiveValue(uris)
        pendingFiles = null
    }

    /**
     * The picker's answer for the waiting input. When that input takes only pictures, each video
     * becomes [framesPerVideo] key frames first, off the main thread (§8).
     */
    internal fun receivePicked(uris: List<Uri>, context: Context, scope: CoroutineScope) {
        val perVideo = framesPerVideo
        val pending = pendingFiles
        if (uris.isEmpty() || perVideo == 0 || pending == null) {
            deliverFiles(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
            return
        }
        val app = context.applicationContext
        scope.launch {
            var answer: Array<Uri>? = null
            try {
                val replaced = withContext(Dispatchers.IO) { VideoFrames.forImageInput(app, uris, perVideo) }
                WebPolicy.unreadableVideos(replaced.unreadableVideos)?.let(callbacks.notice)
                answer = replaced.items.takeIf { it.isNotEmpty() }?.toTypedArray()
            } finally {
                // Only the input that asked gets the answer; a newer file input has its own.
                if (pendingFiles === pending) deliverFiles(answer)
            }
        }
    }

    internal fun obtain(context: Context, create: (Context) -> WebView): WebView {
        val existing = webView
        if (existing != null) {
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        return create(context).also { webView = it }
    }

    /** Tears the WebView down; safe to call more than once. */
    fun destroy() {
        deliverFiles(null)
        askToOpen = null
        val view = webView ?: return
        webView = null
        loadedUrl = null
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }
}

/** A [WebViewHolder] that lives while [key] stays the same and is destroyed when the caller leaves. */
@Composable
fun rememberWebViewHolder(key: Any): WebViewHolder {
    val holder = remember(key) { WebViewHolder() }
    DisposableEffect(holder) { onDispose { holder.destroy() } }
    return holder
}

private const val DOWNLOAD_BLOCKED =
    "Downloads are blocked here. Ask the agent to save the file to Media, then open it from there."

/**
 * The one WebView component for agents, Preview and the terminal. JavaScript and DOM storage
 * are on; file and content access are off; Safe Browsing is on; mixed content is never
 * allowed. Only URLs [isInternal] accepts load inside the app (the port bridge's own origins);
 * any other link, and any new window, opens in Chrome through [onOpenExternal]. A file input
 * opens Android's photo picker, which needs no permission; an input that takes only pictures
 * still offers videos and gets a few key frames of each. Downloads are refused with a notice.
 */
@Composable
fun AgentWebView(
    url: String,
    holder: WebViewHolder,
    isInternal: (String) -> Boolean,
    onOpenExternal: (String) -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Text size in percent (WebSettings.textZoom), for the owner's per-agent choice. */
    textZoom: Int = 100,
    /** Replaces Reload when the address itself may be stale (a new one must be asked for). */
    onRetry: (() -> Unit)? = null,
    onPageFinished: (WebView, String) -> Unit = { _, _ -> },
    onCreated: (WebView) -> Unit = {},
    /** The owner is using the page (a tap, a key): at most about once a minute. */
    onInteraction: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        holder.receivePicked(listOfNotNull(uri), context, scope)
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        holder.receivePicked(uris, context, scope)
    }
    SideEffect {
        holder.callbacks.isInternal = isInternal
        holder.callbacks.openExternal = onOpenExternal
        holder.callbacks.notice = onNotice
        holder.callbacks.pageFinished = onPageFinished
        holder.callbacks.interaction = onInteraction
        holder.callbacks.chooseFiles = { kind, multiple ->
            val request = PickVisualMediaRequest(mediaType = kind.toMediaType())
            External.leaving(context)
            runCatching { if (multiple) pickMany.launch(request) else pickOne.launch(request) }.isSuccess
        }
    }
    val background = MaterialTheme.colorScheme.background.toArgb()
    val allowed = isInternal(url)

    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        val problem = holder.problem
        when {
            !allowed -> PageProblem("This address is not allowed inside the app.", null)
            problem != null -> PageProblem(problem) {
                if (onRetry == null) {
                    holder.reload()
                } else {
                    holder.retry()
                    onRetry()
                }
            }
            else -> key(holder.generation) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        holder.obtain(context) { ctx -> createAgentWebView(ctx, holder, background).also(onCreated) }
                    },
                    update = { view ->
                        if (view.settings.textZoom != textZoom) view.settings.textZoom = textZoom
                        if (holder.loadedUrl != url) {
                            holder.loadedUrl = url
                            holder.loading = true
                            view.loadUrl(url)
                        }
                    },
                    // Leaving composition only detaches the view; the holder decides when it dies.
                    onRelease = { view -> (view.parent as? ViewGroup)?.removeView(view) },
                )
            }
        }
        if (allowed && problem == null && holder.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
        holder.askToOpen?.let { target ->
            OpenQuestion(
                host = WebPolicy.hostOf(target) ?: target,
                onOpen = {
                    holder.askToOpen = null
                    onOpenExternal(target)
                },
                onDismiss = { holder.askToOpen = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun OpenQuestion(host: String, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(12.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("The page wants to open $host in Chrome.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Not now", color = MaterialTheme.colorScheme.inversePrimary) }
            TextButton(onClick = onOpen) { Text("Open", color = MaterialTheme.colorScheme.inversePrimary) }
        }
    }
}

@Composable
private fun PageProblem(text: String, onRetry: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (onRetry != null) Button(onClick = onRetry) { Text("Reload") }
    }
}

private fun PickerKind.toMediaType(): ActivityResultContracts.PickVisualMedia.VisualMediaType = when (this) {
    PickerKind.IMAGES -> ActivityResultContracts.PickVisualMedia.ImageOnly
    PickerKind.VIDEOS -> ActivityResultContracts.PickVisualMedia.VideoOnly
    PickerKind.IMAGES_AND_VIDEOS -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
}

// JavaScript is what these pages are; they only ever come from the loopback bridge.
@SuppressLint("SetJavaScriptEnabled")
private fun createAgentWebView(context: Context, holder: WebViewHolder, background: Int): WebView =
    UsedWebView(context, holder).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(background)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            safeBrowsingEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            // New windows are caught in onCreateWindow and sent to Chrome.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            // Browser-like layout; the page's own viewport rule decides its width.
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // Android's font scale is applied by the pages themselves; doing it here too doubles it.
            textZoom = 100
            mediaPlaybackRequiresUserGesture = true
            // VS Code turns on touch scrolling only for a mobile user agent.
            if (!userAgentString.contains("Mobi")) userAgentString = "$userAgentString Mobile"
        }
        // The bridge sets its per-launch token as a first-party cookie; nothing else needs cookies.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = AgentClient(holder)
        webChromeClient = AgentChrome(holder)
        setDownloadListener { _, _, _, _, _ -> holder.callbacks.notice(DOWNLOAD_BLOCKED) }
    }

/**
 * Tells the holder when the owner uses the page: a touch, a hardware key, or typing on the
 * soft keyboard (which reaches the page through the input connection, not as key events).
 */
private class UsedWebView(context: Context, private val holder: WebViewHolder) : WebView(context) {
    private val throttle = Throttle(INTERACTION_EVERY_MS, SystemClock::elapsedRealtime)

    fun used() {
        if (throttle.ready()) holder.callbacks.interaction()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) used()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) used()
        return super.dispatchKeyEvent(event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(connection, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                used()
                return super.commitText(text, newCursorPosition)
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                used()
                return super.setComposingText(text, newCursorPosition)
            }
        }
    }
}

private const val INTERACTION_EVERY_MS = 60_000L

// Lint flags the super-constructor call of every Kotlin WebViewClient, even one that overrides
// onRenderProcessGone as each of ours does; WebViewClientsTest holds them to it instead.
@SuppressLint("MissingOnRenderProcessGone")
private class AgentClient(private val holder: WebViewHolder) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (holder.callbacks.isInternal(url)) return false
        if (!request.isForMainFrame && WebPolicy.isFrameLocal(url)) return false
        if (request.isForMainFrame) holder.leave(url, request.hasGesture())
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        holder.loading = true
    }

    override fun onPageFinished(view: WebView, url: String) {
        holder.loading = false
        holder.callbacks.pageFinished(view, url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        // Only the page itself; a panel failing to fetch something is the panel's business.
        if (!request.isForMainFrame) return
        holder.loading = false
        holder.problem = "The page did not answer (${error.description}). It may still be starting."
    }

    /**
     * Android kills a WebView's renderer to reclaim memory; an app that does not handle this is
     * killed with it. The dead view is dropped and the owner gets a Reload button instead.
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        holder.problem = if (detail.didCrash()) {
            "The page stopped unexpectedly."
        } else {
            "Android needed the memory back and closed the page. The agent kept running."
        }
        holder.destroy()
        return true
    }
}

private class AgentChrome(private val holder: WebViewHolder) : WebChromeClient() {
    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        holder.deliverFiles(null)
        holder.pendingFiles = filePathCallback
        val multiple = fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
        val pick = WebPolicy.filePick(fileChooserParams.acceptTypes?.toList().orEmpty(), multiple)
        holder.framesPerVideo = pick.framesPerVideo
        if (!holder.callbacks.chooseFiles(pick.picker, multiple)) {
            // Returning false hands the callback back to the WebView, so it must not be called.
            holder.pendingFiles = null
            return false
        }
        return true
    }

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        // A script opening windows in a loop gets one at a time; each tap still gets its own.
        if (!isUserGesture && holder.popups > 0) return false
        holder.popups++
        val popup = WebView(view.context)
        popup.webViewClient = PopupCatcher(holder, popup, isUserGesture)
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
        callback.invoke(origin, false, false)
    }
}

/**
 * A window a page opens (a sign-in page, a docs link) never becomes a WebView of ours: its first
 * address goes to Chrome (after a question when no tap opened it) and the throwaway view is
 * destroyed before it loads anything. JavaScript stays off in it.
 */
@SuppressLint("MissingOnRenderProcessGone") // Overridden below; see AgentClient.
private class PopupCatcher(
    private val holder: WebViewHolder,
    private val popup: WebView,
    private val userGesture: Boolean,
) : WebViewClient() {
    private var handled = false
    private var destroyed = false
    private val main = Handler(Looper.getMainLooper())

    init {
        // A window that never navigates is dropped too.
        main.postDelayed({ destroyPopup() }, 30_000)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        handle(request.url.toString())
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        handle(url)
    }

    /** The renderer is shared with the agent's view: answering false here would end the whole app. */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        destroyPopup()
        return true
    }

    private fun handle(url: String?) {
        if (handled || url.isNullOrEmpty() || WebPolicy.isFrameLocal(url)) return
        handled = true
        popup.stopLoading()
        holder.leave(url, userGesture)
        // Not from inside the popup's own callback.
        main.post { destroyPopup() }
    }

    private fun destroyPopup() {
        if (destroyed) return
        destroyed = true
        holder.popups--
        main.removeCallbacksAndMessages(null)
        popup.stopLoading()
        popup.destroy()
    }
}
