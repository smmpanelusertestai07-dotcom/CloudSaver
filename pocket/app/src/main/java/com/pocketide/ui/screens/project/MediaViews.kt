package com.pocketide.ui.screens.project

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Size
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.core.Ist
import com.pocketide.media.MediaItem
import com.pocketide.media.MediaKind
import com.pocketide.ui.components.SelectableText
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.shell.External
import com.pocketide.ui.web.SafeHtmlView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * A session's Media: screenshots, videos, PDFs, HTML reports and APKs the agents and GitHub
 * builds made for the owner. Only safe formats are rendered, each by Android's own decoder.
 */
@Composable
fun MediaPanel(sessionId: String, pendingVideos: Int, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember(sessionId) { graph.media.forSession(sessionId) }
    val items by flow.collectAsStateWithLifecycle(initialValue = null)
    var open by remember(sessionId) { mutableStateOf<MediaItem?>(null) }
    var adding by remember(sessionId) { mutableStateOf(false) }
    // The system file picker: no storage permission, and only the file the owner picks.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            adding = true
            scope.launch {
                finish { graph.media.addFromPhone(sessionId, uri) }
                    .onSuccess { snackbar.showSnackbar("Added ${it.name} to this session's Media.") }
                    .onFailure { snackbar.showSnackbar("Could not add the file: ${plainReason(it)}") }
                adding = false
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        val list = items
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (list != null && list.isNotEmpty()) {
                Text(
                    mediaSummary(list.size, list.sumOf { it.bytes }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            waitingVideosText(pendingVideos)?.let { StatusChip(it, Tone.WARN) }
            if (adding) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = {
                    External.leaving(context)
                    pick.launch(arrayOf("*/*"))
                }) { Text("Add file") }
            }
        }
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState(
                Icons.Filled.PermMedia,
                "No media yet",
                "Screenshots, videos, reports and APKs the agent or a GitHub build makes for you appear here, " +
                    "with files you add. They sync with this session and come back when you reopen it.",
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.file.path }) { item -> MediaTile(item, Modifier.fillMaxWidth()) { open = item } }
            }
        }
    }
    open?.let { item -> MediaViewer(item, onDismiss = { open = null }) }
}

/** A single row of a session's media, for the transcript and session details. */
@Composable
fun MediaStrip(sessionId: String, modifier: Modifier = Modifier) {
    val graph = rememberGraph()
    val flow = remember(sessionId) { graph.media.forSession(sessionId) }
    val items by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    var open by remember(sessionId) { mutableStateOf<MediaItem?>(null) }
    if (items.isEmpty()) return
    Column(modifier) {
        SectionLabel("Media · ${mediaSummary(items.size, items.sumOf { it.bytes })}")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item -> MediaTile(item, Modifier.width(104.dp)) { open = item } }
        }
    }
    open?.let { item -> MediaViewer(item, onDismiss = { open = null }) }
}

@Composable
private fun MediaTile(item: MediaItem, modifier: Modifier, onClick: () -> Unit) {
    val density = LocalDensity.current
    val px = with(density) { 112.dp.roundToPx() }
    val thumb by produceState<Bitmap?>(null, item.file.path, item.onPhone) {
        value = if (item.onPhone) loadThumbnail(item, px) else null
    }
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = thumb
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(kindIcon(item.kind), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
            }
            if (item.kind == MediaKind.VIDEO && bitmap != null) {
                Icon(Icons.Filled.PlayCircle, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(36.dp))
            }
            if (!item.onPhone) {
                Icon(
                    Icons.Filled.CloudDownload,
                    contentDescription = "In your Drive",
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            item.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

private fun kindIcon(kind: MediaKind): ImageVector = when (kind) {
    MediaKind.IMAGE -> Icons.Filled.Image
    MediaKind.VIDEO -> Icons.Filled.PlayCircle
    MediaKind.PDF -> Icons.Filled.PictureAsPdf
    MediaKind.HTML -> Icons.Filled.Code
    MediaKind.APK -> Icons.Filled.Android
    MediaKind.TEXT -> Icons.Filled.Description
    MediaKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private suspend fun loadThumbnail(item: MediaItem, px: Int): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        when (item.kind) {
            MediaKind.IMAGE -> if (MediaLimits.renderable(item.kind, item.bytes)) decodeBitmap(item.file, px) else null
            MediaKind.VIDEO -> ThumbnailUtils.createVideoThumbnail(item.file, Size(px, px), null)
            MediaKind.PDF -> if (MediaLimits.renderable(item.kind, item.bytes)) PdfDocument(item.file).use { it.renderBlocking(0, px) } else null
            else -> null
        }
    }.getOrNull()
}

private fun decodeBitmap(file: File, maxSide: Int): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val (w, h) = boundedSize(info.size.width, info.size.height, maxSide)
        decoder.setTargetSize(w, h)
    }

private fun decodeDrawable(file: File, maxSide: Int): Drawable =
    ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val (w, h) = boundedSize(info.size.width, info.size.height, maxSide)
        decoder.setTargetSize(w, h)
    }

/** A PDF opened read-only; pages are rendered one at a time (PdfRenderer is not thread-safe). */
private class PdfDocument(file: File) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (e: Exception) {
        descriptor.close()
        throw e
    }
    private var closed = false
    val pageCount: Int get() = renderer.pageCount

    /** Renders page [index] [widthPx] wide on a white background; null once closed. */
    fun renderBlocking(index: Int, widthPx: Int): Bitmap? = synchronized(this) {
        if (closed) return null
        renderer.openPage(index).use { page ->
            val width = widthPx.coerceIn(1, MediaLimits.VIEW_SIDE_PX)
            val height = (page.height.toLong() * width / page.width.coerceAtLeast(1)).toInt().coerceIn(1, MediaLimits.VIEW_SIDE_PX * 2)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            renderer.close()
            descriptor.close()
        }
    }
}

/** Full-screen viewer for one media item, with Share, Delete and (for APKs) Install. */
@Composable
fun MediaViewer(item: MediaItem, onDismiss: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${WorkFormat.bytes(item.bytes)} · ${sourceLabel(item.source)} · ${Ist.dateTime(item.createdAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (item.onPhone) {
                            IconButton(onClick = {
                                scope.act(snackbar, "Could not share") { shareItem(context, withContext(Dispatchers.IO) { graph.media.shareUri(item) }, item) }
                            }) { Icon(Icons.Filled.Share, contentDescription = "Share") }
                        }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                    }
                    if (!item.backedUp) {
                        Text(
                            if (item.kind == MediaKind.VIDEO) "Waiting for Wi-Fi to back up to your Drive." else "Not backed up to your Drive yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        MediaBody(item, snackbar)
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${item.name}?",
            text = "It is removed from this session's Media on this phone and in your Drive.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                scope.launch {
                    finish { graph.media.delete(item) }
                        .onSuccess { onDismiss() }
                        .onFailure { snackbar.showSnackbar("Could not delete: ${plainReason(it)}") }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun sourceLabel(source: String): String = when (source) {
    "agent" -> "From the agent"
    "actions" -> "From a GitHub build"
    "you" -> "Added by you"
    else -> source
}

@Composable
private fun MediaBody(item: MediaItem, snackbar: SnackbarHostState) {
    if (!item.onPhone) {
        NotOnPhone(item, snackbar)
        return
    }
    if (!MediaLimits.renderable(item.kind, item.bytes)) {
        Notice(
            if (item.kind == MediaKind.OTHER) {
                "This kind of file is not opened inside PocketIDE, for safety. You can share it to an app you trust."
            } else {
                "This file is too big to show here (${WorkFormat.bytes(item.bytes)}). You can share it to another app."
            },
        )
        return
    }
    when (item.kind) {
        MediaKind.IMAGE -> ImageBody(item.file)
        MediaKind.VIDEO -> VideoBody(item.file)
        MediaKind.PDF -> PdfBody(item.file)
        MediaKind.HTML -> TextFileBody(item.file, MediaLimits.HTML_BYTES) { SafeHtmlView(it, Modifier.fillMaxSize()) }
        MediaKind.TEXT -> TextFileBody(item.file, MediaLimits.TEXT_BYTES) { text ->
            LazyColumn(Modifier.fillMaxSize().padding(16.dp)) { item { SelectableText(text, sizeSp = 13f) } }
        }
        MediaKind.APK -> ApkBody(item, snackbar)
        MediaKind.OTHER -> Unit
    }
}

@Composable
private fun NotOnPhone(item: MediaItem, snackbar: SnackbarHostState) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text("This file is in your Drive, not on this phone right now.", style = MaterialTheme.typography.bodyLarge)
        Button(onClick = {
            scope.act(snackbar, "Could not download", done = "Downloading. It appears here when it is ready.") {
                graph.sync.fetchSession(item.sessionId)
            }
        }) { Text("Download now") }
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(32.dp))
}

@Composable
private fun ImageBody(file: File) {
    val drawable by produceState<Result<Drawable>?>(null, file.path) {
        value = withContext(Dispatchers.IO) { runCatching { decodeDrawable(file, MediaLimits.VIEW_SIDE_PX) } }
    }
    val result = drawable
    when {
        result == null -> CircularProgressIndicator()
        result.isFailure -> Notice("This image could not be shown.")
        else -> when (val d = result.getOrThrow()) {
            is AnimatedImageDrawable -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = { view ->
                    if (view.drawable !== d) {
                        view.setImageDrawable(d)
                        d.start()
                    }
                },
                onRelease = { d.stop() },
            )
            is BitmapDrawable -> ZoomableBitmap(d.bitmap)
            else -> Notice("This image could not be shown.")
        }
    }
}

@Composable
private fun ZoomableBitmap(bitmap: Bitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    Image(
        bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    if (scale == 1f) {
                        offsetX = 0f
                        offsetY = 0f
                    } else {
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
    )
}

@Composable
private fun VideoBody(file: File) {
    var failed by remember(file.path) { mutableStateOf(false) }
    if (failed) {
        Notice("This video could not be played on this phone.")
        return
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            VideoView(ctx).apply {
                val controller = MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                setOnPreparedListener { start() }
                setOnErrorListener { _, _, _ ->
                    failed = true
                    true
                }
                setVideoPath(file.path)
            }
        },
        onRelease = { it.stopPlayback() },
    )
}

@Composable
private fun PdfBody(file: File) {
    val graph = rememberGraph()
    val opened by produceState<Result<PdfDocument>?>(null, file.path) {
        value = withContext(Dispatchers.IO) { runCatching { PdfDocument(file) } }
    }
    DisposableEffect(opened) {
        val doc = opened?.getOrNull()
        onDispose { if (doc != null) graph.scope.launch(Dispatchers.IO) { doc.close() } }
    }
    val result = opened
    when {
        result == null -> CircularProgressIndicator()
        result.isFailure -> Notice("This PDF could not be opened. It may be damaged or password-protected.")
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val doc = result.getOrThrow()
            val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(doc.pageCount) { index -> PdfPage(doc, index, widthPx) }
            }
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDocument, index: Int, widthPx: Int) {
    val page by produceState<Bitmap?>(null, doc, index, widthPx) {
        value = withContext(Dispatchers.IO) { runCatching { doc.renderBlocking(index, widthPx) }.getOrNull() }
    }
    val bitmap = page
    if (bitmap == null) {
        Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        Image(bitmap.asImageBitmap(), contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    }
}

@Composable
private fun TextFileBody(file: File, limit: Long, content: @Composable (String) -> Unit) {
    val text by produceState<Result<String>?>(null, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                require(file.length() <= limit) { "too big" }
                file.readText()
            }
        }
    }
    val result = text
    when {
        result == null -> CircularProgressIndicator()
        result.isFailure -> Notice("This file could not be shown.")
        else -> content(result.getOrThrow())
    }
}

/** What an APK says about itself, read before anything is installed. */
private data class ApkFacts(val label: String?, val packageName: String, val versionName: String?, val versionCode: Long, val signers: List<String>)

@Composable
private fun ApkBody(item: MediaItem, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val facts by produceState<Result<ApkFacts>?>(null, item.file.path) {
        value = withContext(Dispatchers.IO) { runCatching { readApk(context, item.file) } }
    }
    val result = facts
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            result == null -> CircularProgressIndicator()
            result.isFailure -> Notice("This APK could not be read. It may be damaged, so it will not be installed.")
            else -> {
                val apk = result.getOrThrow()
                Text(apk.label ?: apk.packageName, style = MaterialTheme.typography.headlineSmall)
                FactLine("Package", apk.packageName)
                FactLine("Version", "${apk.versionName ?: "?"} (${apk.versionCode})")
                if (apk.signers.isEmpty()) {
                    FactLine("Signer SHA-256", "Not signed")
                } else {
                    apk.signers.forEach { FactLine("Signer SHA-256", it) }
                }
                Text(
                    "Check that the package and signer are what you expect. Android's installer and Play Protect check it again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = apk.signers.isNotEmpty(), onClick = {
                        scope.launch {
                            attempt { withContext(Dispatchers.IO) { graph.media.shareUri(item) } }
                                .onSuccess { uri -> install(context, uri)?.let { snackbar.showSnackbar(it) } }
                                .onFailure { snackbar.showSnackbar("Could not start the install: ${plainReason(it)}") }
                        }
                    }) { Text("Install") }
                    OutlinedButton(onClick = {
                        scope.act(snackbar, "Could not share") { shareItem(context, withContext(Dispatchers.IO) { graph.media.shareUri(item) }, item) }
                    }) { Text("Share") }
                }
            }
        }
    }
}

@Composable
private fun FactLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectableText(value, sizeSp = 14f)
    }
}

private fun readApk(context: Context, file: File): ApkFacts {
    val pm = context.packageManager
    // Both flags: with the first alone, signingInfo is null on API 29 and on the first Android 13 release.
    @Suppress("DEPRECATION")
    val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
    val info: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageArchiveInfo(file.path, flags)
    }
    requireNotNull(info) { "not an APK" }
    @Suppress("DEPRECATION")
    val signers = signerFingerprints(
        info.signingInfo?.apkContentsSigners?.map { it.toByteArray() },
        info.signatures?.map { it.toByteArray() },
    )
    val label = info.applicationInfo?.let { app ->
        app.sourceDir = file.path
        app.publicSourceDir = file.path
        runCatching { pm.getApplicationLabel(app).toString() }.getOrNull()
    }
    return ApkFacts(label, info.packageName, info.versionName, info.longVersionCode, signers)
}

/** Hands the APK to Android's installer; returns a message when the owner must allow installs first. */
private fun install(context: Context, uri: Uri): String? {
    if (uri == Uri.EMPTY) return "This file is not ready to install yet."
    if (!context.packageManager.canRequestPackageInstalls()) {
        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        return try {
            External.leaving(context)
            context.startActivity(settings)
            "Allow PocketIDE to install apps, then come back and tap Install again."
        } catch (_: ActivityNotFoundException) {
            "Allow installs from PocketIDE in Android's settings, then tap Install again."
        }
    }
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, APK_MIME)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        External.leaving(context)
        context.startActivity(view)
        null
    } catch (_: ActivityNotFoundException) {
        "This phone has no installer that can open the file."
    }
}

private fun shareItem(context: Context, uri: Uri, item: MediaItem) {
    check(uri != Uri.EMPTY) { "This file is not ready to share yet." }
    val send = Intent(Intent.ACTION_SEND)
        .setType(shareMime(item.kind, item.name))
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(item.name, uri)
    External.leaving(context)
    context.startActivity(Intent.createChooser(send, "Share ${item.name}"))
}
