package com.gitpush.app.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gitpush.app.AppContainer
import com.gitpush.app.BuildConfig
import com.gitpush.app.LocalContainer
import com.gitpush.app.data.AuthException
import com.gitpush.app.data.DeviceCode
import com.gitpush.app.data.SettingKeys
import com.gitpush.app.ui.common.ErrorCard
import com.gitpush.app.ui.theme.CodeStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoginMode { OAUTH, TOKEN }

data class LoginUi(
    val mode: LoginMode = LoginMode.OAUTH,
    val clientId: String = BuildConfig.DEFAULT_CLIENT_ID,
    val token: String = "",
    val device: DeviceCode? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

class LoginViewModel(private val c: AppContainer) : ViewModel() {
    val ui = MutableStateFlow(LoginUi())
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val saved = c.settings.flow.first().clientId
            if (saved.isNotBlank()) ui.update { it.copy(clientId = saved) }
        }
    }

    fun setMode(m: LoginMode) = ui.update { it.copy(mode = m, error = null) }
    fun setClientId(v: String) = ui.update { it.copy(clientId = v) }
    fun setToken(v: String) = ui.update { it.copy(token = v) }

    fun startOAuth() {
        val id = ui.value.clientId.trim()
        job?.cancel()
        job = viewModelScope.launch {
            ui.update { it.copy(busy = true, error = null) }
            try {
                c.settings.set(SettingKeys.clientId, id)
                val code = c.auth.requestDeviceCode(id)
                ui.update { it.copy(device = code) }
                val token = c.auth.awaitDeviceToken(id, code)
                c.auth.signInWithToken(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                ui.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                ui.update { it.copy(error = e.message ?: "Sign-in failed.") }
            } finally {
                ui.update { it.copy(busy = false, device = null) }
            }
        }
    }

    fun cancelOAuth() {
        job?.cancel()
        ui.update { it.copy(busy = false, device = null) }
    }

    fun signInWithToken() {
        job?.cancel()
        job = viewModelScope.launch {
            ui.update { it.copy(busy = true, error = null) }
            try {
                c.auth.signInWithToken(ui.value.token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                ui.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                ui.update { it.copy(error = e.message ?: "Sign-in failed.") }
            } finally {
                ui.update { it.copy(busy = false) }
            }
        }
    }
}

@Composable
fun LoginScreen() {
    val c = LocalContainer.current
    val vm: LoginViewModel = viewModel(factory = viewModelFactory { initializer { LoginViewModel(c) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text("GitPush", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Send a folder or ZIP file from your phone to GitHub in one tap.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = ui.mode == LoginMode.OAUTH,
                onClick = { vm.setMode(LoginMode.OAUTH) },
                label = { Text("Sign in with GitHub") },
                enabled = !ui.busy,
            )
            FilterChip(
                selected = ui.mode == LoginMode.TOKEN,
                onClick = { vm.setMode(LoginMode.TOKEN) },
                label = { Text("Access token") },
                enabled = !ui.busy,
            )
        }

        ui.error?.let { ErrorCard(it) }

        when (ui.mode) {
            LoginMode.OAUTH -> {
                val device = ui.device
                if (device == null) {
                    Text(
                        "Full account access through GitHub’s device login. You need an OAuth app with Device Flow turned on " +
                            "(github.com/settings/developers). Paste its Client ID once.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = ui.clientId,
                        onValueChange = vm::setClientId,
                        label = { Text("OAuth Client ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = vm::startOAuth,
                        enabled = !ui.busy && ui.clientId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (ui.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Continue")
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Enter this code on GitHub", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(
                                device.userCode,
                                style = CodeStyle.copy(fontSize = 34.sp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                textAlign = TextAlign.Center,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(device.userCode)) }) { Text("Copy code") }
                                Button(onClick = { uriHandler.openUri(device.verificationUri) }) { Text("Open GitHub") }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Waiting for approval…", style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = vm::cancelOAuth) { Text("Cancel") }
                }
            }

            LoginMode.TOKEN -> {
                Text(
                    "Paste a personal access token. Classic tokens need the “repo” scope (and “workflow” to push " +
                        ".github/workflows files). Fine-grained tokens need Contents: read and write.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = ui.token,
                    onValueChange = vm::setToken,
                    label = { Text("Personal access token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { uriHandler.openUri("https://github.com/settings/tokens/new?scopes=repo,workflow&description=GitPush") }) {
                    Text("Create a token on GitHub")
                }
                Button(
                    onClick = vm::signInWithToken,
                    enabled = !ui.busy && ui.token.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (ui.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Sign in")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Your token is encrypted with a key that never leaves this device’s secure hardware store.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
