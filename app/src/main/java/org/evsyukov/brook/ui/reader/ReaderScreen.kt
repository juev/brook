package org.evsyukov.brook.ui.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
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

object ReaderTags {
    const val STAR = "reader-star"
    const val FULL_TEXT = "reader-full-text"
    const val MORE = "reader-more"
    const val MARK_UNREAD = "reader-mark-unread"
    const val LABELS = "reader-labels"
    const val OPEN = "reader-open"
    const val SHARE = "reader-share"
    const val NEW_LABEL = "reader-new-label"
    const val ADD_LABEL = "reader-add-label"
    fun label(name: String) = "reader-label-$name"
}

/** The entry; [onBack] is set when the list is not beside it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(vm: ReaderViewModel, onBack: (() -> Unit)?) {
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
                onToggleStar = { vm.setStarred(!(state.entry?.isStarred ?: false)) },
                onToggleFullText = vm::toggleFullText,
                onMarkUnread = {
                    vm.markUnread()
                    onBack?.invoke()
                },
                onLabels = { labelsOpen = true },
                onOpenOriginal = { state.entry?.url?.let { openLink(context, it) } },
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
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
            ),
            url = entry.url,
            body = body,
            enclosures = decodeEnclosures(entry.enclosuresJson),
            colors = readerColors,
        )
        ReaderWebView(
            html = html,
            baseUrl = entry.url.ifBlank { null },
            images = vm.images,
            onLink = { openLink(context, it) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    state: ReaderState,
    onBack: (() -> Unit)?,
    onToggleStar: () -> Unit,
    onToggleFullText: () -> Unit,
    onMarkUnread: () -> Unit,
    onLabels: () -> Unit,
    onOpenOriginal: () -> Unit,
    onShare: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val entry = state.entry
    TopAppBar(
        title = {},
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
        },
        actions = {
            if (entry == null) return@TopAppBar
            if (state.loadingFullText) {
                Box(Modifier.padding(12.dp)) { CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp) }
            } else {
                IconToggleButton(
                    checked = state.showFullText,
                    onCheckedChange = { onToggleFullText() },
                    modifier = Modifier.testTag(ReaderTags.FULL_TEXT),
                ) {
                    Icon(
                        if (state.showFullText) Icons.AutoMirrored.Filled.Article else Icons.AutoMirrored.Outlined.Article,
                        contentDescription = stringResource(R.string.full_text),
                    )
                }
            }
            IconToggleButton(
                checked = entry.isStarred,
                onCheckedChange = { onToggleStar() },
                modifier = Modifier.testTag(ReaderTags.STAR),
            ) {
                Icon(
                    if (entry.isStarred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = stringResource(R.string.starred),
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag(ReaderTags.MORE)) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(R.string.mark_unread, Icons.Outlined.MarkEmailUnread, ReaderTags.MARK_UNREAD) { menu = false; onMarkUnread() }
                    MenuItem(R.string.labels, Icons.AutoMirrored.Outlined.Label, ReaderTags.LABELS) { menu = false; onLabels() }
                    if (entry.url.isNotBlank()) {
                        MenuItem(R.string.open_original, Icons.Outlined.OpenInBrowser, ReaderTags.OPEN) { menu = false; onOpenOriginal() }
                        MenuItem(R.string.share, Icons.Outlined.Share, ReaderTags.SHARE) { menu = false; onShare() }
                    }
                }
            }
        },
    )
}

@Composable
private fun MenuItem(text: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(text)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
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
 * way when missing; links open outside.
 */
@Composable
private fun ReaderWebView(
    html: String,
    baseUrl: String?,
    images: ImageCache,
    onLink: (String) -> Unit,
    modifier: Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.allowFileAccess = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = ReaderWebViewClient(images, onLink)
            }
        },
        update = { view ->
            // Recomposition must not reload the same page: that would scroll back to the top.
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            }
        },
    )
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
        val accept = request.requestHeaders["Accept"].orEmpty()
        if (!accept.startsWith("image/")) return null
        val image = images.get(url.toString()) ?: return null
        return WebResourceResponse(image.mimeType, null, FileInputStream(image.file))
    }
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
