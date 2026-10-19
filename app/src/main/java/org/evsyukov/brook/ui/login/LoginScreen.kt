package org.evsyukov.brook.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.evsyukov.brook.R

object LoginTags {
    const val SERVER = "login-server"
    const val USER = "login-user"
    const val PASSWORD = "login-password"
    const val SUBMIT = "login-submit"
    const val ERROR = "login-error"
}

/** Sign-in to a server; [onBack] is set when an account is being added next to existing ones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    state: LoginState,
    onServerChange: (String) -> Unit,
    onUserChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (onBack == null) R.string.login_title else R.string.login_title_add)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.login_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = state.server,
                    onValueChange = onServerChange,
                    label = { Text(stringResource(R.string.login_server)) },
                    placeholder = { Text("rss.example.org") },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag(LoginTags.SERVER),
                )
                OutlinedTextField(
                    value = state.user,
                    onValueChange = onUserChange,
                    label = { Text(stringResource(R.string.login_user)) },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag(LoginTags.USER),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.login_password)) },
                    singleLine = true,
                    enabled = !state.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth().testTag(LoginTags.PASSWORD),
                )
                state.error?.let {
                    Text(
                        loginErrorText(it),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(LoginTags.ERROR),
                    )
                }
                Button(
                    onClick = onSubmit,
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth().testTag(LoginTags.SUBMIT),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.login_submit))
                    }
                }
            }
        }
    }
}

@Composable
private fun loginErrorText(error: LoginError): String = when (error) {
    LoginError.BadAddress -> stringResource(R.string.login_error_address)
    LoginError.WrongCredentials -> stringResource(R.string.login_error_credentials)
    is LoginError.RateLimited -> error.retryAfterSeconds?.let {
        pluralStringResource(R.plurals.login_error_rate_limited_seconds, it.toInt(), it.toInt())
    } ?: stringResource(R.string.login_error_rate_limited)
    LoginError.NoApi -> stringResource(R.string.login_error_no_api)
    LoginError.Network -> stringResource(R.string.login_error_network)
    is LoginError.Server -> stringResource(R.string.login_error_server, error.message)
}
