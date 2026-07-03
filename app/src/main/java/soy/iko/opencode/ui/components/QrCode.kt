@file:Suppress("MatchingDeclarationName")

package soy.iko.opencode.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import soy.iko.opencode.R
import soy.iko.opencode.data.model.ServerProfile

/**
 * Wire format for sharing a [ServerProfile] as a QR code (and re-importing it). Only the
 * connection-relevant fields travel — [ServerProfile.id] and [ServerProfile.lastUsed] are
 * device-local and excluded. Marked with a [v] version tag so a future format change can be
 * detected and rejected instead of mis-parsed.
 */
@Serializable
data class ServerProfileQr(
    val v: Int = 1,
    val label: String = "",
    val baseUrl: String,
    val username: String? = null,
    val password: String? = null,
    val requireHttps: Boolean = false,
    val certPin: String? = null,
)

/** Lenient JSON for QR encode/decode (independent of [soy.iko.opencode.data.network.OpencodeJson],
 *  which is tuned for the server's polymorphic payloads). */
private val qrJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Serialize a [ServerProfile] to the QR payload string. */
fun ServerProfile.toQrPayload(): String = qrJson.encodeToString(
    ServerProfileQr.serializer(),
    ServerProfileQr(
        label = label,
        baseUrl = baseUrl,
        username = username,
        password = password,
        requireHttps = requireHttps,
        certPin = certPin,
    ),
)

/** Parse a scanned/decoded QR payload back into a [ServerProfileQr], or null if it isn't one of
 *  our payloads (so an arbitrary QR doesn't blow up the import flow). */
fun parseQrPayload(payload: String): ServerProfileQr? = runCatching {
    qrJson.decodeFromString(ServerProfileQr.serializer(), payload)
}.getOrNull()

/** Render a QR code carrying [content] at [sizePx] pixels, or null if encoding fails. Uses ZXing's
 *  core (no camera/UI deps) so it stays a pure-Java encode. */
fun encodeQrBitmap(content: String, sizePx: Int): Bitmap? = runCatching {
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            pixels[offset + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}.getOrNull()

/** Decode a QR code from a decoded [Bitmap] (e.g. an image the user picked to import a server),
 *  returning its text content or null if no QR was found. */
fun decodeQrFromBitmap(bitmap: Bitmap): String? = runCatching {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val source = RGBLuminanceSource(width, height, pixels)
    val binary = BinaryBitmap(HybridBinarizer(source))
    val reader = MultiFormatReader()
    reader.setHints(mapOf(DecodeHintType.TRY_HARDER to true))
    reader.decodeWithState(binary).text
}.getOrNull()

/** Decode a server-profile QR from an image [uri] (the result of an image picker). Runs Bitmap
 *  decode + ZXing scan on the caller's dispatcher. Returns null if the image couldn't be read or
 *  its QR isn't one of our server payloads. */
suspend fun decodeServerQr(context: android.content.Context, uri: android.net.Uri): ServerProfileQr? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val bitmap = android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                ?: return@runCatching null
            decodeQrFromBitmap(bitmap)?.let { parseQrPayload(it) }
        }.getOrNull()
    }

/** A Compose QR code image. The QR is generated off the composition; encoding a few hundred
 *  modules is sub-millisecond so no background dispatching is warranted. Drawn on a white
 *  background (QR scanners need the light module) with a small inner pad so the code isn't flush
 *  with the dialog edges. */
@Composable
fun QrImage(content: String, sizePx: Int, modifier: Modifier = Modifier) {
    val bitmap = remember(content, sizePx) { encodeQrBitmap(content, sizePx) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier
                .size(sizePx.dp)
                .background(Color.White)
                .padding(8.dp),
        )
    }
}

/** Dialog that shows a server profile as a scannable QR code, for transferring a profile (URL +
 *  credentials + cert pin) to another device without re-typing. The payload includes the password
 *  when present; a warning makes that explicit. */
@Composable
fun QrShareDialog(profile: ServerProfile, onDismiss: () -> Unit) {
    val payload = remember(profile.id, profile.baseUrl, profile.password) { profile.toQrPayload() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        title = { Text(profile.displayLabel) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                QrImage(content = payload, sizePx = 256)
                Spacer(Modifier.size(8.dp))
                Text(
                    profile.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (profile.password != null) {
                    Text(
                        stringResource(R.string.qr_includes_password_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}
