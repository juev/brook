package org.evsyukov.brook.ui.home

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.evsyukov.brook.R
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.nextOnShortPress

object ModeTags {
    const val BUTTON = "mode-button"
    const val MENU = "mode-menu"
    fun option(filter: Filter) = "mode-option-${filter.name}"
}

/** The glyph of the active mode; it shows the mode, not the destination of the next tap. */
fun Filter.icon(): ImageVector = when (this) {
    Filter.ALL -> Icons.Default.Layers
    Filter.UNREAD -> Icons.Outlined.MarkEmailUnread
    Filter.STARRED -> Icons.Default.Star
}

fun Filter.label(): Int = when (this) {
    Filter.ALL -> R.string.filter_all
    Filter.UNREAD -> R.string.filter_unread
    Filter.STARRED -> R.string.filter_starred
}

/** The label of the picker section that names the mode the flat feed list is in. */
fun Filter.paneTitle(): Int = when (this) {
    Filter.ALL -> R.string.feed_pane_all
    Filter.UNREAD -> R.string.feed_pane_unread
    Filter.STARRED -> R.string.feed_pane_starred
}

/**
 * The one mode control: a short press moves through the shortcuts, a long press opens the same
 * three-choice menu the overflow exposes. The menu state lives in the screen so both routes open
 * one menu; opening or dismissing it never changes the mode by itself.
 */
@Composable
fun ModeButton(
    filter: Filter,
    menuOpen: Boolean,
    onShortPress: () -> Unit,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onSelect: (Filter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = stringResource(filter.label())
    val description = stringResource(R.string.view_mode_state, current)
    val tap = stringResource(R.string.view_mode_tap, stringResource(filter.nextOnShortPress().label()))
    val hold = stringResource(R.string.view_mode_long_press)
    Box(modifier) {
        Box(
            Modifier
                .size(48.dp)
                .testTag(ModeTags.BUTTON)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = tap,
                    onLongClickLabel = hold,
                    onLongClick = onMenuOpen,
                    onClick = onShortPress,
                )
                .semantics {
                    contentDescription = description
                    stateDescription = current
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(filter.icon(), contentDescription = null)
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onMenuDismiss,
            modifier = Modifier.testTag(ModeTags.MENU),
        ) {
            for (option in Filter.entries) {
                val active = option == filter
                DropdownMenuItem(
                    text = { Text(stringResource(option.label())) },
                    leadingIcon = { if (active) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = { onSelect(option) },
                    modifier = Modifier
                        .testTag(ModeTags.option(option))
                        .semantics { selected = active },
                )
            }
        }
    }
}
