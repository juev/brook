package org.evsyukov.brook.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.nextOnShortPress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModeControlTest {
    @get:Rule
    val compose = createComposeRule()

    private val picked = mutableListOf<Filter>()

    /** The accessibility action labels a screen reader announces, checked without a content description. */
    private fun clickLabel(label: String) = SemanticsMatcher("onClick label is $label") { node ->
        node.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    private fun longClickLabel(label: String) = SemanticsMatcher("onLongClick label is $label") { node ->
        node.config.getOrNull(SemanticsActions.OnLongClick)?.label == label
    }

    private fun show(start: Filter = Filter.UNREAD) {
        compose.setContent {
            var filter by remember { mutableStateOf(start) }
            var menu by remember { mutableStateOf(false) }
            MaterialTheme {
                ModeButton(
                    filter = filter,
                    menuOpen = menu,
                    onShortPress = {
                        val next = filter.nextOnShortPress()
                        picked += next
                        filter = next
                    },
                    onMenuOpen = { menu = true },
                    onMenuDismiss = { menu = false },
                    onSelect = {
                        picked += it
                        filter = it
                        menu = false
                    },
                )
            }
        }
    }

    @Test
    fun `every mode has its own glyph and the short press follows the shortcuts`() {
        assertThat(Filter.entries.map { it.icon() }.toSet()).hasSize(3)
        assertThat(Filter.ALL.nextOnShortPress()).isEqualTo(Filter.UNREAD)
        assertThat(Filter.UNREAD.nextOnShortPress()).isEqualTo(Filter.ALL)
        assertThat(Filter.STARRED.nextOnShortPress()).isEqualTo(Filter.UNREAD)
    }

    @Test
    fun `a short press never opens the menu`() {
        show(Filter.UNREAD)

        compose.onNodeWithTag(ModeTags.BUTTON).performClick()
        compose.onNodeWithTag(ModeTags.BUTTON).performClick()

        assertThat(picked).containsExactly(Filter.ALL, Filter.UNREAD).inOrder()
        compose.onNodeWithTag(ModeTags.MENU).assertDoesNotExist()
    }

    @Test
    fun `a long press opens the menu with the active mode marked and changes nothing`() {
        show(Filter.UNREAD)

        compose.onNodeWithTag(ModeTags.BUTTON).performTouchInput { longClick() }

        compose.onNodeWithTag(ModeTags.MENU).assertExists()
        compose.onNodeWithTag(ModeTags.option(Filter.ALL)).assertExists()
        compose.onNodeWithTag(ModeTags.option(Filter.UNREAD)).assertIsSelected()
        compose.onNodeWithTag(ModeTags.option(Filter.STARRED)).assertExists()
        assertThat(picked).isEmpty()
    }

    @Test
    fun `an option in the menu replaces the mode and closes it`() {
        show(Filter.UNREAD)
        compose.onNodeWithTag(ModeTags.BUTTON).performTouchInput { longClick() }

        compose.onNodeWithTag(ModeTags.option(Filter.STARRED)).performClick()

        compose.onNodeWithTag(ModeTags.MENU).assertDoesNotExist()
        assertThat(picked).containsExactly(Filter.STARRED)
    }

    @Test
    fun `the button is a 48 dp target whose name and state carry the mode`() {
        show(Filter.UNREAD)

        compose.onNodeWithTag(ModeTags.BUTTON)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unread"))
            .assert(clickLabel("Switch to All"))
            .assert(longClickLabel("Choose view mode"))
        compose.onNodeWithContentDescription("View mode: Unread").assertExists()
    }
}
