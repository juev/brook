package org.evsyukov.brook.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.Scope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavDrawerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun drawerSwitchesTheFilterWithoutSelectingAScope() {
        val scopes = mutableListOf<Scope>()
        val filters = mutableListOf<Filter>()
        compose.setContent {
            var filter by remember { mutableStateOf(Filter.ALL) }
            MaterialTheme {
                NavDrawerContent(
                    tree = NavTree(),
                    selected = Scope.All,
                    onSelect = { scopes += it },
                    filter = filter,
                    onSelectFilter = {
                        filters += it
                        filter = it
                    },
                    header = {},
                    onOpenSettings = {},
                    onOpenManage = {},
                )
            }
        }
        compose.onNodeWithTag(SwitcherTags.filter(Filter.ALL)).assertIsSelected()
        compose.onNodeWithTag(SwitcherTags.filter(Filter.UNREAD)).performClick()

        compose.onNodeWithTag(SwitcherTags.filter(Filter.UNREAD)).assertIsSelected()
        assertThat(filters).containsExactly(Filter.UNREAD)
        assertThat(scopes).isEmpty()
    }
}
