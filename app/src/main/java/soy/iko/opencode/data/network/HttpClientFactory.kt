package soy.iko.opencode.data.network

import soy.iko.opencode.data.model.ServerProfile
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit

/**
 * Builds a Ktor client bound to a single [ServerProfile]. One client per active
 * profile: it carries the base URL, optional Basic auth, JSON negotiation, and the
 * SSE plugin used for `GET /event`.
 */
object HttpClientFactory {

    fun create(profile: ServerProfile): HttpClient = HttpClient(OkHttp) {
        expectSuccess = true

        engine {
            config {
                // The SSE stream is long-lived; a socket read timeout would kill it
                // mid-stream. REST calls rely on the connect timeout + withRetry for
                // resilience instead. The SSE idle-timeout watchdog in EventStreamClient
                // handles half-open connections.
                readTimeout(0, TimeUnit.MILLISECONDS)
                connectTimeout(NetworkConfig.connectTimeoutSeconds, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
                pingInterval(NetworkConfig.pingIntervalSeconds, TimeUnit.SECONDS)
            }
        }

        install(ContentNegotiation) {
            json(OpencodeJson)
        }

        install(SSE)

        // Disable the request-level timeout at the client level — it covers the whole
        // call including the body stream, so it would kill the long-lived SSE `/event`
        // connection after 60s (the previous default). The engine's connect timeout
        // and the SSE idle-timeout watchdog handle stuck connections instead.
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
        }

        if (profile.hasAuth) {
            install(Auth) {
                basic {
                    credentials {
                        BasicAuthCredentials(
                            username = profile.username.orEmpty(),
                            password = profile.password.orEmpty(),
                        )
                    }
                    // Send eagerly so opencode doesn't need a 401 challenge round-trip.
                    sendWithoutRequest { true }
                }
            }
        }

        defaultRequest {
            url.takeFrom(URLBuilder().takeFrom(normalizeBaseUrl(profile.baseUrl)))
        }
    }

    /** Ensure a scheme and a single trailing slash so relative paths resolve correctly. */
    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }
}
