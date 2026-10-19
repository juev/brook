package org.evsyukov.brook.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import org.evsyukov.brook.R

object ReaderTags {
    const val READ = "reader-read"
    const val STAR = "reader-star"
    const val NEXT = "reader-next"
    const val FULL_TEXT = "reader-full-text"
    const val SHARE = "reader-share"
    const val MORE = "reader-more"
    const val LABELS = "reader-labels"
    const val OPEN = "reader-open"
    const val NEW_LABEL = "reader-new-label"
    const val ADD_LABEL = "reader-add-label"
    const val PULL = "reader-pull"
    fun label(name: String) = "reader-label-$name"
}

/** Back, when the list is not beside the entry, and the actions used now and then. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    state: ReaderState,
    onBack: (() -> Unit)?,
    onLabels: () -> Unit,
    onOpenOriginal: () -> Unit,
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
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag(ReaderTags.MORE)) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(R.string.labels, Icons.AutoMirrored.Outlined.Label, ReaderTags.LABELS) { menu = false; onLabels() }
                    if (entry.url.isNotBlank()) {
                        MenuItem(R.string.open_original, Icons.Outlined.OpenInBrowser, ReaderTags.OPEN) { menu = false; onOpenOriginal() }
                    }
                }
            }
        },
    )
}

/** The actions used on every entry: read, star, the next entry, full text, share. */
@Composable
fun ReaderBottomBar(
    state: ReaderState,
    onToggleRead: () -> Unit,
    onToggleStar: () -> Unit,
    onNext: (() -> Unit)?,
    onToggleFullText: () -> Unit,
    onShare: () -> Unit,
) {
    val entry = state.entry ?: return
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider()
            // On a wide pane the actions keep together in the middle instead of spreading to the edges.
            Row(
                Modifier.fillMaxWidth().wrapContentWidth().widthIn(max = 480.dp).fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconToggleButton(
                    checked = !entry.isRead,
                    onCheckedChange = { onToggleRead() },
                    modifier = Modifier.testTag(ReaderTags.READ),
                ) {
                    Icon(
                        if (entry.isRead) Icons.Outlined.Circle else Icons.Filled.Circle,
                        contentDescription = stringResource(if (entry.isRead) R.string.mark_unread else R.string.mark_read),
                    )
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
                IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null, modifier = Modifier.testTag(ReaderTags.NEXT)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.next_entry))
                }
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
                IconButton(onClick = onShare, enabled = entry.url.isNotBlank(), modifier = Modifier.testTag(ReaderTags.SHARE)) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.share))
                }
            }
        }
    }
}

@Composable
private fun MenuItem(text: Int, icon: ImageVector, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(text)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}
