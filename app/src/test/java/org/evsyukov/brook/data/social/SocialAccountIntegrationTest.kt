package org.evsyukov.brook.data.social

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.evsyukov.brook.data.account.AccountManager
import org.evsyukov.brook.data.account.Credentials
import org.evsyukov.brook.data.account.TokenCipher
import org.evsyukov.brook.data.db.EntryEntity
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SocialAccountIntegrationTest {
    @Test
    fun accountSessionsUseTheConfiguredSocialDestinationsAndWakeDeliveryAfterCommit() = runBlocking<Unit> {
        val context: Context = ApplicationProvider.getApplicationContext()
        var wakes = 0
        val manager = AccountManager(context, OkHttpClient(), object : TokenCipher {
            override fun encrypt(plain: String) = plain.reversed()
            override fun decrypt(sealed: String) = sealed.reversed()
        }, {}, registryFile = "social-integration-${UUID.randomUUID()}",
            socialDestinations = { listOf("mastodon#connection") }, onSocialQueued = { wakes++ },
            signIn = { server, _, _ -> Credentials(server, "reader-token") })
        val account = manager.add("https://reader.example/".toHttpUrl(), "alice", "password")
        try {
            val session = manager.session(account.id)
            session.db.entryDao().insertIgnore(listOf(EntryEntity(1, "feed/1", "Title", "", "https://example.org/story", 1, "", "", false, false, null)))
            session.actions.setStarred(1, true)
            assertThat(session.db.deliveryDao().all().map { it.destination }).containsExactly("mastodon#connection")
            assertThat(wakes).isEqualTo(1)
            session.actions.setStarred(1, true)
            assertThat(wakes).isEqualTo(1)
        } finally {
            manager.remove(account.id)
        }
    }
}