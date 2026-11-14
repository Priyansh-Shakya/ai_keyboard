package com.aikeyboard.data

import android.content.Context
import com.aikeyboard.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

data class AuthResult(val loggedIn: Boolean, val message: String = "")

/** The only network client in the app. Talks to our FastAPI backend - no third-party keys here. */
class ApiClient(context: Context) {

    private val ctx = context.applicationContext
    private val gson = Gson()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val base = BuildConfig.API_BASE_URL.trimEnd('/')
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    // ───────────────────────── auth ─────────────────────────

    suspend fun login(email: String, password: String) =
        authCall("auth/login", mapOf("email" to email, "password" to password))

    suspend fun signUp(email: String, password: String) =
        authCall("auth/signup", mapOf("email" to email, "password" to password))

    suspend fun googleLogin(idToken: String, nonce: String) =
        authCall("auth/google", mapOf("id_token" to idToken, "nonce" to nonce))

    fun logout() = PrefsManager.clearSession(ctx)

    private suspend fun authCall(path: String, body: Map<String, String>): Result<AuthResult> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("$base/$path")
                    .post(gson.toJson(body).toRequestBody(jsonType)).build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@use Result.failure<AuthResult>(ApiException(resp.code, errorMessage(resp.code, text)))
                    }
                    val obj = gson.fromJson(text, JsonObject::class.java)
                    if (obj.has("access_token")) {
                        PrefsManager.saveSession(ctx, parseSession(obj))
                        Result.success(AuthResult(true))
                    } else {
                        Result.success(AuthResult(false, obj.get("message")?.asString
                            ?: "Check your email to confirm your account."))
                    }
                }
            } catch (e: Exception) {
                Result.failure(friendly(e))
            }
        }

    private fun parseSession(o: JsonObject) = Session(
        accessToken = o.get("access_token").asString,
        refreshToken = o.get("refresh_token").asString,
        expiresAt = o.get("expires_at")?.asLong ?: (System.currentTimeMillis() / 1000 + 3600),
        email = o.get("email")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
    )

    @Synchronized
    private fun refreshSession(): String? {
        val s = PrefsManager.getSession(ctx) ?: return null
        val req = Request.Builder().url("$base/auth/refresh")
            .post(gson.toJson(mapOf("refresh_token" to s.refreshToken)).toRequestBody(jsonType)).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                val n = parseSession(gson.fromJson(text, JsonObject::class.java))
                PrefsManager.saveSession(ctx, n)
                return n.accessToken
            }
            if (resp.code == 400 || resp.code == 401) PrefsManager.clearSession(ctx)  // refresh token dead
            return null
        }
    }

    @Synchronized
    private fun validToken(): String? {
        val s = PrefsManager.getSession(ctx) ?: return null
        return if (s.expiresAt - 60 > System.currentTimeMillis() / 1000) s.accessToken else refreshSession()
    }

    /** Runs an authenticated request; on 401 refreshes the token once and retries. Blocking. */
    private fun execAuthed(build: (String) -> Request): Response {
        var token = validToken() ?: throw ApiException(401, "Not signed in. Open the AI Keyboard app and sign in.")
        var resp = http.newCall(build(token)).execute()
        if (resp.code == 401) {
            resp.close()
            token = refreshSession() ?: throw ApiException(401, "Session expired. Open the AI Keyboard app and sign in again.")
            resp = http.newCall(build(token)).execute()
        }
        return resp
    }

    private suspend fun callAuthed(build: (String) -> Request): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                execAuthed(build).use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) Result.success(text)
                    else Result.failure<String>(ApiException(resp.code, errorMessage(resp.code, text)))
                }
            } catch (e: Exception) {
                Result.failure(friendly(e))
            }
        }

    private fun req(token: String, path: String) =
        Request.Builder().url("$base/$path").header("Authorization", "Bearer $token")

    // ───────────────────────── notes ─────────────────────────

    suspend fun getNotes(): Result<List<Note>> =
        callAuthed { t -> req(t, "notes").get().build() }
            .mapCatching { gson.fromJson(it, Array<Note>::class.java).toList() }

    suspend fun createNote(title: String, content: String): Result<Note> =
        callAuthed { t ->
            req(t, "notes").post(gson.toJson(mapOf("title" to title, "content" to content)).toRequestBody(jsonType)).build()
        }.mapCatching { gson.fromJson(it, Note::class.java) }

    suspend fun updateNote(id: String, title: String, content: String): Result<Note> =
        callAuthed { t ->
            req(t, "notes/$id").patch(gson.toJson(mapOf("title" to title, "content" to content)).toRequestBody(jsonType)).build()
        }.mapCatching { gson.fromJson(it, Note::class.java) }

    suspend fun deleteNote(id: String): Result<Unit> =
        callAuthed { t -> req(t, "notes/$id").delete().build() }.map { }

    // ───────────────────────── user limits ─────────────────────────

    data class UserLimits(val userId: String, val requestLimits: Int, val premium: Boolean)

    suspend fun getUserLimits(): Result<UserLimits> =
        callAuthed { t -> req(t, "user/limits").get().build() }
            .mapCatching { json ->
                val obj = gson.fromJson(json, JsonObject::class.java)
                UserLimits(
                    userId = obj.get("user_id")?.asString.orEmpty(),
                    requestLimits = obj.get("request_limits")?.asInt ?: 10,
                    premium = obj.get("premium")?.asBoolean ?: false
                )
            }

    // ───────────────────────── chat (streaming) ─────────────────────────

    /** Streams the reply; [onDelta] is invoked on the main thread for every text chunk. */
    suspend fun chatStream(messages: List<ChatMessage>, onDelta: suspend (String) -> Unit, onRemaining: ((Int) -> Unit)? = null): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val body = gson.toJson(mapOf("messages" to messages.map {
                    mapOf("role" to it.role, "content" to it.content)
                }))
                execAuthed { t ->
                    req(t, "chat").header("Accept", "text/event-stream")
                        .post(body.toRequestBody(jsonType)).build()
                }.use { resp ->
                    if (!resp.isSuccessful) {
                        val text = resp.body?.string().orEmpty()
                        return@use Result.failure<String>(ApiException(resp.code, errorMessage(resp.code, text)))
                    }
                    val source = resp.body!!.source()
                    val full = StringBuilder()
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val obj = gson.fromJson(data, JsonObject::class.java) ?: continue
                        obj.get("error")?.let {
                            return@use Result.failure<String>(ApiException(502, it.asString))
                        }
                        obj.get("remaining_requests")?.asInt?.let { rem ->
                            withContext(Dispatchers.Main) { onRemaining?.invoke(rem) }
                        }
                        obj.get("delta")?.asString?.let { d ->
                            full.append(d)
                            withContext(Dispatchers.Main) { onDelta(d) }
                        }
                    }
                    Result.success(full.toString())
                }
            } catch (e: Exception) {
                Result.failure(friendly(e))
            }
        }

    // ───────────────────────── helpers ─────────────────────────

    private fun errorMessage(code: Int, body: String): String = try {
        val d = gson.fromJson(body, JsonObject::class.java)?.get("detail")
        when {
            d == null || d.isJsonNull -> "Request failed ($code)"
            d.isJsonPrimitive -> d.asString
            else -> "Invalid request ($code)"
        }
    } catch (e: Exception) {
        "Request failed ($code)"
    }

    private fun friendly(e: Exception): Exception = when (e) {
        is ApiException -> e
        is IOException -> Exception("Can't reach the server. Is the backend running?")
        else -> e
    }
}
