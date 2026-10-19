package org.evsyukov.brook.ui.login

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.data.remote.GReaderException
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val calls = mutableListOf<Triple<HttpUrl, String, String>>()
    private val signedIn = mutableListOf<Account>()
    private var failure: Exception? = null

    private val vm by lazy {
        LoginViewModel(
            signIn = { server, user, password ->
                calls += Triple(server, user, password)
                failure?.let { throw it }
                Account("a1", server.toString(), user)
            },
            onSignedIn = { signedIn += it },
        )
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun fill(server: String = "rss.example.org", user: String = " alice ", password: String = "pw") {
        vm.setServer(server)
        vm.setUser(user)
        vm.setPassword(password)
    }

    @Test
    fun `an address without a scheme gets https and the user name is trimmed`() {
        fill()
        vm.submit()
        assertThat(calls.single().first.toString()).isEqualTo("https://rss.example.org/")
        assertThat(calls.single().second).isEqualTo("alice")
        assertThat(signedIn).hasSize(1)
        assertThat(vm.state.value).isEqualTo(LoginState())
    }

    @Test
    fun `nothing is sent until every field is filled`() {
        fill(password = "")
        assertThat(vm.state.value.canSubmit).isFalse()
        vm.submit()
        assertThat(calls).isEmpty()
    }

    @Test
    fun `an address that does not parse is reported without a request`() {
        fill(server = "http://exa mple")
        vm.submit()
        assertThat(calls).isEmpty()
        assertThat(vm.state.value.error).isEqualTo(LoginError.BadAddress)
    }

    @Test
    fun `failures keep the fields and tell why`() {
        fill()
        failure = GReaderException.Unauthorized()
        vm.submit()
        assertThat(vm.state.value.error).isEqualTo(LoginError.WrongCredentials)
        assertThat(vm.state.value.busy).isFalse()
        assertThat(vm.state.value.password).isEqualTo("pw")
        assertThat(signedIn).isEmpty()

        vm.setPassword("pw2")
        assertThat(vm.state.value.error).isNull()
    }

    @Test
    fun `errors map to what the screen says`() {
        assertThat(loginError(GReaderException.Unauthorized())).isEqualTo(LoginError.WrongCredentials)
        assertThat(loginError(GReaderException.RateLimited(30))).isEqualTo(LoginError.RateLimited(30))
        assertThat(loginError(GReaderException.NotFound())).isEqualTo(LoginError.NoApi)
        assertThat(loginError(GReaderException.Http(500, "boom"))).isInstanceOf(LoginError.Server::class.java)
        assertThat(loginError(IOException("reset"))).isEqualTo(LoginError.Network)
    }
}
