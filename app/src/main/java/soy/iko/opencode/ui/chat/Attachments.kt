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
    val max = NetworkConfig.maxAttachmentBytes
    // Cheap pre-check: if the provider reports a size, reject an oversize file before reading
    // a single byte into memory. The picker can hand back arbitrarily large files (e.g. a
    // multi-hundred-MB video), so reading first and checking after would OOM the app.
    val declaredSize = fileSize(context)
    if (declaredSize != null && declaredSize > max) return@withContext AttachmentResult.TooLarge
    // Read with a hard cap so a provider that under-reports or omits its size still can't OOM
    // us: stop as soon as we've read one byte past the cap, then reject.
    val bytes = runCatching {
        resolver.openInputStream(this@toAttachmentResult)?.use { it.readAtMost(max + 1) }
    }.getOrNull() ?: return@withContext AttachmentResult.Failed
    if (bytes.size > max) return@withContext AttachmentResult.TooLarge
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

/** The provider-reported byte size of the content [uri], or null if it isn't exposed. */
private fun Uri.fileSize(context: Context): Long? = runCatching {
    context.contentResolver.query(this, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else null
    }
}.getOrNull()

/** Read up to [limit] bytes from the stream without materializing the whole file, so an
 *  oversize source can be rejected on a bounded amount of memory. Reaching [limit] bytes is
 *  the caller's signal that the source exceeds the cap. */
private fun java.io.InputStream.readAtMost(limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8 * 1024)
    var total = 0L
    while (total < limit) {
        val want = minOf(chunk.size.toLong(), limit - total).toInt()
        val read = read(chunk, 0, want)
        if (read < 0) break
        out.write(chunk, 0, read)
        total += read
    }
    return out.toByteArray()
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
