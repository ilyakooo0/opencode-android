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
import okhttp3.CertificatePinner
import java.util.concurrent.TimeUnit

/**
 * Builds a Ktor client bound to a single [ServerProfile]. One client per active
 * profile: it carries the base URL, optional Basic auth, JSON negotiation, and the
 * SSE plugin used for `GET /event`.
 */
object HttpClientFactory {

    fun create(profile: ServerProfile): HttpClient {
        // Resolve per-profile TLS options up front so they're available inside the engine
        // config: optionally upgrade a cleartext http:// URL to https:// ("Require HTTPS"),
        // and pin the server certificate(s) for HTTPS connections.
        val normalizedUrl = effectiveBaseUrl(profile)
        val isHttps = normalizedUrl.lowercase().startsWith("https://")
        val pinHost = runCatching { java.net.URI(normalizedUrl).host }.getOrNull()
        val pins = parsePins(profile.certPin)

        return HttpClient(OkHttp) {
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
                    // Pin the server certificate(s) when configured and connecting over TLS.
                    // A malformed pin throws here (surfaced as a connection failure), which is
                    // the right fail-closed behavior for a security control the user opted into.
                    if (pins.isNotEmpty() && pinHost != null && isHttps) {
                        certificatePinner(
                            CertificatePinner.Builder().apply { pins.forEach { add(pinHost, it) } }.build(),
                        )
                    }
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
            if (isHttps) {
                install(Auth) {
                    basic {
                        credentials {
                            BasicAuthCredentials(
                                username = profile.username.orEmpty(),
                                password = profile.password.orEmpty(),
                            )
                        }
                        // Send eagerly so opencode doesn't need a 401 challenge round-trip, but
                        // only to the configured host: a cross-host redirect must not carry the
                        // credentials off-origin (the plugin would still answer a genuine 401 from
                        // the real host reactively). The Auth plugin is only installed for HTTPS
                        // profiles — over HTTP, even reactive credential sending would leak
                        // passwords in cleartext, so we don't install it at all.
                        sendWithoutRequest { request ->
                            pinHost != null && request.url.host.equals(pinHost, ignoreCase = true)
                        }
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
    }

    /** The base URL after applying [ServerProfile.requireHttps]: a cleartext http:// URL is
     *  upgraded to https:// so the "Require HTTPS" choice is enforced at the transport layer.
     *  A configured certificate pin also forces TLS: a pin is meaningless over cleartext and
     *  would otherwise be silently dropped, so we honor the opt-in security control by upgrading
     *  rather than connecting unpinned in the clear. */
    private fun effectiveBaseUrl(profile: ServerProfile): String {
        val normalized = normalizeBaseUrl(profile.baseUrl)
        val forceHttps = profile.requireHttps || parsePins(profile.certPin).isNotEmpty()
        return if (forceHttps && normalized.lowercase().startsWith("http://")) {
            "https://" + normalized.substring("http://".length)
        } else {
            normalized
        }
    }

    /** Split a certificate-pin field into individual OkHttp pins. Accepts whitespace- or
     *  comma-separated "sha256/<base64>" entries; blank entries are dropped. */
    private fun parsePins(raw: String?): List<String> =
        raw?.split(Regex("[\\s,]+"))?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

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
