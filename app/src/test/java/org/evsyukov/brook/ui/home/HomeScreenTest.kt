package org.evsyukov.brook.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import org.evsyukov.brook.R
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.Scope
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.ui.list.EntryRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pickingAFilterInTheDrawerClosesItAndKeepsTheScope() {
        val scopes = mutableListOf<Scope>()
        val account = Account("a", "http://localhost/api/greader.php", "alice")
        compose.setContent {
            var selection by remember { mutableStateOf(Selection(scope = Scope.Category("News"))) }
            MaterialTheme {
                val done = LoadState.NotLoading(endOfPaginationReached = true)
                val items = remember { flowOf(PagingData.from(emptyList<EntryRow>(), LoadStates(done, done, done))) }.collectAsLazyPagingItems()
                HomeScreen(
                    accounts = listOf(account),
                    active = account,
                    tree = NavTree(categories = listOf(CategoryNode("News", 0, emptyList()))),
                    selection = selection,
                    query = null,
                    onQueryChange = {},
                    syncing = false,
                    items = items,
                    searchResults = items,
                    onSelectScope = { scopes += it },
                    onSelectFilter = { selection = selection.copy(filter = it) },
                    onSelectAccount = {},
                    onAddAccount = {},
                    onRemoveAccount = {},
                    onToggleRead = {},
                    onToggleStar = {},
                    onMarkAllRead = {},
                    onMarkBeside = { _, _ -> },
                    onScrolledPast = null,
                    onRefresh = {},
                    feedFullText = null,
                    onFeedFullText = {},
                    onOpenSettings = {},
                    onOpenManage = {},
                    images = null,
                    detail = { _, _, _ -> },
                )
            }
        }
        val openTree = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.open_tree)
        compose.onNodeWithContentDescription(openTree).performClick()
        compose.onNodeWithTag(SwitcherTags.filter(Filter.STARRED)).assertIsDisplayed()

        compose.onNodeWithTag(SwitcherTags.filter(Filter.STARRED)).performClick()

        compose.waitForIdle()
        compose.onNodeWithTag(SwitcherTags.filter(Filter.STARRED)).assertIsNotDisplayed()
        assertThat(scopes).isEmpty()
    }
}
