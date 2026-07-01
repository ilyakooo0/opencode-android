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
import io.ktor.client.request.header
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

        val normalizedUrl = normalizeBaseUrl(profile.baseUrl)
        val isHttps = normalizedUrl.lowercase().startsWith("https://")

        if (profile.hasAuth) {
            if (isHttps) {
                install(Auth) {
                    basic {
                        credentials {
                            BasicAuthCredentials(
                                username = profile.username.orEmpty(),
                                password = profile.password.orEmpty(),
                            )
                        }
                        // Send eagerly so opencode doesn't need a 401 challenge round-trip.
                        // The Auth plugin is only installed for HTTPS profiles — over HTTP,
                        // even reactive (401-challenge) credential sending would leak
                        // passwords in cleartext, so we don't install it at all.
                        sendWithoutRequest { true }
                    }
                }
            } else {
                // For HTTP profiles with auth, send credentials proactively in the
                // Authorization header via defaultRequest (below) so the user's intent
                // to connect over cleartext is honored, but we never install the Auth
                // plugin's reactive challenge-response which would silently re-send
                // credentials on any 401 without checking the protocol.
            }
        }

        defaultRequest {
            url.takeFrom(URLBuilder().takeFrom(normalizedUrl))
            // For HTTP profiles with auth, attach the Basic auth header proactively on
            // every request (the Auth plugin is skipped for non-HTTPS). The user chose
            // cleartext explicitly, so we honor that — but without the reactive challenge
            // logic that could re-send credentials silently.
            if (profile.hasAuth && !isHttps) {
                val credentials = java.util.Base64.getEncoder().encodeToString(
                    "${profile.username.orEmpty()}:${profile.password.orEmpty()}".toByteArray(),
                )
                header("Authorization", "Basic $credentials")
            }
        }
    }

    /** Ensure a scheme and a single trailing slash so relative paths resolve correctly.
     *  Defaults to [https] when no scheme is given so credentials aren't accidentally
     *  sent over cleartext. Users who need plain HTTP must type the scheme explicitly. */
    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        // Compare the scheme prefix case-insensitively (a lowercased copy) while
        // preserving the original casing of the rest of the URL in the output.
        val lowerTrimmed = trimmed.lowercase()
        val withScheme = if (lowerTrimmed.startsWith("http://") || lowerTrimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }
}
