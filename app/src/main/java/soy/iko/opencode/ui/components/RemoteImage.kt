package soy.iko.opencode.ui.components

import android.util.Base64
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import soy.iko.opencode.R
import soy.iko.opencode.data.model.FilePart
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.network.HttpClientFactory

/**
 * Carries the bits needed to load an image off the opencode server: the base URL (for
 * resolving relative `url`s) and an optional HTTP Basic header for protected servers.
 */
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
        // Resolve relative URL against the base. Collapse any ../ segments to
        // prevent path traversal from escaping the server's base path.
        val base = HttpClientFactory.normalizeBaseUrl(ctx.baseUrl)
        val resolved = java.net.URI(base).resolve(url).normalize().toString()
        // Guard: if normalization escaped the base path, fall back to the base.
        if (!resolved.startsWith(base)) base else resolved
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
    AsyncImage(
        model = request,
        contentDescription = part.filename ?: stringResource(R.string.image),
        contentScale = ContentScale.FillWidth,
        modifier = modifier
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(12.dp)),
    )
}
