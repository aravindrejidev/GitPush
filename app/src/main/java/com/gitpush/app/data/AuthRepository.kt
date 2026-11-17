package com.gitpush.app.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val user: GhUser) : AuthState
}

@Serializable
data class DeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Int = 900,
    val interval: Int = 5,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

class AuthException(message: String) : Exception(message)

/**
 * Holds the GitHub credential. Two ways in, one token out:
 *  1. OAuth device flow ("Sign in with GitHub", no client secret needed on a phone)
 *  2. A personal access token pasted by the user
 */
class AuthRepository(
    private val secure: SecureStore,
    private val client: OkHttpClient,
    private val json: Json,
    private val api: GitHubApi,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun currentToken(): String? = secure.get(KEY_TOKEN)
    fun currentUser(): GhUser? = (state.value as? AuthState.SignedIn)?.user

    /** Called once at start-up. Keeps the cached profile if the network is unavailable. */
    suspend fun restore() {
        val token = currentToken()
        if (token == null) {
            _state.value = AuthState.SignedOut
            return
        }
        val cached = secure.get(KEY_USER)?.let { runCatching { json.decodeFromString(GhUser.serializer(), it) }.getOrNull() }
        if (cached != null) _state.value = AuthState.SignedIn(cached)
        try {
            val fresh = api.getUser(token)
            secure.put(KEY_USER, json.encodeToString(GhUser.serializer(), fresh))
            _state.value = AuthState.SignedIn(fresh)
        } catch (e: GitHubException) {
            if (e.status == 401) signOut() else if (cached == null) _state.value = AuthState.SignedOut
        } catch (e: IOException) {
            if (cached == null) _state.value = AuthState.SignedOut
        }
    }

    /** Validates the token against GitHub, stores it, and flips the app into the signed-in state. */
    suspend fun signInWithToken(token: String) {
        val clean = token.trim()
        if (clean.isEmpty()) throw AuthException("Enter a token first.")
        val user = try {
            api.getUser(clean)
        } catch (e: GitHubException) {
            throw AuthException(if (e.status == 401) "GitHub does not accept this token." else (e.message ?: "Sign-in failed."))
        } catch (e: IOException) {
            throw AuthException("No connection to GitHub.")
        }
        secure.put(KEY_TOKEN, clean)
        secure.put(KEY_USER, json.encodeToString(GhUser.serializer(), user))
        _state.value = AuthState.SignedIn(user)
    }

    fun signOut() {
        secure.put(KEY_TOKEN, null)
        secure.put(KEY_USER, null)
        _state.value = AuthState.SignedOut
    }

    // ---- OAuth device flow ----------------------------------------------------------------

    suspend fun requestDeviceCode(clientId: String): DeviceCode {
        if (clientId.isBlank()) throw AuthException("Enter the OAuth app Client ID first.")
        val body = FormBody.Builder().add("client_id", clientId.trim()).add("scope", "repo workflow").build()
        val req = Request.Builder()
            .url("https://github.com/login/device/code")
            .header("Accept", "application/json")
            .post(body)
            .build()
        val text = exec(req)
        return runCatching { json.decodeFromString(DeviceCode.serializer(), text) }
            .getOrElse { throw AuthException("GitHub did not return a device code. Check that Device Flow is enabled for the OAuth app.") }
    }

    /** Polls until the user approves, denies, or the code expires. Returns the access token. */
    suspend fun awaitDeviceToken(clientId: String, code: DeviceCode): String {
        var interval = code.interval.coerceAtLeast(1)
        val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
        while (currentCoroutineContext().isActive && System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val body = FormBody.Builder()
                .add("client_id", clientId.trim())
                .add("device_code", code.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()
            val req = Request.Builder()
                .url("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json")
                .post(body)
                .build()
            val resp = json.decodeFromString(TokenResponse.serializer(), exec(req))
            when {
                resp.accessToken != null -> return resp.accessToken
                resp.error == "authorization_pending" -> Unit
                resp.error == "slow_down" -> interval += 5
                resp.error == "expired_token" -> throw AuthException("The code expired. Start again.")
                resp.error == "access_denied" -> throw AuthException("Authorization was denied.")
                else -> throw AuthException(resp.errorDescription ?: resp.error ?: "Sign-in failed.")
            }
        }
        throw AuthException("The code expired. Start again.")
    }

    private suspend fun exec(req: Request): String = try {
        client.newCall(req).await().use { it.body?.string().orEmpty() }
    } catch (e: IOException) {
        throw AuthException("No connection to GitHub.")
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    private companion object {
        const val KEY_TOKEN = "github_token"
        const val KEY_USER = "github_user"
    }
}
