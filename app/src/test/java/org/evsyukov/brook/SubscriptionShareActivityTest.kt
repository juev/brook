package org.evsyukov.brook

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.evsyukov.brook.data.account.KeystoreTokenCipher
import org.evsyukov.brook.ui.login.LoginTags
import org.evsyukov.brook.ui.manage.ManageTags
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
@Config(
    application = SubscriptionShareActivityTest.TestApplication::class,
    instrumentedPackages = ["org.evsyukov.brook.data.account"],
    shadows = [SubscriptionShareActivityTest.TestCipher::class],
)
class SubscriptionShareActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<BrookApplication>()
    private val quickAdds = LinkedBlockingQueue<RecordedRequest>()

    @After
    fun removeTestAccounts() = runBlocking<Unit> {
        app.container.scope.coroutineContext[Job]!!.cancelAndJoin()
        for (account in app.container.accounts.accounts.first()) app.container.accounts.remove(account.id)
    }

    @Test
    fun aColdBrowserShareWaitsForFirstSignInThenOpensThePrefilledDialog() {
        val link = "https://example.com/blog"
        server().use { server ->
            ActivityScenario.launch<MainActivity>(share("Example Blog\n$link")).use {
                compose.waitUntilExactlyOneExists(hasTestTag(LoginTags.SUBMIT), 10_000)
                compose.onNodeWithTag(ManageTags.FIELD).assertDoesNotExist()

                val account = runBlocking { withTimeout(10_000) {
                    app.container.accounts.add(server.url("/").newBuilder().host("localhost").build(), "alice", "fixture")
                } }
                assertThat(account.user).isEqualTo("alice")

                compose.waitUntilExactlyOneExists(hasTestTag(ManageTags.FIELD), 10_000)
                compose.onNodeWithTag(ManageTags.FIELD).assertTextContains(link)
                assertThat(quickAdds).isEmpty()
            }
        }
    }

    @Test
    fun aLaterShareReplacesTheDraftAndTheEditedAddressIsSentOnceToTheActiveAccount() {
        val initial = "https://example.com/old"
        val next = "https://example.com/site"
        val edited = "https://example.com/rss?q=a%2Fb"
        server().use { server ->
            runBlocking { withTimeout(10_000) {
                val base = server.url("/").newBuilder().host("localhost").build()
                app.container.accounts.add(base.resolve("/other")!!, "bob", "fixture")
                app.container.accounts.add(base, "alice", "fixture")
            } }
            ActivityScenario.launch<MainActivity>(share(initial)).use { scenario ->
                compose.waitUntilExactlyOneExists(hasTestTag(ManageTags.FIELD), 10_000)
                compose.onNodeWithTag(ManageTags.FIELD).assertTextContains(initial)

                scenario.onActivity { activity ->
                    InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, share(next))
                }
                compose.onNodeWithTag(ManageTags.FIELD).assertTextContains(next)
                compose.onNodeWithTag(ManageTags.FIELD).performTextReplacement(edited)
                scenario.recreate()
                compose.waitUntilExactlyOneExists(hasTestTag(ManageTags.FIELD), 10_000)
                compose.onNodeWithTag(ManageTags.FIELD).assertTextContains(edited)
                compose.onNodeWithText("alice · localhost", substring = true).assertIsDisplayed()
                assertThat(quickAdds).isEmpty()

                compose.onNodeWithTag(ManageTags.CONFIRM).performClick()

                val request = checkNotNull(quickAdds.poll(10, SECONDS)) { "No quickadd was sent" }
                assertThat(request.method).isEqualTo("POST")
                assertThat(request.url.encodedPath).isEqualTo("/api/greader.php/reader/api/0/subscription/quickadd")
                assertThat(request.headers["Authorization"]).isEqualTo("GoogleLogin auth=alice/fixture-token")
                assertThat(request.body?.utf8()).isEqualTo("quickadd=https%3A%2F%2Fexample.com%2Frss%3Fq%3Da%252Fb")
                compose.waitUntilExactlyOneExists(hasText("Example Feed"), 10_000)

                scenario.recreate()
                compose.waitUntilExactlyOneExists(hasText("Example Feed"), 10_000)
                compose.onNodeWithTag(ManageTags.FIELD).assertDoesNotExist()
                assertThat(quickAdds).isEmpty()
            }
        }
    }

    @Test
    fun cancellingAShareDoesNotSendOrRestoreIt() {
        server().use { server ->
            runBlocking { withTimeout(10_000) {
                app.container.accounts.add(server.url("/").newBuilder().host("localhost").build(), "alice", "fixture")
            } }
            ActivityScenario.launch<MainActivity>(share("https://example.com/feed")).use { scenario ->
                compose.waitUntilExactlyOneExists(hasTestTag(ManageTags.FIELD), 10_000)
                compose.onNodeWithText(app.getString(R.string.cancel)).performClick()
                compose.onNodeWithTag(ManageTags.FIELD).assertDoesNotExist()

                scenario.recreate()

                compose.waitUntilExactlyOneExists(hasTestTag(ManageTags.ADD), 10_000)
                compose.onNodeWithTag(ManageTags.FIELD).assertDoesNotExist()
                assertThat(quickAdds).isEmpty()
            }
        }
    }

    private fun share(text: String) = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(app.packageName)
        .putExtra(Intent.EXTRA_TEXT, text)

    private fun server() = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            @Volatile private var added = false

            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/accounts/ClientLogin") -> {
                        val user = if (path.startsWith("/other/")) "bob" else "alice"
                        MockResponse(body = "Auth=$user/fixture-token")
                    }
                    path.endsWith("/subscription/quickadd") -> {
                        added = true
                        quickAdds.add(request)
                        MockResponse(body = """{"numResults":1,"streamId":"feed/1"}""")
                    }
                    path.endsWith("/tag/list") -> MockResponse(body = """{"tags":[]}""")
                    path.endsWith("/subscription/list") -> MockResponse(body = if (added) {
                        """{"subscriptions":[{"id":"feed/1","title":"Example Feed","url":"https://example.com/rss"}]}"""
                    } else {
                        """{"subscriptions":[]}"""
                    })
                    path.endsWith("/stream/items/ids") -> MockResponse(body = """{"itemRefs":[]}""")
                    else -> MockResponse(code = 404)
                }
            }
        }
        start()
    }

    class TestApplication : BrookApplication() {
        override fun onCreate() {
            WorkManagerTestInitHelper.initializeTestWorkManager(this)
            super.onCreate()
        }
    }

    // Robolectric does not provide Android Keystore. The account store, HTTP and UI are real.
    @Implements(KeystoreTokenCipher::class)
    class TestCipher {
        @Implementation protected fun encrypt(plain: String): String = plain.reversed()
        @Implementation protected fun decrypt(sealed: String): String = sealed.reversed()
    }
}
