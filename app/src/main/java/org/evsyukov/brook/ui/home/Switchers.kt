package org.evsyukov.brook.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.evsyukov.brook.R
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.account.Account

object SwitcherTags {
    const val ACCOUNT = "account-switcher"
    const val ADD_ACCOUNT = "account-add"
    const val REMOVE_ACCOUNT = "account-remove"
    fun account(id: String) = "account-$id"
    fun filter(filter: Filter) = "filter-${filter.name}"
}

/** The head of the tree: the active account; a tap opens the others, adding and removing. */
@Composable
fun AccountSwitcher(
    accounts: List<Account>,
    active: Account,
    onSelect: (Account) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Account) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(horizontal = 28.dp, vertical = 16.dp)
                .testTag(SwitcherTags.ACCOUNT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(active.user, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    active.host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.accounts))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (account in accounts) {
                DropdownMenuItem(
                    text = { Text("${account.user} · ${account.host}") },
                    leadingIcon = { if (account.id == active.id) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = {
                        open = false
                        if (account.id != active.id) onSelect(account)
                    },
                    modifier = Modifier.testTag(SwitcherTags.account(account.id)),
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.account_add)) },
                leadingIcon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                onClick = {
                    open = false
                    onAdd()
                },
                modifier = Modifier.testTag(SwitcherTags.ADD_ACCOUNT),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.account_remove)) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    open = false
                    onRemove(active)
                },
                modifier = Modifier.testTag(SwitcherTags.REMOVE_ACCOUNT),
            )
        }
    }
}

/** All / Unread / Starred. */
@Composable
fun FilterSwitcher(filter: Filter, onSelect: (Filter) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(
        Filter.ALL to R.string.filter_all,
        Filter.UNREAD to R.string.filter_unread,
        Filter.STARRED to R.string.filter_starred,
    )
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = filter == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.testTag(SwitcherTags.filter(value)),
            ) {
                Text(stringResource(label), maxLines = 1)
            }
        }
    }
}
