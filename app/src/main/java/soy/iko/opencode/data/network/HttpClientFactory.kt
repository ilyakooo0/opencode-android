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
                // A normal read timeout for REST calls. The SSE stream overrides this
                // to infinite inside the sse {} request block (see EventStreamClient),
                // so the long-lived event connection is never killed mid-stream.
                readTimeout(NetworkConfig.readTimeoutSeconds, TimeUnit.SECONDS)
                connectTimeout(NetworkConfig.connectTimeoutSeconds, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
                pingInterval(NetworkConfig.pingIntervalSeconds, TimeUnit.SECONDS)
            }
        }

        install(ContentNegotiation) {
            json(OpencodeJson)
        }

        install(SSE)

        // Request-level timeout for REST calls. The SSE stream overrides this to
        // infinite inside the sse {} request block (see EventStreamClient) so the
        // long-lived /event connection isn't killed after 60s. The engine's connect
        // timeout and the SSE idle-timeout watchdog handle stuck SSE connections.
        install(HttpTimeout) {
            requestTimeoutMillis = NetworkConfig.restRequestTimeoutMs
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
