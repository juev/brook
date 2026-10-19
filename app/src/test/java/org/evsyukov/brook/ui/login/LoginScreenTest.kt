package org.evsyukov.brook.ui.login

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.data.remote.GReaderException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val signedIn = mutableListOf<Account>()

    private fun show(signIn: suspend (String, String, String) -> Account) {
        val vm = LoginViewModel(
            signIn = { server, user, password -> signIn(server.toString(), user, password) },
            onSignedIn = { signedIn += it },
        )
        compose.setContent {
            MaterialTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                LoginScreen(state, vm::setServer, vm::setUser, vm::setPassword, vm::submit)
            }
        }
    }

    private fun fill() {
        compose.onNodeWithTag(LoginTags.SERVER).performTextInput("http://10.0.2.2:8080")
        compose.onNodeWithTag(LoginTags.USER).performTextInput("alice")
        compose.onNodeWithTag(LoginTags.PASSWORD).performTextInput("secret")
    }

    @Test
    fun signInSendsWhatWasTyped() {
        var sent: Triple<String, String, String>? = null
        show { server, user, password ->
            sent = Triple(server, user, password)
            Account("a1", server, user)
        }
        compose.onNodeWithTag(LoginTags.SUBMIT).assertIsNotEnabled()
        fill()
        compose.onNodeWithTag(LoginTags.SUBMIT).assertIsEnabled().performClick()
        compose.waitForIdle()

        assertThat(sent).isEqualTo(Triple("http://10.0.2.2:8080/", "alice", "secret"))
        assertThat(signedIn.map { it.id }).containsExactly("a1")
    }

    @Test
    fun wrongPasswordIsShown() {
        show { _, _, _ -> throw GReaderException.Unauthorized() }
        fill()
        compose.onNodeWithTag(LoginTags.SUBMIT).performClick()

        compose.onNodeWithTag(LoginTags.ERROR).assertTextEquals("Wrong user name or API password")
        assertThat(signedIn).isEmpty()
    }

    @Test
    fun rateLimitShowsTheWait() {
        show { _, _, _ -> throw GReaderException.RateLimited(30) }
        fill()
        compose.onNodeWithTag(LoginTags.SUBMIT).performClick()

        compose.onNodeWithTag(LoginTags.ERROR).assertTextEquals("Too many attempts, try again in 30 seconds")
    }

    @Test
    fun fieldsAreLockedWhileSigningIn() {
        val answer = CompletableDeferred<Account>()
        show { _, _, _ -> answer.await() }
        fill()
        compose.onNodeWithTag(LoginTags.SUBMIT).performClick()

        compose.onNodeWithTag(LoginTags.SUBMIT).assertIsNotEnabled()
        compose.onNodeWithTag(LoginTags.PASSWORD).assertIsNotEnabled()
        answer.complete(Account("a1", "http://10.0.2.2:8080/", "alice"))
        compose.waitForIdle()
        assertThat(signedIn).hasSize(1)
    }
}
