package org.evsyukov.brook.ui.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Parcelable
import android.text.format.DateUtils
import android.view.MotionEvent
import android.view.ViewGroup
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.evsyukov.brook.R
import org.evsyukov.brook.data.images.ImageCache
import org.evsyukov.brook.data.settings.ReaderTheme
import org.evsyukov.brook.sync.decodeEnclosures
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The entry in [style]; [onBack] is set when the list is not beside it. [onNext] and [onPrevious]
 * open the entries below and above it in its list, null at an end. [page] is the WebView every
 * entry is shown in: built once, it is not what the slide that brings the reader in has to pay for.
 */
@Composable
fun ReaderScreen(
    vm: ReaderViewModel,
    page: ReaderPage,
    onBack: (() -> Unit)?,
    style: ReaderStyle = ReaderStyle(),
    onNext: (() -> Unit)? = null,
    onPrevious: (() -> Unit)? = null,
) {
    // The page outlives the reader: leaving the entry retires a link still being found for it
    // without taking the page down, as the next entry opens in the same one.
    DisposableEffect(page) { onDispose { page.release() } }
    // The theme of the reader colors the bars too, so the page does not sit in a frame of other colors.
    MaterialTheme(colorScheme = readerColorScheme(style.theme, MaterialTheme.colorScheme)) {
        // Beside the list, the status bar is above both; it keeps the icons of the app.
        if (onBack != null) SystemBarIcons(style.theme)
        ReaderContent(vm, page, onBack, style, onNext, onPrevious)
    }
}

/** Icons of the system bars that stay readable on the background of [theme] while the reader is on screen. */
@Composable
private fun SystemBarIcons(theme: ReaderTheme) {
    val palette = readerPalette(theme) ?: return
    val view = LocalView.current
    val window = view.context.findActivity()?.window ?: return
    DisposableEffect(palette, window) {
        val controller = WindowCompat.getInsetsController(window, view)
        val status = controller.isAppearanceLightStatusBars
        val navigation = controller.isAppearanceLightNavigationBars
        val light = palette.background.luminance() > 0.5f
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
        onDispose {
            controller.isAppearanceLightStatusBars = status
            controller.isAppearanceLightNavigationBars = navigation
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun ReaderContent(
    vm: ReaderViewModel,
    page: ReaderPage,
    onBack: (() -> Unit)?,
    style: ReaderStyle,
    onNext: (() -> Unit)?,
    onPrevious: (() -> Unit)?,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val failedText = stringResource(R.string.full_text_failed)
    val resources = LocalResources.current
    var labelsOpen by remember { mutableStateOf(false) }
    var translationOptions by remember(vm, state.entry?.id) { mutableStateOf(false) }
    var translationPrefs by remember(vm, state.entry?.id) { mutableStateOf<org.evsyukov.brook.data.translation.TranslationPreferences?>(null) }

    LaunchedEffect(vm) { vm.fullTextFailures.collect { snackbar.showSnackbar(failedText) } }
    LaunchedEffect(vm, resources) { vm.translationNotices.collect { snackbar.showSnackbar(resources.getString(it)) } }
    LaunchedEffect(vm, state.entry?.id, translationOptions, state.translationSetup) {
        if (translationOptions || state.translationSetup) translationPrefs = vm.translationPreferences()
        else translationPrefs = null
    }
    DisposableEffect(vm) { onDispose { vm.showOriginal() } }

    Scaffold(
        topBar = {
            Column {
            ReaderTopBar(
                state = state,
                onBack = onBack,
                onLabels = { labelsOpen = true },
                // The menu action keeps the chooser it had: this change is about the gestures on a link.
                onOpenOriginal = { state.entry?.url?.let { openChooser(context, it) } },
                onTranslate = {
                    if (state.translation != null || state.loadingTranslation) vm.showOriginal() else vm.translate()
                },
                onTranslationSettings = { translationOptions = true },
            )
            if (state.loadingTranslation) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.translation_progress,state.translationDone,state.translationTotal),modifier=Modifier.padding(horizontal=16.dp))
            }
            }
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
        val body = state.displayBody
        val title = state.displayTitle
        // One document per entry and style: the page is a whole html, not something to build again on
        // every recomposition of the reader (a label toggled, a bar that comes back).
        val html = remember(entry, title, body, state.feedTitle, readerColors, style) {
            readerHtml(
                title = title,
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
        }
        ReaderPageView(
            page = page,
            html = html,
            baseUrl = entry.url.ifBlank { null },
            onLink = { openLink(context, it) },
            onLinkLongPress = { openChooser(context, it) },
            onNext = onNext,
            onPrevious = onPrevious,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }

    translationPrefs?.takeIf { translationOptions || state.translationSetup }?.let { prefs ->
        TranslationDialog(
            initialLanguage=prefs.language,
            initialConsent=prefs.consent,
            onConfirm={ language, consent -> translationOptions=false; vm.dismissTranslationSetup(); vm.configureTranslation(language,consent) },
            onClear={ translationOptions=false; vm.dismissTranslationSetup(); vm.clearTranslations() },
            onDismiss={ translationOptions=false; vm.dismissTranslationSetup() },
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
 * The page of the entry: [page], loaded with [html]. Scripts are off; pictures come from the
 * cache of the page, downloaded into it on the way when missing, and fonts from the assets; a
 * tapped link opens in [onLink], a long-pressed one offers the apps that take it in
 * [onLinkLongPress]. A pull past the bottom opens [onNext], past the top [onPrevious]; a label
 * says which while the finger is down.
 */
@Composable
private fun ReaderPageView(
    page: ReaderPage,
    html: String,
    baseUrl: String?,
    onLink: (String) -> Unit,
    onLinkLongPress: (String) -> Unit,
    onNext: (() -> Unit)?,
    onPrevious: (() -> Unit)?,
    modifier: Modifier,
) {
    var pullEdge by remember { mutableStateOf<EdgePull.Edge?>(null) }
    var pullProgress by remember { mutableFloatStateOf(0f) }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { page.attach() },
            update = { view ->
                // The page is reused: every entry brings the callbacks of the screen showing it.
                view.onLink = onLink
                view.onLinkLongPress = onLinkLongPress
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
                page.load(html, baseUrl)
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

/**
 * The page of the reader: one WebView, built the first time it is asked for and then handed to
 * every entry. A WebView built in the frame an entry opens costs more than the frames of the slide
 * that brings the reader in, so the screen [warm]s the page while the list sits still and only
 * swaps the document when an entry opens.
 */
class ReaderPage internal constructor(
    private val context: Context,
    images: ImageCache,
    private val threshold: Float,
    private val requestHref: (WebView, Message) -> Unit = { view, message -> view.requestFocusNodeHref(message) },
) {
    private val client = ReaderWebViewClient(images)
    private var web: EntryWebView? = null
    private var held: String? = null
    private var heldBase: String? = null

    /** How many documents the page was given; a page shown again is not the same showing. */
    internal var loads = 0
        private set

    /**
     * The page as the screen takes it. Shown again, it holds nothing: the entry opens at its top,
     * and a view another holder left keeps working, as it is taken from that holder first.
     */
    internal fun attach(): EntryWebView {
        held = null
        heldBase = null
        // A link answer for the entry that was here must not open in the entry that comes next.
        web?.invalidateLinkHit()
        return view.also { previous -> (previous.parent as? ViewGroup)?.removeView(previous) }
    }

    /** Builds the page before an entry needs it. */
    fun warm() {
        view
    }

    /** Loads [html] into the page unless it holds it already. */
    fun load(html: String, baseUrl: String?) {
        if (html == held && baseUrl == heldBase) return
        held = html
        heldBase = baseUrl
        loads++
        web?.invalidateLinkHit()
        view.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
    }

    /**
     * The reader left the screen: a link still being found for it must not open later. The page
     * itself stays, because the next entry opens in the same one.
     */
    fun release() {
        web?.invalidateLinkHit()
    }

    /** Takes the page down: with the home screen, not with an entry. */
    fun destroy() {
        val gone = web ?: return
        gone.invalidateLinkHit()
        (gone.parent as? ViewGroup)?.removeView(gone)
        gone.destroy()
        web = null
        held = null
        heldBase = null
    }

    private val view: EntryWebView
        get() = web ?: EntryWebView(context, threshold, requestHref).apply {
            settings.javaScriptEnabled = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.allowFileAccess = false
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            webViewClient = client
        }.also { web = it }
}

/** The page of the reader, taken down with the screen that holds it. */
@Composable
fun rememberReaderPage(images: ImageCache): ReaderPage {
    val context = LocalContext.current
    val threshold = with(LocalDensity.current) { PULL_THRESHOLD.toPx() }
    val page = remember(context, images, threshold) { ReaderPage(context, images, threshold) }
    DisposableEffect(page) { onDispose { page.destroy() } }
    return page
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

/**
 * A WebView that tells about pulls past the ends of its page, as [EdgePull] sees them, and about
 * the link under a long press. The platform finds the long press; the page tells which link is
 * under the finger.
 */
@SuppressLint("ViewConstructor")
internal class EntryWebView(
    context: Context,
    threshold: Float,
    requestHref: (WebView, Message) -> Unit = { view, message -> view.requestFocusNodeHref(message) },
) : WebView(context) {
    private val pull = EdgePull(threshold)
    private val linkHit = LinkHitTest(this, requestHref)
    private var lastY = 0f
    var onPull: (EdgePull.Edge?, Float) -> Unit = { _, _ -> }
    var onRelease: (EdgePull.Edge) -> Unit = {}

    /** A tap on a link: the app that takes it opens the link. */
    var onLink: (String) -> Unit = {}

    /** A long press on a link: the apps that take a link are offered. */
    var onLinkLongPress: (String) -> Unit = {}

    /** A long press already opened its chooser; the release must not open the link a second time. */
    private var longPressHandled = false

    init {
        setOnLongClickListener { handleLongPress() }
    }

    /** A navigation the page reports for a tap, unless the same touch already was a long press. */
    internal fun tap(url: String) {
        if (longPressHandled) {
            longPressHandled = false
            return
        }
        onLink(url)
    }

    /** Drops a link the page is still finding: the entry or the touch it belongs to is gone. */
    internal fun invalidateLinkHit() {
        linkHit.invalidate()
    }

    private fun handleLongPress(): Boolean {
        val hit = hitTestResult ?: return false
        return when (hit.type) {
            WebView.HitTestResult.ANCHOR_TYPE,
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                val url = hit.extra?.takeIf { it.isHttpUrl() } ?: return false
                longPressHandled = true
                onLinkLongPress(url)
                true
            }
            // A picture inside a link is a link too. Its destination is not in the hit test
            // result: the page reports the href of the focused node, and the answer may come
            // after the reader has moved on.
            WebView.HitTestResult.IMAGE_ANCHOR_TYPE,
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                longPressHandled = true
                linkHit.find { url -> url?.let { onLinkLongPress(it) } }
                true
            }
            else -> false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pull.start()
                lastY = event.y
                // A new touch is a new gesture: a long press and a link still being found for
                // the previous one are done with.
                longPressHandled = false
                linkHit.invalidate()
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
                // The touch was taken away: a link still being found for it is not wanted.
                linkHit.invalidate()
                onPull(null, 0f)
            }
        }
        return super.onTouchEvent(event)
    }
}

/**
 * The link under the last touch. An anchor is in the hit test result at once; a linked picture
 * only through the href of the focused node, which the page sends back asynchronously and which
 * may be stale by the time it arrives.
 */
private class LinkHitTest(
    private val web: WebView,
    private val requestHref: (WebView, Message) -> Unit,
) {
    private var generation = 0

    /** Drops an answer still on its way: the page or the touch it belongs to is gone. */
    fun invalidate() {
        generation++
    }

    /** Calls [found] with the destination of the link under the finger, null when there is none. */
    fun find(found: (String?) -> Unit) {
        val hit = web.hitTestResult ?: return found(null)
        when (hit.type) {
            WebView.HitTestResult.ANCHOR_TYPE,
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> found(hit.extra?.takeIf { it.isHttpUrl() })
            WebView.HitTestResult.IMAGE_ANCHOR_TYPE,
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val request = ++generation
                val handler = Handler(Looper.getMainLooper()) { message ->
                    if (request == generation) {
                        found(message.data.getString(HREF)?.takeIf { it.isHttpUrl() })
                    }
                    true
                }
                requestHref(web, Message.obtain(handler))
            }
            else -> found(null)
        }
    }

    private companion object {
        /** The key the page puts the destination of the focused link under. */
        const val HREF = "url"
    }
}

internal class ReaderWebViewClient(private val images: ImageCache) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        // The page never navigates inside the reader: a tapped link goes to the app that takes
        // it, and a scheme no app here takes is left alone.
        if (url.isHttpUrl()) (view as? EntryWebView)?.tap(url)
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

private val HTTP_SCHEMES = setOf("http", "https")

/** Whether another app can take [this] as a link: HTTP or HTTPS. */
internal fun String.isHttpUrl(): Boolean {
    val scheme = toUri().scheme ?: return false
    return scheme.lowercase() in HTTP_SCHEMES
}

/** The intent of a tap on a link: the link itself, for the app that takes it by default. */
internal fun linkIntent(url: String): Intent =
    Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * Opens a tapped link in the app that takes it. No chooser of ours: the system shows one only
 * when the reader has no default, and nothing at all when no app takes the link.
 */
internal fun openLink(context: Context, url: String) {
    if (!url.isHttpUrl()) return
    try {
        context.startActivity(linkIntent(url))
    } catch (e: ActivityNotFoundException) {
        // Nothing opens such links; the reader stays where it is.
    }
}

// A long press offers the apps that take a link, and an app may keep or post it: such apps take a
// link only as shared text, so the share intent travels as an alternate and the chooser lists
// them beside the browsers.
internal fun linkChooser(url: String): Intent {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    return Intent.createChooser(Intent(Intent.ACTION_VIEW, url.toUri()), null)
        .putExtra(Intent.EXTRA_ALTERNATE_INTENTS, arrayOf<Parcelable>(send))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

private fun openChooser(context: Context, url: String) {
    try {
        context.startActivity(linkChooser(url))
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
