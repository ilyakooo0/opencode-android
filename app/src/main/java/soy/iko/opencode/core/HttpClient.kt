package soy.iko.opencode.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A completed HTTP response (any status code — non-2xx is not an error here). */
data class HttpResp(val code: Int, val body: String)

/**
 * Thin OkHttp wrapper for the opencode REST calls. Transport failures surface as
 * [Result.failure]; every actual HTTP response (including 4xx/5xx) is a success
 * carrying its status code, matching the Rust core's `HttpResult` handling.
 */
class HttpClient(
    val okhttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val jsonMedia = "application/json".toMediaType()

    suspend fun get(url: String, auth: String?): Result<HttpResp> =
        exec(Request.Builder().url(url).get(), auth)

    suspend fun postJson(url: String, body: String, auth: String?): Result<HttpResp> =
        exec(Request.Builder().url(url).post(body.toRequestBody(jsonMedia)), auth)

    suspend fun delete(url: String, auth: String?): Result<HttpResp> =
        exec(Request.Builder().url(url).delete(), auth)

    private suspend fun exec(builder: Request.Builder, auth: String?): Result<HttpResp> = withContext(Dispatchers.IO) {
        if (auth != null) builder.header("Authorization", auth)
        val request = builder.build()
        runCatching { await(okhttp.newCall(request)) }
    }

    private suspend fun await(call: Call): HttpResp = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = runCatching { it.body?.string() }.getOrNull().orEmpty()
                    if (cont.isActive) cont.resume(HttpResp(it.code, body))
                }
            }
        })
    }
}
