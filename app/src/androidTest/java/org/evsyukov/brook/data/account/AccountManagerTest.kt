package org.evsyukov.brook.data.account

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.CategoryEntity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** R10 on a device: the Keystore seals tokens, accounts keep their data apart, removal leaves nothing. */
@RunWith(AndroidJUnit4::class)
class AccountManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = shared(context)

    @Before
    fun setUp() = runBlocking<Unit> {
        for (account in manager.accounts.first()) manager.remove(account.id)
        cancelled.clear()
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        for (account in manager.accounts.first()) manager.remove(account.id)
    }

    @Test
    fun accountsKeepTheirDataApart() = runBlocking<Unit> {
        val a = manager.add("http://one.test".toHttpUrl(), "alice", "pw-a")
        val b = manager.add("http://two.test".toHttpUrl(), "bob", "pw-b")
        assertThat(manager.accounts.first().map { it.id }).containsExactly(a.id, b.id).inOrder()
        assertThat(manager.active.first()?.id).isEqualTo(b.id)

        manager.session(a.id).db.treeDao().replaceTree(listOf(CategoryEntity("only-a")), emptyList(), emptyList())
        assertThat(manager.session(b.id).db.treeDao().categories().first()).isEmpty()

        manager.setActive(a.id)
        assertThat(manager.active.first()?.id).isEqualTo(a.id)
        assertThat(manager.session(a.id).db.treeDao().categories().first().map { it.name }).containsExactly("only-a")
        assertThat(manager.session(a.id).api.auth).isEqualTo("alice/token-of-pw-a")
        assertThat(manager.session(a.id).api.apiBase.toString()).isEqualTo("http://one.test/api/greader.php")
    }

    @Test
    fun theTokenIsNotStoredInTheClear() = runBlocking<Unit> {
        val a = manager.add("http://one.test".toHttpUrl(), "alice", "pw-a")
        val file = context.preferencesDataStoreFile(AccountManager.storeFile(a.id))
        assertThat(file.exists()).isTrue()
        assertThat(String(file.readBytes(), Charsets.ISO_8859_1)).doesNotContain("token-of-pw-a")
    }

    @Test
    fun signingInAgainKeepsTheAccountAndItsData() = runBlocking<Unit> {
        val a = manager.add("http://one.test".toHttpUrl(), "alice", "old")
        manager.session(a.id).db.treeDao().replaceTree(listOf(CategoryEntity("kept")), emptyList(), emptyList())
        manager.add("http://two.test".toHttpUrl(), "bob", "pw-b")

        val again = manager.add("http://one.test".toHttpUrl(), "alice", "new")

        assertThat(again.id).isEqualTo(a.id)
        assertThat(manager.accounts.first()).hasSize(2)
        assertThat(manager.active.first()?.id).isEqualTo(a.id)
        assertThat(manager.session(a.id).api.auth).isEqualTo("alice/token-of-new")
        assertThat(manager.session(a.id).db.treeDao().categories().first().map { it.name }).containsExactly("kept")
    }

    @Test
    fun removalDeletesEverythingOfTheAccountOnly() = runBlocking<Unit> {
        val a = manager.add("http://one.test".toHttpUrl(), "alice", "pw-a")
        val b = manager.add("http://two.test".toHttpUrl(), "bob", "pw-b")
        manager.setActive(a.id)
        manager.session(a.id).db.treeDao().replaceTree(listOf(CategoryEntity("x")), emptyList(), emptyList())
        manager.session(b.id).db.treeDao().replaceTree(listOf(CategoryEntity("y")), emptyList(), emptyList())
        manager.cacheDir(a.id).apply { mkdirs() }.resolve("picture").writeText("p")
        manager.filesDir(a.id).apply { mkdirs() }.resolve("text").writeText("t")

        manager.remove(a.id)

        assertThat(context.getDatabasePath(BrookDatabase.fileName(a.id)).exists()).isFalse()
        assertThat(context.preferencesDataStoreFile(AccountManager.storeFile(a.id)).exists()).isFalse()
        assertThat(manager.cacheDir(a.id).exists()).isFalse()
        assertThat(manager.filesDir(a.id).exists()).isFalse()
        assertThat(cancelled).containsExactly(AccountManager.workTag(a.id))
        assertThat(manager.accounts.first().map { it.id }).containsExactly(b.id)
        assertThat(manager.active.first()?.id).isEqualTo(b.id)
        assertThat(manager.session(b.id).db.treeDao().categories().first().map { it.name }).containsExactly("y")

        manager.remove(b.id)
        assertThat(manager.accounts.first()).isEmpty()
        assertThat(manager.active.first()).isNull()
    }

    companion object {
        private val cancelled = mutableListOf<String>()
        private var instance: AccountManager? = null

        // One DataStore per file and process: every test shares the manager, and with it its list.
        // The app's own manager holds the list of the app, so this one keeps a list of its own.
        @Synchronized
        fun shared(context: Context): AccountManager = instance ?: AccountManager(
            context = context,
            http = OkHttpClient(),
            cipher = KeystoreTokenCipher(alias = "brook-test-token"),
            cancelWork = { cancelled += it },
            registryFile = "accounts-test",
            signIn = { server: HttpUrl, user: String, password: String ->
                Credentials(server.newBuilder().addPathSegments("api/greader.php").build(), "$user/token-of-$password")
            },
        ).also { instance = it }
    }
}
