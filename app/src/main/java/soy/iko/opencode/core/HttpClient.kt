package soy.iko.opencode.core

import com.novi.serde.Bytes
import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.util.flattenEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Executes HTTP requests on behalf of the Crux core.
 *
 * When the core emits an [Effect.Http], the shell calls [request] with the
 * [HttpRequest] and returns the [HttpResult] back to the core via [Core.resolve].
 *
 * The core attaches the `Authorization` header when basic-auth credentials are
 * available, so this client simply forwards headers as-is — no auth logic here.
 */
class HttpClient {
    private val ktorHttpClient = KtorHttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }

    suspend fun request(request: HttpRequest): HttpResult = withContext(Dispatchers.IO) {
        try {
            val response = executeRequest(request)
            HttpResult.Ok(response)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            HttpResult.Err(toHttpError(e))
        }
    }

    private suspend fun executeRequest(request: HttpRequest): HttpResponse {
        val response = ktorHttpClient.request(request.url) {
            this.method = HttpMethod.parse(request.method)
            this.headers {
                for (header in request.headers) {
                    append(header.name, header.value)
                }
            }
            if (request.body.content.isNotEmpty()) {
                setBody(request.body.content)
            }
        }
        val bytes: ByteArray = response.bodyAsText().toByteArray()
        val headers = response.headers
            .flattenEntries()
            .map { HttpHeader(it.first, it.second) }
        return HttpResponse(
            status = response.status.value.toUShort(),
            headers = headers,
            body = Bytes(bytes),
        )
    }

    private fun toHttpError(error: Throwable): HttpError = when (error) {
        is SocketTimeoutException -> HttpError.Timeout
        is UnknownHostException -> HttpError.Io("Unknown host")
        else -> HttpError.Io(error.message ?: "HTTP request failed")
    }
}
