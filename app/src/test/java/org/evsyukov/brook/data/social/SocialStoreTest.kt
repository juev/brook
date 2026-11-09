package org.evsyukov.brook.data.social

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.brook.data.account.TokenCipher
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SocialStoreTest {
    private val cipher = object : TokenCipher {
        override fun encrypt(plain: String) = Base64.getEncoder().encodeToString(plain.toByteArray())
        override fun decrypt(sealed: String) = String(Base64.getDecoder().decode(sealed))
    }

    @Test
    fun connectionsAndSelectionSurviveReopeningWithoutPlaintextSecrets() = runBlocking<Unit> {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "social-${UUID.randomUUID()}"
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = SocialStore(context, scope, cipher, name)
            store.save(SocialConnection("one", SocialService.MASTODON, "https://social.example/", "42", "alice", "private-access-token", enabled = true))
            assertThat(store.connections.first().map { it.id }).containsExactly("one")
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val reopened = SocialStore(context, scope, cipher, name)
            val connection = reopened.connections.first().single()
            assertThat(connection.enabled).isTrue()
            assertThat(connection.accessToken).isEqualTo("private-access-token")
            assertThat(File(context.noBackupFilesDir, "$name.preferences_pb").readText()).doesNotContain("private-access-token")
            reopened.remove(SocialService.MASTODON)
            assertThat(reopened.connections.first()).isEmpty()
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}