package soy.iko.opencode.ui.components

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import soy.iko.opencode.R
import soy.iko.opencode.data.model.FilePart
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.model.sourcePath
import soy.iko.opencode.data.network.HttpClientFactory
import soy.iko.opencode.data.network.NetworkConfig

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
    // Bumping retryKey re-builds the ImageRequest, so a failed load can be re-issued by tapping
    // the error state instead of being a permanent broken-image icon.
    var retryKey by remember(part.source, part.url, ctx.baseUrl) { mutableIntStateOf(0) }
    // Fullscreen zoomable viewer state: opened by tapping the inline image.
    var showFullscreen by remember { mutableStateOf(false) }
    val request = remember(part.source, part.url, ctx.baseUrl, ctx.basicAuthHeader, model, retryKey) {
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
            .heightIn(max = NetworkConfig.inlineImageMaxHeightDp.dp)
            .clip(MaterialTheme.shapes.small)
            // Tap to open a fullscreen zoomable viewer so the user can inspect details
            // without the inline 320dp height cap.
            .clickable(role = Role.Button) { showFullscreen = true },
        loading = {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = NetworkConfig.inlineImageMinHeightDp.dp),
                contentAlignment = Alignment.Center,
            ) {
                val loadingLabel = stringResource(R.string.loading)
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = loadingLabel },
                )
            }
        },
        error = { ImageRetry(onRetry = { retryKey++ }) },
    )
    if (showFullscreen) {
        FullscreenImageViewer(request = request, contentDescription = part.filename ?: stringResource(R.string.image)) {
            showFullscreen = false
        }
    }
}

/**
 * Fullscreen zoomable image viewer opened by tapping an inline [RemoteImage]. Supports
 * pinch-to-zoom and pan via [detectTransformGestures], double-tap to toggle zoom, and
 * re-centers when zoom returns to 1×. Uses a non-interactive Dialog window so it overlays
 * the whole screen.
 */
@Composable
private fun FullscreenImageViewer(
    request: ImageRequest,
    contentDescription: String,
    onDismiss: () -> Unit,
) {
    // Scale is an Animatable so a double-tap smoothly tweens between 1× and the zoom target,
    // while pinch-to-zoom drives it via snapTo (no animation) for immediate 1:1 finger tracking.
    val scale = remember { Animatable(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    // Double-tap zoom tween, collapsed to an instant snap under reduced motion. Resolved in
    // the composable body (not inside the pointerInput coroutine) since it reads the
    // composition's LocalReducedMotion.
    val zoomSpec = rememberMotionTween<Float>(NetworkConfig.imageViewerZoomAnimMs)
    // Swipe-to-dismiss: track the vertical drag offset. When the image is at 1× (not zoomed
    // or panned) and the user drags down beyond a threshold, dismiss the viewer — a common
    // gesture for photo viewers. The drag also fades the background so the dismiss reads as
    // a continuous motion rather than a snap.
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val dismissThreshold = NetworkConfig.imageViewerSwipeDismissThreshold
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = (1f - (dragOffsetY.absoluteValue / dismissThreshold)).coerceIn(0f, 1f)))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // Clamp the zoom so the image can't be scaled down below 1× (which would
                        // leave empty letterbox) or blown up so far it becomes a handful of pixels.
                        val newScale = (scale.value * zoom).coerceIn(1f, NetworkConfig.imageViewerMaxZoom)
                        scope.launch { scale.snapTo(newScale) }
                        if (newScale <= 1f) {
                            // Returning to 1× recenters so a prior pan doesn't leave the image
                            // stuck off-center at its rest size.
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }
                }
                // Swipe-to-dismiss: only when the image is at 1× (not zoomed/panned), a
                // vertical drag accumulates an offset; crossing the threshold dismisses.
                .pointerInput(scale.value) {
                    if (scale.value <= 1.001f) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (dragOffsetY.absoluteValue >= dismissThreshold) {
                                    onDismiss()
                                } else {
                                    // Animate the snap-back to rest. A simple reset; the user's
                                    // finger is already lifting so a full spring isn't needed.
                                    dragOffsetY = 0f
                                }
                            },
                            onVerticalDrag = { _, dragAmount ->
                                // Only accumulate downward drags (positive Y) for dismiss, so an
                                // upward swipe doesn't accidentally trigger it.
                                if (dragAmount > 0) dragOffsetY += dragAmount
                            },
                        )
                    }
                }
                // Tap handling on the same surface: a double-tap toggles zoom (animated, with a
                // recenter on the way back to 1×); a single tap dismisses the viewer. detectTapGestures
                // defers the single-tap callback until it knows the touch isn't the first of a pair.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            scope.launch {
                                if (scale.value > 1.001f) {
                                    offsetX = 0f
                                    offsetY = 0f
                                    scale.animateTo(1f, zoomSpec)
                                } else {
                                    scale.animateTo(NetworkConfig.imageViewerDoubleTapZoom, zoomSpec)
                                }
                            }
                        },
                        onTap = { onDismiss() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            SubcomposeAsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale.value,
                        scaleY = scale.value,
                        translationX = offsetX,
                        // Apply the swipe-drag offset on top of any pan offset so the image
                        // follows the finger during a dismiss swipe.
                        translationY = offsetY + dragOffsetY,
                    ),
            )
            // Close button in the top corner, inset below the status bar so it doesn't sit
            // under the notch / status bar overlay on edge-to-edge devices.
            androidx.compose.material3.IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(end = 8.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
        }
    }
}

/** Error slot for [RemoteImage]: a broken-image icon plus a "Tap to retry" caption, the whole box
 *  clickable so a failed load can be re-issued instead of stranding a permanent broken icon. */
@Composable
private fun ImageRetry(onRetry: () -> Unit) {
    val label = stringResource(R.string.tap_to_retry)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NetworkConfig.inlineImageMinHeightDp.dp)
            .clickable(role = Role.Button, onClick = onRetry),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrokenImageIcon()
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Sentinel for [RemoteImage]'s produceState while resolveModel runs off-thread, so the initial
 *  (still-resolving) frame is distinguishable from a resolved-to-null (unloadable) result. */
private val ImageResolving = Any()

/** A centered, fixed-min-height box matching the loading/error slots so an image placeholder
 *  reserves the same space whether it's loading, failed, or unresolvable. */
@Composable
private fun ImageStatusBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = NetworkConfig.inlineImageMinHeightDp.dp),
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
