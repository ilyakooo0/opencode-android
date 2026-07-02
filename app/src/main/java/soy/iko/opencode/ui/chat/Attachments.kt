package soy.iko.opencode.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import soy.iko.opencode.data.model.FilePromptPart
import soy.iko.opencode.data.network.NetworkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read [uri]'s bytes off the main thread, base64-encode them into a `data:` URL, and wrap
 * the result as a [PendingAttachment] ready to send. Enforces the size cap. The original
 * [uri] is kept as the thumbnail model for images (Coil loads content Uris directly).
 */
suspend fun Uri.toAttachmentResult(context: Context): AttachmentResult = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val mime = resolver.getType(this@toAttachmentResult) ?: "application/octet-stream"
    val name = displayName(context) ?: defaultName(mime)
    val bytes = runCatching {
        resolver.openInputStream(this@toAttachmentResult)?.use { it.readBytes() }
    }.getOrNull() ?: return@withContext AttachmentResult.Failed
    if (bytes.size > NetworkConfig.maxAttachmentBytes) return@withContext AttachmentResult.TooLarge
    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    val dataUrl = "data:$mime;base64,$b64"
    AttachmentResult.Ok(
        PendingAttachment(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            mime = mime,
            previewModel = if (mime.startsWith("image/")) this@toAttachmentResult.toString() else null,
            part = FilePromptPart(mime = mime, url = dataUrl, filename = name),
        ),
    )
}

/** Resolve a human-readable display name for the content [uri], if the provider exposes one. */
private fun Uri.displayName(context: Context): String? = runCatching {
    context.contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
}.getOrNull()?.takeIf { it.isNotBlank() }

private fun defaultName(mime: String): String {
    val ext = mime.substringAfterLast('/', "bin").takeIf { it.isNotBlank() } ?: "bin"
    return "attachment.$ext"
}

/**
 * Create a temp file in the cache and a shareable content [Uri] for it (via the app's
 * [FileProvider]) to hand to the camera as the capture target. Returns null if it can't be
 * created. The file is overwritten on each capture; the OS cleans the cache dir.
 */
fun newCameraCaptureUri(context: Context): Uri? = runCatching {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}.getOrNull()
