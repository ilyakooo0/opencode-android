package soy.iko.opencode.ui.components

import android.util.Base64
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import soy.iko.opencode.R
import soy.iko.opencode.data.model.FilePart
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.model.sourcePath
import soy.iko.opencode.data.network.HttpClientFactory

/**
 * Carries the bits needed to load an image off the opencode server: the base URL (for
 * resolving relative `url`s) and an optional HTTP Basic header for protected servers.
 */
@Immutable
data class ImageLoadContext(
    val baseUrl: String,
    val basicAuthHeader: String?,
)

/** Build an [ImageLoadContext] from a resolved connection profile (password included). */
fun ServerProfile.toImageContext(): ImageLoadContext {
    val auth = if (hasAuth && !password.isNullOrEmpty()) {
        val raw = "$username:$password".toByteArray()
        "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    } else {
        null
    }
    // Resolve image URLs against the HTTPS-upgraded base URL, not the raw stored one, so a
    // requireHttps (or certificate-pinned) profile never fetches an image — with the Basic auth
    // header attached — over cleartext http even when the stored URL is http://. This mirrors
    // how HttpClientFactory computes the effective base URL for the REST/SSE channel.
    return ImageLoadContext(baseUrl = effectiveImageBaseUrl(), basicAuthHeader = auth)
}

/**
 * The base URL used to resolve image URLs, after applying the same http->https upgrade the
 * REST/SSE client uses (see HttpClientFactory.effectiveBaseUrl, the source of truth — that
 * helper and its pin parser are private there and this file may not edit HttpClientFactory, so
 * the logic is replicated here and must be kept in sync). A pin is meaningless over cleartext, so
 * a configured certificate pin forces HTTPS too, matching the REST channel.
 */
private fun ServerProfile.effectiveImageBaseUrl(): String {
    val normalized = HttpClientFactory.normalizeBaseUrl(baseUrl)
    val forceHttps = requireHttps || parseCertPins(certPin).isNotEmpty()
    return if (forceHttps && normalized.lowercase().startsWith("http://")) {
        "https://" + normalized.substring("http://".length)
    } else {
        normalized
    }
}

/** Split a certificate-pin field into individual pins; mirrors HttpClientFactory.parsePins. */
private fun parseCertPins(raw: String?): List<String> =
    raw?.split(Regex("[\\s,]+"))?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

/** True if the part references a raster/vector image we can render. */
val FilePart.isImage: Boolean
    get() = mime?.startsWith("image/") == true

private fun decodeDataUri(data: String): ByteArray? {
    val comma = data.indexOf(',')
    if (comma < 0) return null
    val header = data.substring(0, comma)
    val payload = data.substring(comma + 1)
    // Only ";base64" payloads are Base64; a plain/percent-encoded data URI (e.g.
    // "data:image/svg+xml,%3Csvg…") must be percent-decoded to bytes instead of Base64-decoded,
    // which would throw and render the broken-image icon.
    return runCatching {
        if (header.contains(";base64", ignoreCase = true)) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            android.net.Uri.decode(payload).toByteArray()
        }
    }.getOrNull()
}

/**
 * Resolve a [FilePart] to a Coil-loadable model: a decoded [ByteArray] for inline
 * data URIs, an absolute URL string otherwise. Returns null when nothing loadable.
 */
private fun FilePart.resolveModel(ctx: ImageLoadContext): Any? {
    val src = sourcePath
    if (!src.isNullOrBlank() && src.startsWith("data:")) return decodeDataUri(src)
    val url = url ?: return null
    // Attachments are persisted as a data URL in `part.url`, so a data URI can arrive here too.
    if (url.startsWith("data:")) return decodeDataUri(url)
    // Resolve the URL (absolute, relative, or server-absolute like "/media/x.png") against
    // the base, collapsing any ../ segments. Both absolute and relative forms must clear the
    // same-origin check below — an absolute foreign URL (e.g. "https://evil.com/x.png") would
    // otherwise get the server's Basic auth attached and leak the credentials to that host.
    val base = HttpClientFactory.normalizeBaseUrl(ctx.baseUrl)
    val baseUri = runCatching { java.net.URI(base) }.getOrElse { return null }
    val resolved = runCatching {
        java.net.URI(base).resolve(url).normalize()
    }.getOrElse { return null }
    // Guard on same origin (scheme + host + port), not base-path prefix: the real risk is
    // sending the request (with its Basic auth) to another host. A server-absolute path
    // resolves to a different base *path* but the same origin, so it must still be allowed.
    // Compare *effective* ports (substituting the scheme default when unspecified) so an
    // absolute image URL that spells out the default port — "https://host:443/x.png" while
    // the base is "https://host/" — isn't wrongly rejected as cross-origin (URI.port is -1
    // when omitted, so a raw `==` would treat 443 and -1 as different).
    val sameOrigin = resolved.host != null &&
        resolved.scheme.equals(baseUri.scheme, ignoreCase = true) &&
        resolved.host.equals(baseUri.host, ignoreCase = true) &&
        effectivePort(resolved) == effectivePort(baseUri)
    return if (sameOrigin) resolved.toString() else null
}

/** The URI's port, or the scheme's default (80/443) when unspecified (`port == -1`). */
private fun effectivePort(uri: java.net.URI): Int {
    if (uri.port != -1) return uri.port
    return when (uri.scheme?.lowercase()) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }
}

/**
 * Renders an image attachment from a [FilePart]. Handles inline data URIs (no network)
 * and server-relative URLs (fetched with Basic auth when the server requires it).
 * Falls back to nothing when the part has no loadable source.
 */
@Composable
fun RemoteImage(part: FilePart, ctx: ImageLoadContext, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Distinguish "still resolving off-thread" from "resolved to nothing loadable": produceState
    // seeds a sentinel so the first (pre-resolve) frame renders nothing (as before), but once
    // resolveModel returns null — a cross-origin URL (blocked so the server's Basic auth can't
    // leak off-origin), a malformed data URI, or a source with no path — we show the broken-image
    // fallback instead of an empty gap (PartView already committed to RemoteImage, so there's no
    // FileChip to fall through to).
    val model = produceState<Any?>(initialValue = ImageResolving, part.source, part.url, ctx.baseUrl) {
        value = withContext(Dispatchers.Default) { part.resolveModel(ctx) }
    }.value
    if (model === ImageResolving) return
    if (model == null) {
        ImageStatusBox { BrokenImageIcon() }
        return
    }
    val request = remember(part.source, part.url, ctx.baseUrl, ctx.basicAuthHeader, model) {
        ImageRequest.Builder(context)
            .data(model)
            .apply {
                // resolveModel already verified same-origin, so a String model can only point at
                // the user's own server — attaching Basic auth over http (not just https) is safe.
                // A data-URI model is a ByteArray, so gate on the model being a URL String.
                if (model is String) ctx.basicAuthHeader?.let { addHeader("Authorization", it) }
            }
            .crossfade(true)
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = part.filename ?: stringResource(R.string.image),
        contentScale = ContentScale.FillWidth,
        modifier = modifier
            .heightIn(max = 320.dp)
            .clip(MaterialTheme.shapes.small),
        loading = {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                contentAlignment = Alignment.Center,
            ) {
                val loadingLabel = stringResource(R.string.loading)
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = loadingLabel },
                )
            }
        },
        error = { ImageStatusBox { BrokenImageIcon() } },
    )
}

/** Sentinel for [RemoteImage]'s produceState while resolveModel runs off-thread, so the initial
 *  (still-resolving) frame is distinguishable from a resolved-to-null (unloadable) result. */
private val ImageResolving = Any()

/** A centered, fixed-min-height box matching the loading/error slots so an image placeholder
 *  reserves the same space whether it's loading, failed, or unresolvable. */
@Composable
private fun ImageStatusBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun BrokenImageIcon() {
    Icon(
        Icons.Filled.BrokenImage,
        contentDescription = stringResource(R.string.image_failed),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
