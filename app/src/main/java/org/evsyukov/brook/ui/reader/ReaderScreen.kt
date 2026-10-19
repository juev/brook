package org.evsyukov.brook.ui.reader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.evsyukov.brook.R
import org.evsyukov.brook.data.images.ImageCache
import org.evsyukov.brook.sync.decodeEnclosures
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The entry in [style]; [onBack] is set when the list is not beside it. [onNext] and [onPrevious]
 * open the entries below and above it in its list, null at an end.
 */
@Composable
fun ReaderScreen(
    vm: ReaderViewModel,
    onBack: (() -> Unit)?,
    style: ReaderStyle = ReaderStyle(),
    onNext: (() -> Unit)? = null,
    onPrevious: (() -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val failedText = stringResource(R.string.full_text_failed)
    var labelsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.fullTextFailures.collect { snackbar.showSnackbar(failedText) } }

    Scaffold(
        topBar = {
            ReaderTopBar(
                state = state,
                onBack = onBack,
                onLabels = { labelsOpen = true },
                onOpenOriginal = { state.entry?.url?.let { openLink(context, it) } },
            )
        },
        bottomBar = {
            ReaderBottomBar(
                state = state,
                onToggleRead = { state.entry?.let { vm.setRead(!it.isRead) } },
                onToggleStar = { vm.setStarred(!(state.entry?.isStarred ?: false)) },
                onNext = onNext,
                onToggleFullText = vm::toggleFullText,
                onShare = { state.entry?.let { share(context, it.title, it.url) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val entry = state.entry
        if (entry == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        val colors = MaterialTheme.colorScheme
        val readerColors = ReaderColors(
            background = colors.surface.css(),
            text = colors.onSurface.css(),
            secondary = colors.onSurfaceVariant.css(),
            link = colors.primary.css(),
            surface = colors.surfaceContainerHigh.css(),
        )
        val body = if (state.showFullText) entry.fullContent.orEmpty() else entry.content
        val html = readerHtml(
            title = entry.title,
            feedTitle = state.feedTitle,
            author = entry.author,
            date = DateUtils.formatDateTime(
                context,
                entry.sortTime * 1000,
                DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
            ),
            url = entry.url,
            body = body,
            enclosures = decodeEnclosures(entry.enclosuresJson),
            colors = readerColors,
            style = style,
        )
        ReaderWebView(
            html = html,
            baseUrl = entry.url.ifBlank { null },
            images = vm.images,
            onLink = { openLink(context, it) },
            onNext = onNext,
            onPrevious = onPrevious,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }

    if (labelsOpen) {
        LabelsDialog(
            allLabels = state.allLabels,
            selected = state.labels.toSet(),
            onToggle = vm::setLabel,
            onDismiss = { labelsOpen = false },
        )
    }
}

/** Labels of the entry: a tick toggles one, the field adds a new one. */
@Composable
fun LabelsDialog(
    allLabels: List<String>,
    selected: Set<String>,
    onToggle: (label: String, on: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var newLabel by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.labels)) },
        text = {
            Column {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(allLabels, key = { it }) { label ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = label in selected,
                                onCheckedChange = { onToggle(label, it) },
                                modifier = Modifier.testTag(ReaderTags.label(label)),
                            )
                            Text(label)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        label = { Text(stringResource(R.string.label_new)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag(ReaderTags.NEW_LABEL),
                    )
                    TextButton(
                        onClick = {
                            onToggle(newLabel.trim(), true)
                            newLabel = ""
                        },
                        enabled = newLabel.isNotBlank(),
                        modifier = Modifier.testTag(ReaderTags.ADD_LABEL),
                    ) { Text(stringResource(R.string.label_add)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

/**
 * The page of the entry. Scripts are off; pictures come from [images], downloaded into it on the
 * way when missing, and fonts from the assets; links open outside. A pull past the bottom opens
 * [onNext], past the top [onPrevious]; a label says which while the finger is down.
 */
@Composable
private fun ReaderWebView(
    html: String,
    baseUrl: String?,
    images: ImageCache,
    onLink: (String) -> Unit,
    onNext: (() -> Unit)?,
    onPrevious: (() -> Unit)?,
    modifier: Modifier,
) {
    var pullEdge by remember { mutableStateOf<EdgePull.Edge?>(null) }
    var pullProgress by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { PULL_THRESHOLD.toPx() }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                EntryWebView(context, threshold).apply {
                    settings.javaScriptEnabled = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.allowFileAccess = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webViewClient = ReaderWebViewClient(images, onLink)
                }
            },
            update = { view ->
                view.onPull = { edge, progress ->
                    // A pull toward an end with nothing beyond it shows nothing.
                    val target = when (edge) {
                        EdgePull.Edge.BOTTOM -> onNext
                        EdgePull.Edge.TOP -> onPrevious
                        null -> null
                    }
                    pullEdge = edge.takeIf { target != null }
                    pullProgress = progress
                }
                view.onRelease = { edge ->
                    when (edge) {
                        EdgePull.Edge.BOTTOM -> onNext?.invoke()
                        EdgePull.Edge.TOP -> onPrevious?.invoke()
                    }
                }
                // Recomposition must not reload the same page: that would scroll back to the top.
                if (view.tag != html) {
                    view.tag = html
                    view.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
                }
            },
        )
        val edge = pullEdge
        if (edge != null) {
            PullLabel(
                edge = edge,
                ready = pullProgress >= 1f,
                modifier = Modifier
                    .align(if (edge == EdgePull.Edge.BOTTOM) Alignment.BottomCenter else Alignment.TopCenter)
                    .padding(16.dp)
                    .alpha(pullProgress.coerceIn(0.3f, 1f)),
            )
        }
    }
}

private val PULL_THRESHOLD = 96.dp

@Composable
private fun PullLabel(edge: EdgePull.Edge, ready: Boolean, modifier: Modifier) {
    val next = edge == EdgePull.Edge.BOTTOM
    Surface(
        shape = CircleShape,
        color = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 2.dp,
        modifier = modifier.testTag(ReaderTags.PULL),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (next) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, contentDescription = null)
            Text(
                stringResource(
                    when {
                        next && ready -> R.string.pull_release_next
                        next -> R.string.next_entry
                        ready -> R.string.pull_release_previous
                        else -> R.string.previous_entry
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** A WebView that tells about pulls past the ends of its page, as [EdgePull] sees them. */
@SuppressLint("ViewConstructor")
private class EntryWebView(context: Context, threshold: Float) : WebView(context) {
    private val pull = EdgePull(threshold)
    private var lastY = 0f
    var onPull: (EdgePull.Edge?, Float) -> Unit = { _, _ -> }
    var onRelease: (EdgePull.Edge) -> Unit = {}

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pull.start()
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                pull.move(event.y - lastY, atTop = !canScrollVertically(-1), atBottom = !canScrollVertically(1))
                lastY = event.y
                onPull(pull.edge, pull.progress)
            }
            MotionEvent.ACTION_UP -> {
                pull.end()?.let(onRelease)
                onPull(null, 0f)
            }
            MotionEvent.ACTION_CANCEL -> {
                pull.start()
                onPull(null, 0f)
            }
        }
        return super.onTouchEvent(event)
    }
}

private class ReaderWebViewClient(
    private val images: ImageCache,
    private val onLink: (String) -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        onLink(request.url.toString())
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (request.isForMainFrame || request.method != "GET" || url.scheme !in setOf("http", "https")) return null
        if (url.toString().startsWith(READER_ASSETS)) return readerAsset(url.toString(), view.context.assets::open)
        val accept = request.requestHeaders["Accept"].orEmpty()
        if (!accept.startsWith("image/")) return null
        val image = images.get(url.toString()) ?: return null
        return WebResourceResponse(image.mimeType, null, FileInputStream(image.file))
    }
}

/**
 * A bundled font the page asks for under [READER_ASSETS], from [open]; null for anything else there.
 * The page comes from the address of the entry, so the font is served to any origin.
 */
internal fun readerAsset(url: String, open: (String) -> InputStream): WebResourceResponse? {
    val path = url.removePrefix(READER_ASSETS)
    if (!path.startsWith("fonts/") || !path.endsWith(".woff2") || path.contains("..")) return null
    val stream = try {
        open(path)
    } catch (e: IOException) {
        return null
    }
    return WebResourceResponse("font/woff2", null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), stream)
}

private fun Color.css(): String = "#%06X".format(toArgb() and 0xFFFFFF)

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // Nothing opens such links; the reader stays where it is.
    }
}

private fun share(context: Context, title: String, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, url.ifBlank { title })
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
