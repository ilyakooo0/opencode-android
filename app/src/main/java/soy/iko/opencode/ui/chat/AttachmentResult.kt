package soy.iko.opencode.ui.chat

/** Outcome of turning a picked Uri into a [PendingAttachment] (see `Uri.toAttachmentResult`). */
sealed interface AttachmentResult {
    data class Ok(val attachment: PendingAttachment) : AttachmentResult

    /** The file exceeded the size cap ([soy.iko.opencode.data.network.NetworkConfig.maxAttachmentBytes]). */
    data object TooLarge : AttachmentResult

    /** The file couldn't be read/resolved. */
    data object Failed : AttachmentResult
}
