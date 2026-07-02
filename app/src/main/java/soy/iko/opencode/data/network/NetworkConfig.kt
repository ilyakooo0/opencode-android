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
    /** Debounce delay before fetching a session preview after an SSE update, so a burst
     *  of SessionUpdated events (e.g. during active streaming) coalesces into one fetch
     *  instead of launching one per event (each downloading the full message history). */
    const val previewDebounceMs = 500L
    /** Debounce for applying SessionUpdated SSE events to the session list state. During
     *  active streaming the server emits these frequently; without debouncing each one
     *  triggers a full list filter + sort + recomposition, causing scroll jank. */
    const val sessionUpdateDebounceMs = 300L

    // --- Global search (GlobalSearchViewModel) ---

    /** Max sessions whose message history is scanned in one global-search pass. Global
     *  search downloads each session's messages, so this bounds the work; if there are more
     *  sessions, the result set notes that the search was truncated. */
    const val maxSearchSessions = 50
    /** Debounce before a global search fires, so typing doesn't launch a fetch per keystroke. */
    const val searchDebounceMs = 350L
    /** Minimum query length before a global search runs (shorter queries match too much). */
    const val minSearchQueryLength = 2
    /** Characters of context to show around a global-search match. */
    const val searchSnippetLength = 160

    // --- Message cache (MessageCacheStore) ---

    /** Minimum interval between on-disk writes of a session's message snapshot, so a fast
     *  token stream doesn't hammer the disk. The cache only needs to be recent enough for an
     *  instant/offline first paint; the network corrects it on the next open. */
    const val messageCacheWriteThrottleMs = 2_000L

    /** Grace period before a deferred session delete actually fires, during which an
     *  Undo snackbar lets the user cancel it. */
    const val undoDeleteDelayMs = 5_000L

    /** Grace period before a deferred server-profile delete actually fires, during which
     *  an Undo snackbar lets the user cancel it. Same UX rationale as [undoDeleteDelayMs]. */
    const val undoServerDeleteDelayMs = 5_000L

    /** Grace period before a deferred crash-report delete actually fires, during which
     *  an Undo snackbar lets the user cancel it. */
    const val undoReportDeleteDelayMs = 5_000L

    // --- In-memory message store (SessionRepository.MessageStore) ---

    /** Max messages to keep in memory per observed session; oldest are evicted beyond this. */
    const val maxInMemoryMessages = 500

    // --- File browser (FileBrowserViewModel) ---

    /** Debounce delay before firing a file-search request after the user stops typing. */
    const val fileSearchDebounceMs = 250L

    // --- Attachments (chat composer) ---

    /** Max size of a single attachment (image/file) before it's rejected. Base64 inflates
     *  the payload ~33%, and the whole prompt body is held in memory, so cap generously but
     *  finitely to avoid OOM / oversized requests. */
    const val maxAttachmentBytes = 10L * 1024 * 1024

    /** Max number of attachments staged for one prompt, to bound memory and request size. */
    const val maxAttachments = 8

    // --- StateFlow sharing (WhileSubscribed) ---

    /** Grace period before a cold StateFlow is stopped after its last subscriber leaves. */
    const val stateFlowSubscriptionTimeoutMs = 5_000L

    // --- Draft persistence (ChatViewModel) ---

    /** Debounce delay before persisting a draft to disk after the user stops typing. */
    const val draftDebounceMs = 500L

    /** Maximum characters allowed in the chat input field. A generous cap that prevents
     *  a huge paste from stalling the UI (the field would otherwise buffer and lay out
     *  an unbounded string) while leaving plenty of room for long prompts. */
    const val maxDraftLengthChars = 32_000

    /** Show the "remaining characters" supportingText under the chat input once the
     *  draft length crosses this fraction of [maxDraftLengthChars], so the cap is
     *  visible before it silently kicks in but doesn't clutter a normal short prompt. */
    const val draftCountdownThresholdFraction = 0.8f

    /** Maximum characters allowed in a session title (rename dialog). Prevents the
     *  server from rejecting an overly long title and keeps list rows readable. */
    const val maxSessionTitleChars = 200

    // --- Profile store (ServerEditViewModel) ---

    /** Timeout for loading a profile from DataStore before giving up. */
    const val profileLoadTimeoutMs = 5_000L

    // --- SSE stream (EventStreamClient, AppContainer) ---

    /** Delay before retrying the message-activity observer after a failure. */
    const val observerRetryDelayMs = 5_000L
    /** Max concurrent assistant runs to track for completion notifications. */
    const val activeRunsLimit = 200

    // --- Markdown rendering (MarkdownText) ---

    /** Throttle delay to coalesce streaming tokens into one re-parse. The markdown
     *  library re-parses the full AST on every content change, so during a long streaming
     *  response this is O(n²) work. 50ms (~20fps) is smooth for progressively appearing
     *  text while cutting parse work ~3x versus a per-frame (16ms) throttle. */
    const val streamingThrottleMs = 50L

    // --- Snackbar one-shot events (ViewModels) ---

    /** Buffer capacity for transient error SharedFlows that drive snackbars. */
    const val snackbarEventBufferCapacity = 16

    /** How long the chat top-bar refresh icon stays in its "refreshing" spinner state
     *  after a tap, giving immediate feedback that the refresh was triggered even when
     *  the connection state is already Connected (so the ConnectionBanner doesn't change). */
    const val refreshFeedbackMs = 1_200L

    // --- UI layout constants ---

    /** Minimum window width (dp) for two-pane layout on tablets / unfolded foldables. */
    const val twoPaneWidthThresholdDp = 840
    /** Maximum width fraction a user message bubble can occupy (keeps it readable). */
    const val userBubbleWidthFraction = 0.85f
    /** Left pane weight in the two-pane layout (session list). */
    const val twoPaneLeftWeight = 0.38f
    /** Right pane weight in the two-pane layout (chat detail). */
    const val twoPaneRightWeight = 0.62f
}
