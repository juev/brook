package org.evsyukov.brook.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.brook.data.account.Account
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwitchersTest {
    @get:Rule
    val compose = createComposeRule()

    private val alice = Account("a", "https://one.example/api/greader.php", "alice")
    private val bob = Account("b", "http://10.0.2.2:8080/api/greader.php", "bob")

    @Test
    fun accountSwitcherSelectsAnotherAccount() {
        val selected = mutableListOf<Account>()
        compose.setContent {
            MaterialTheme {
                AccountSwitcher(listOf(alice, bob), active = alice, onSelect = { selected += it }, onAdd = {}, onRemove = {})
            }
        }
        compose.onNodeWithText("one.example").assertExists()
        compose.onNodeWithTag(SwitcherTags.ACCOUNT).performClick()
        compose.onNodeWithText("bob · 10.0.2.2").assertExists()
        compose.onNodeWithTag(SwitcherTags.account("b")).performClick()

        assertThat(selected).containsExactly(bob)
    }

    @Test
    fun accountSwitcherIgnoresTheActiveOneAndOffersAddAndRemove() {
        val selected = mutableListOf<Account>()
        var added = 0
        val removed = mutableListOf<Account>()
        compose.setContent {
            MaterialTheme {
                AccountSwitcher(listOf(alice, bob), active = alice, onSelect = { selected += it }, onAdd = { added++ }, onRemove = { removed += it })
            }
        }
        compose.onNodeWithTag(SwitcherTags.ACCOUNT).performClick()
        compose.onNodeWithTag(SwitcherTags.account("a")).performClick()
        compose.onNodeWithTag(SwitcherTags.ACCOUNT).performClick()
        compose.onNodeWithTag(SwitcherTags.ADD_ACCOUNT).performClick()
        compose.onNodeWithTag(SwitcherTags.ACCOUNT).performClick()
        compose.onNodeWithTag(SwitcherTags.REMOVE_ACCOUNT).performClick()

        assertThat(selected).isEmpty()
        assertThat(added).isEqualTo(1)
        assertThat(removed).containsExactly(alice)
    }
}
