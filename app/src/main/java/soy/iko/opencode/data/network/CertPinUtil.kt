package soy.iko.opencode.data.network

import soy.iko.opencode.data.model.ServerProfile

/**
 * Shared helpers for resolving a [ServerProfile]'s certificate-pin field and the
 * http→https upgrade it implies. The logic is used in three places that must agree:
 * [HttpClientFactory] (the REST/SSE channel), [soy.iko.opencode.OpencodeApp]'s
 * PinnedImageCallFactory (the image-loading channel), and [soy.iko.opencode.ui.components.RemoteImage]
 * (resolving image base URLs). Previously each call site carried its own private copy
 * with a "must be kept in sync" comment; the helpers here are the single source of truth.
 */
internal object CertPinUtil {

    /** Split a certificate-pin field into individual OkHttp pins. Accepts whitespace- or
     *  comma-separated "sha256/<base64>" entries; blank entries are dropped. */
    fun parseCertPins(raw: String?): List<String> =
        raw?.split(Regex("[\\s,]+"))?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** The base URL after applying [ServerProfile.requireHttps]: a cleartext http:// URL is
     *  upgraded to https:// so the "Require HTTPS" choice is enforced at the transport layer.
     *  A configured certificate pin also forces TLS: a pin is meaningless over cleartext and
     *  would otherwise be silently dropped, so we honor the opt-in security control by upgrading
     *  rather than connecting unpinned in the clear. Relies on [HttpClientFactory.normalizeBaseUrl]
     *  for scheme/trailing-slash normalization. */
    fun effectiveBaseUrl(profile: ServerProfile): String {
        val normalized = HttpClientFactory.normalizeBaseUrl(profile.baseUrl)
        val forceHttps = profile.requireHttps || parseCertPins(profile.certPin).isNotEmpty()
        return if (forceHttps && normalized.lowercase().startsWith("http://")) {
            "https://" + normalized.substring("http://".length)
        } else {
            normalized
        }
    }
}
