package org.evsyukov.brook.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import org.evsyukov.brook.data.account.Account
import org.evsyukov.brook.data.remote.GReaderClient
import org.evsyukov.brook.data.remote.GReaderException
import java.io.IOException

/** Why sign-in failed, as the screen tells it. */
sealed interface LoginError {
    data object BadAddress : LoginError
    data object WrongCredentials : LoginError
    data class RateLimited(val retryAfterSeconds: Long?) : LoginError
    data object NoApi : LoginError
    data object Network : LoginError
    data class Server(val message: String) : LoginError
}

fun loginError(e: Throwable): LoginError = when (e) {
    is GReaderException.Unauthorized -> LoginError.WrongCredentials
    is GReaderException.RateLimited -> LoginError.RateLimited(e.retryAfterSeconds)
    is GReaderException.NotFound -> LoginError.NoApi
    is GReaderException -> LoginError.Server(e.message.orEmpty())
    is IOException -> LoginError.Network
    else -> LoginError.Server(e.message.orEmpty())
}

data class LoginState(
    val server: String = "",
    val user: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: LoginError? = null,
) {
    val canSubmit: Boolean get() = !busy && server.isNotBlank() && user.isNotBlank() && password.isNotEmpty()
}

class LoginViewModel(
    private val signIn: suspend (server: HttpUrl, user: String, password: String) -> Account,
    private val onSignedIn: (Account) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun setServer(value: String) = _state.update { it.copy(server = value, error = null) }
    fun setUser(value: String) = _state.update { it.copy(user = value, error = null) }
    fun setPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        val server = GReaderClient.parseServer(current.server)
        if (server == null) {
            _state.update { it.copy(error = LoginError.BadAddress) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val account = signIn(server, current.user.trim(), current.password)
                // The view model outlives the screen; the next sign-in starts empty.
                _state.value = LoginState()
                onSignedIn(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = loginError(e)) }
            }
        }
    }
}
