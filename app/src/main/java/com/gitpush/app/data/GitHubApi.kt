package com.gitpush.app.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GitHubException(val status: Int, message: String) : IOException(message)

@Serializable
data class GhUser(
    val login: String,
    val id: Long,
    val name: String? = null,
    val email: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class GhOwner(val login: String)

@Serializable
data class GhRepo(
    val id: Long,
    val name: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("private") val isPrivate: Boolean = false,
    val description: String? = null,
    @SerialName("default_branch") val defaultBranch: String = "main",
    val owner: GhOwner,
    @SerialName("pushed_at") val pushedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val fork: Boolean = false,
    val archived: Boolean = false,
)

@Serializable
data class GhBranch(val name: String)

@Serializable
data class GhCommitAuthor(val name: String? = null, val date: String? = null)

@Serializable
data class GhCommitInfo(val message: String = "", val author: GhCommitAuthor? = null)

@Serializable
data class GhCommit(
    val sha: String,
    @SerialName("html_url") val htmlUrl: String = "",
    val commit: GhCommitInfo,
)

class GitHubApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val tokenProvider: () -> String?,
) {
    private val base = "https://api.github.com"

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

    private fun request(path: String, token: String?): Request.Builder =
        Request.Builder()
            .url(if (path.startsWith("http")) path else base + path)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (token != null) header("Authorization", "Bearer $token") }

    private fun errorMessage(body: String, code: Int): String {
        val msg = runCatching { json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        return when (code) {
            401 -> "GitHub rejected the token. Sign in again."
            403 -> msg ?: "Access denied by GitHub."
            404 -> "Not found, or the token cannot see it."
            else -> msg ?: "GitHub returned HTTP $code."
        }
    }

    private suspend fun send(req: Request): String =
        client.newCall(req).await().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw GitHubException(r.code, errorMessage(body, r.code))
            body
        }

    private suspend fun get(path: String, token: String? = tokenProvider()): String =
        send(request(path, token).get().build())

    suspend fun getUser(token: String? = null): GhUser =
        json.decodeFromString(GhUser.serializer(), get("/user", token ?: tokenProvider()))

    suspend fun getRepo(owner: String, repo: String): GhRepo =
        json.decodeFromString(GhRepo.serializer(), get("/repos/$owner/$repo"))

    suspend fun listRepos(): List<GhRepo> {
        val all = ArrayList<GhRepo>()
        for (page in 1..10) {
            val body = get("/user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator,organization_member&page=$page")
            val items = json.decodeFromString(ListSerializer(GhRepo.serializer()), body)
            all += items
            if (items.size < 100) break
        }
        return all
    }

    suspend fun listBranches(owner: String, repo: String): List<String> {
        val all = ArrayList<String>()
        for (page in 1..5) {
            val body = get("/repos/$owner/$repo/branches?per_page=100&page=$page")
            val items = json.decodeFromString(ListSerializer(GhBranch.serializer()), body)
            all += items.map { it.name }
            if (items.size < 100) break
        }
        return all
    }

    suspend fun listCommits(owner: String, repo: String, branch: String): List<GhCommit> {
        val body = get("/repos/$owner/$repo/commits?sha=${java.net.URLEncoder.encode(branch, "UTF-8")}&per_page=30")
        return json.decodeFromString(ListSerializer(GhCommit.serializer()), body)
    }

    suspend fun createRepo(name: String, description: String, isPrivate: Boolean): GhRepo {
        val payload: JsonObject = buildJsonObject {
            put("name", name)
            put("description", description)
            put("private", isPrivate)
            put("auto_init", false)
        }
        val req = request("/user/repos", tokenProvider())
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return json.decodeFromString(GhRepo.serializer(), send(req))
    }
}
