package soy.iko.opencode.ui.components

import android.util.Base64
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import soy.iko.opencode.R
import soy.iko.opencode.data.model.FilePart
import soy.iko.opencode.data.model.ServerProfile
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
    return ImageLoadContext(baseUrl = baseUrl, basicAuthHeader = auth)
}

/** True if the part references a raster/vector image we can render. */
val FilePart.isImage: Boolean
    get() = mime?.startsWith("image/") == true

/**
 * Resolve a [FilePart] to a Coil-loadable model: a decoded [ByteArray] for inline
 * data URIs, an absolute URL string otherwise. Returns null when nothing loadable.
 */
private fun FilePart.resolveModel(ctx: ImageLoadContext): Any? {
    val src = source
    if (!src.isNullOrBlank() && src.startsWith("data:")) {
        val comma = src.indexOf(',')
        if (comma < 0) return null
        val payload = src.substring(comma + 1)
        return runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
    }
    val url = url ?: return null
    return if (url.startsWith("http://") || url.startsWith("https://")) {
        url
    } else {
        // Resolve the URL (relative or server-absolute like "/media/x.png") against the
        // base, collapsing any ../ segments.
        val base = HttpClientFactory.normalizeBaseUrl(ctx.baseUrl)
        val baseUri = runCatching { java.net.URI(base) }.getOrElse { return null }
        val resolved = runCatching {
            java.net.URI(base).resolve(url).normalize()
        }.getOrElse { return null }
        // Guard on same origin (scheme + host + port), not base-path prefix: the real
        // risk is redirecting the request (with its Basic auth) to another host. A
        // server-absolute path resolves to a different base *path* but the same origin,
        // so it must still be allowed — a prefix check wrongly rejected those.
        val sameOrigin = resolved.host != null &&
            resolved.scheme.equals(baseUri.scheme, ignoreCase = true) &&
            resolved.host.equals(baseUri.host, ignoreCase = true) &&
            resolved.port == baseUri.port
        if (sameOrigin) resolved.toString() else null
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
    val model = remember(part.source, part.url, ctx.baseUrl) { part.resolveModel(ctx) } ?: return
    val request = remember(part.source, part.url, ctx.baseUrl, ctx.basicAuthHeader) {
        ImageRequest.Builder(context)
            .data(model)
            .apply {
                // Only send Basic auth over HTTPS; sending credentials over
                // cleartext HTTP would expose them on the network.
                val isHttps = (model as? String)?.startsWith("https://") == true
                if (isHttps) ctx.basicAuthHeader?.let { addHeader("Authorization", it) }
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
        error = {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.BrokenImage,
                    contentDescription = stringResource(R.string.image_failed),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
