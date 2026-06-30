package soy.iko.opencode.data.network

/**
 * Centralized tuning parameters for the network and presentation layers. Keeping these
 * in one place makes them easy to find and adjust without hunting through source files,
 * and makes the intent of each value explicit rather than a bare magic number.
 */
object NetworkConfig {

    // --- OkHttp engine timeouts (HttpClientFactory) ---

    /** Socket connect timeout for all HTTP calls, including the SSE handshake. */
    const val connectTimeoutSeconds = 30L
    /** Socket read timeout for ordinary REST calls. The SSE stream overrides this to INFINITE. */
    const val readTimeoutSeconds = 60L
    /** OkHttp WebSocket/interval ping, keeping the SSE socket alive through proxies. */
    const val pingIntervalSeconds = 20L
    /** Request-level timeout for REST calls (covers connect + headers + body). The SSE stream overrides this to INFINITE. */
    const val restRequestTimeoutMs = 60_000L

    // --- REST retry (OpencodeApiClient.withRetry) ---

    /** Maximum number of attempts for a retriable REST call. */
    const val retryMaxAttempts = 3
    /** Initial backoff delay; doubles on each successive failure (exponential backoff). */
    const val retryInitialDelayMs = 500L
    /** Cap on a single retry backoff delay, guarding against overflow at high attempt counts. */
    const val retryMaxDelayMs = 30_000L
    /** Jitter factor for retry backoff (0.2 = ±20% symmetric random jitter on each delay). */
    const val retryJitterFactor = 0.2

    // --- SSE stream (EventStreamClient) ---

    /** Initial reconnect backoff after an SSE stream drop; doubles up to [sseMaxBackoffMs]. */
    const val sseInitialBackoffMs = 500L
    /** Cap on the SSE reconnect backoff. */
    const val sseMaxBackoffMs = 10_000L
    /** Max gap between events before a silent/half-open SSE connection is dropped and reconnected. */
    const val sseIdleTimeoutMs = 90_000L
    /** Buffer capacity for the SSE events SharedFlow; prevents a slow subscriber stalling the read loop. */
    const val sseEventBufferCapacity = 64

    // --- Catalog cache (OpencodeApiClient) ---

    /** How long cached catalog responses (providers/agents/commands) are considered fresh. */
    const val catalogCacheTtlMs = 60_000L

    // --- Session list (SessionListViewModel) ---

    /** Max sessions to fetch previews for in one batch. */
    const val maxPreviewSessions = 50
    /** Max concurrent preview fetches (prevents flooding the server with parallel requests). */
    const val maxConcurrentPreviews = 8
    /** Max characters of a session's last message to keep as a list preview. */
    const val previewTextMaxLength = 200

    // --- In-memory message store (SessionRepository.MessageStore) ---

    /** Max messages to keep in memory per observed session; oldest are evicted beyond this. */
    const val maxInMemoryMessages = 500

    // --- File browser (FileBrowserViewModel) ---

    /** Debounce delay before firing a file-search request after the user stops typing. */
    const val fileSearchDebounceMs = 250L
}
