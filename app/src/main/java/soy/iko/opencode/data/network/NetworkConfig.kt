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
    /** Reconnect-attempt count at which the connection banner switches from "Reconnecting…"
     *  to "Reconnecting (attempt N)…" — after a few retries the user benefits from seeing the
     *  system is actively retrying rather than stuck. Below this the plain label is calmer. */
    const val sseReconnectAttemptLabelThreshold = 3
    /** Max gap between events before a silent/half-open SSE connection is dropped and reconnected. */
    const val sseIdleTimeoutMs = 90_000L
    /** Buffer capacity for the SSE events SharedFlow; prevents a slow subscriber stalling the read loop. */
    const val sseEventBufferCapacity = 64

    // --- Connectivity monitor (AppContainer) ---

    /** Grace period after a network is lost before reporting offline. Absorbs the transient
     *  window during a Wi-Fi→cellular handoff where onLost(WiFi) fires before the replacement
     *  network becomes active (cm.activeNetwork is briefly null), so a handoff doesn't flash
     *  offline while a genuine loss (no active network after the grace) is still caught. */
    const val networkOfflineGraceMs = 2_000L

    // --- Catalog cache (OpencodeApiClient) ---

    /** How long cached catalog responses (providers/agents/commands) are considered fresh. */
    const val catalogCacheTtlMs = 60_000L

    /** TTL for files written to the cache dir to hand to external apps via FileProvider
     *  (open-externally). Stale files past this age are pruned on the next open so repeated
     *  opens of large files don't accumulate unbounded. 24h covers a typical session. */
    const val externalCacheTtlMs = 24L * 60 * 60 * 1000

    // --- Session list (SessionListViewModel) ---

    /** Max sessions to fetch previews for in one batch. */
    const val maxPreviewSessions = 50
    /** Initial render window for the session list — the number of rows composed before the
     *  user scrolls to reveal more. Grows by [sessionListPageStep] as the user nears the
     *  bottom, bounding the sort/filter work on a huge list while keeping scrolling seamless. */
    const val sessionListInitialPage = 60
    /** How many additional rows to reveal when the session list render window is exhausted. */
    const val sessionListPageStep = 60
    /** Initial render window for the file-browser directory listing. A huge directory
     *  (10k+ entries) sorts/filters in full on each recomposition; windowing the render
     *  bounds the composed-row count while the sort still runs over the full list (so the
     *  order is correct, just the visible slice is capped). Grows as the user scrolls. */
    const val fileListInitialPage = 100
    /** How many additional file rows to reveal when the render window is exhausted. */
    const val fileListPageStep = 100
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

    /** Max entries in MessageCacheStore's tombstone set. Tombstones only need to survive long
     *  enough to block a racing teardown flush (which resolves within seconds of deletion), so
     *  entries from long-ago deletions are useless. Capping prevents unbounded growth across
     *  many session deletions over the app's lifetime; when exceeded the set is cleared wholesale
     *  (coarse but safe — no racing flush can still be alive for a session deleted sessions ago). */
    const val maxMessageCacheTombstones = 128

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

    // --- File viewer (FileViewScreen) ---

    /** Cap on how many lines the raw viewer renders and searches. A multi-megabyte file is
     *  split once (not twice) into this many lines at most, so it can't hold two full line
     *  lists in memory or stall the LazyColumn; beyond the cap a truncation banner is shown. */
    const val maxRenderedFileLines = 5000

    // --- File browser (FileBrowserViewModel) ---

    /** Debounce delay before firing a file-search request after the user stops typing. */
    const val fileSearchDebounceMs = 250L

    /** Debounce delay before re-running the in-conversation message search after the user
     *  stops typing. The search walks every part of every message (including stringifying
     *  tool state), so debouncing avoids jank on long conversations. The filter itself runs
     *  off the main thread; this delay coalesces rapid keystrokes. */
    const val chatSearchDebounceMs = 200L

    // --- Attachments (chat composer) ---

    /** Max size of a single attachment (image/file) before it's rejected. Base64 inflates
     *  the payload ~33%, and the whole prompt body is held in memory, so cap generously but
     *  finitely to avoid OOM / oversized requests. */
    const val maxAttachmentBytes = 10L * 1024 * 1024

    /** Max number of attachments staged for one prompt, to bound memory and request size. */
    const val maxAttachments = 8

    /** Max cumulative size of all attachments staged for one prompt. The per-file
     *  [maxAttachmentBytes] cap alone allows [maxAttachments] max-size files (~80MB raw,
     *  ~106MB as base64) to pile up in memory and be re-serialized on every add/remove and
     *  again when the request body is built — a realistic OOM. This bounds the whole set. */
    const val maxTotalAttachmentBytes = 25L * 1024 * 1024

    // --- StateFlow sharing (WhileSubscribed) ---

    /** Grace period before a cold StateFlow is stopped after its last subscriber leaves. */
    const val stateFlowSubscriptionTimeoutMs = 5_000L

    /** Maximum time a notification action BroadcastReceiver may hold its goAsync()
     *  PendingResult open. The system ANRs a receiver that doesn't call finish() within
     *  ~10s, so this must stay below that; a slow permission-respond retry (withRetry
     *  exponential backoff up to ~90s) or a blocked outbox enqueue could otherwise blow
     *  past the window. The watchdog finishes the PendingResult at this deadline so the
     *  receiver never ANRs; the underlying network call keeps running on the app scope. */
    const val notificationReceiverTimeoutMs = 8_000L

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

    /** Timeout for a credential test (probe) before giving up, so a hung/unresponsive server
     *  doesn't leave the "Testing…" spinner spinning indefinitely. */
    const val testCredentialsTimeoutMs = 15_000L

    // --- SSE stream (EventStreamClient, AppContainer) ---

    /** Delay before retrying the message-activity observer after a failure. */
    const val observerRetryDelayMs = 5_000L
    /** Debounce before the run foreground-service is started/stopped in response to
     *  run-activity changes. A run that starts and then fails/idles almost immediately
     *  would otherwise dispatch startForegroundService() then stopService() back-to-back;
     *  if the stop wins the race before startForeground() runs, Android raises the
     *  "did not then call startForeground()" crash. Debouncing collapses that window.
     *  Also serves as a minimum-run-duration gate: a run shorter than this (a quick abort,
     *  a one-token reply) clears [anyRunActive] before the FGS ever posts, so the shade
     *  stays clean of non-dismissable notifications for trivially short runs. The Doze-
     *  resilience benefit only matters for long runs, so a few seconds of gate preserves
     *  the core benefit. */
    const val runForegroundDebounceMs = 3_000L

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
    /** Minimum number of options in a picker sheet (model/agent/command) before its search
     *  field auto-focuses and raises the keyboard on open. A tiny catalog is faster to scan by
     *  eye, so popping the keyboard for it is just friction. */
    const val pickerSearchAutofocusThreshold = 8

    /** Minimum number of saved server profiles before the server list shows its search/filter
     *  field. A typical user has one or two servers, where scanning by eye beats typing; power
     *  users juggling many profiles get the filter once it earns its space. */
    const val serverListSearchThreshold = 5

    /** Minimum number of crash reports before the diagnostics list shows its search field. */
    const val diagnosticsSearchThreshold = 8

    /** Initial render window for the diagnostics crash-report list — the number of rows
     *  composed before the user scrolls to reveal more. Bounds composition work on a device
     *  that has accumulated many crash reports. Grows by [diagnosticsListPageStep] as the
     *  user nears the bottom. */
    const val diagnosticsListInitialPage = 60
    /** How many additional crash-report rows to reveal when the render window is exhausted. */
    const val diagnosticsListPageStep = 60

    /** Minimum number of sessions in the usage report before the per-session list shows its
     *  filter field. A short history is faster to scan than type into. */
    const val usageSessionSearchThreshold = 8

    /** Minimum number of sessions before the session list shows its search/filter field.
     *  Matches the sibling screens' convention: a short list is faster to scan by eye than
     *  type into, so the field earns its vertical space only once the list grows. */
    const val sessionListSearchThreshold = 5
    /** Extra bottom content padding (dp) for the chat message list, reserving room for the
     *  jump-to-latest FAB so it never floats over the last message. */
    const val chatListFabInsetDp = 80

    /** Extra bottom content padding (dp) for single-pane list screens that host a FAB
     *  (session list, server list, MCP), reserving room so the FAB never floats over the
     *  last row. Unified across screens so the inset doesn't drift (the session list
     *  previously hardcoded 96dp while the chat list used [chatListFabInsetDp]). */
    const val listFabInsetDp = 96
    /** Left pane weight in the two-pane layout (session list). */
    const val twoPaneLeftWeight = 0.38f
    /** Right pane weight in the two-pane layout (chat detail). */
    const val twoPaneRightWeight = 0.62f

    /** Maximum width (dp) for the left (session-list) pane in the two-pane layout. On a wide
     *  tablet the list pane would otherwise stretch too wide for a readable row density; capping
     *  it keeps the list compact and gives the chat detail pane the remaining space. */
    const val twoPaneLeftMaxWidthDp = 460

    /** Minimum window width (dp) at which a NavigationRail is shown alongside the
     *  two-pane layout for top-level destination discoverability on large screens. Sits below
     *  [twoPaneWidthThresholdDp] (M3's WindowWidthSizeClass.Medium breakpoint ~600dp) so a
     *  tablet or landscape phone gets the rail while staying single-pane until 840dp, where the
     *  two-pane master/detail split takes over. */
    const val navigationRailThresholdDp = 600

    /** Maximum width (dp) for the chat message list on large screens. On a tablet or
     *  unfolded foldable, full-width message bubbles stretch edge-to-edge, hurting
     *  readability — capping and centering the list (like Gmail/Telegram on tablets)
     *  keeps line lengths comfortable. Applied only above [twoPaneWidthThresholdDp]. */
    const val chatContentMaxWidthDp = 800

    /** Maximum width (dp) for a single-pane list/form screen on large screens (Diagnostics,
     *  MCP, Usage, Global Search, File Browser). Mirrors [chatContentMaxWidthDp]'s rationale
     *  at a tighter width suited to dense list rows and form fields. Settings and ServerEdit
     *  use 600dp directly; this constant centralizes the same value for the screens that
     *  previously stretched edge-to-edge on tablets. */
    const val listContentMaxWidthDp = 600

    /** Maximum width (dp) for the full-screen chat composer dialog on large screens, so
     *  the editor doesn't stretch edge-to-edge on a tablet (matching [chatContentMaxWidthDp]'s
     *  readability rationale for the message list). */
    const val composerDialogMaxWidthDp = 800

    /** Maximum height (dp) for a modal bottom sheet's list area. Resolved against the screen
     *  (not the partial sheet) so a sheet that starts fully expanded shows a predictable list
     *  height regardless of the sheet's drag position. */
    const val pickerSheetMaxHeightDp = 560

    /** Maximum inline height (dp) for an image attachment in the chat list. A tall image
     *  caps at this so it doesn't dominate the bubble; tapping opens the fullscreen viewer. */
    const val inlineImageMaxHeightDp = 320

    /** Minimum inline height (dp) reserved for an image placeholder (loading/error/resolving),
     *  so the layout doesn't jump when the load completes and the image's intrinsic size is known. */
    const val inlineImageMinHeightDp = 120

    /** Lines of a code fence rendered before collapsing to a head with a "show more" affordance.
     *  Capped low for mobile: a 200-line block rendered at once can jank a low-end phone, so the
     *  initial render is bounded and a "show more" reveals the rest in place. */
    const val collapsedCodeLineThreshold = 80

    /** Lines of a unified diff rendered before collapsing to a head with a "show more" affordance.
     *  Same mobile-performance rationale as [collapsedCodeLineThreshold]. */
    const val collapsedDiffLineThreshold = 80

    /** Lines of tool output rendered before collapsing to a head with a "show more" affordance. */
    const val toolOutputCollapsedLimitChars = 4000

    /** Lines of tool output rendered before collapsing to a head with a "show more" affordance.
     *  A line-based threshold (rather than the char-based [toolOutputCollapsedLimitChars]) keeps
     *  the collapsed preview a predictable height on mobile — a 4000-char block of short lines
     *  can be 60+ lines and fill the whole screen, defeating the purpose of collapsing. The
     *  char cap still applies as a hard upper bound; this line cap kicks in first for typical
     *  multi-line output. */
    const val toolOutputCollapsedLimitLines = 30

    /** Tool input (pretty-printed JSON) shorter than this many lines is shown expanded by
     *  default — for tools like bash/write/edit the command is the most important info and
     *  hiding it behind a tap inverts the priority. Longer inputs stay collapsed to avoid
     *  dominating the bubble. */
    const val toolInputAutoExpandLineThreshold = 10

    /** Tool durations below this many ms are hidden from the tool-name row (sub-second runs
     *  are instant and the ms figure is noise). Set to 1000 to hide all sub-second durations,
     *  or 0 to always show. */
    const val toolDurationHideBelowMs = 1_000L

    /** Characters of tool output included in a shared/exported transcript before truncation. */
    const val exportToolOutputLimitChars = 2000

    /** Maximum zoom factor applied by pinch or double-tap in the fullscreen image viewer. */
    const val imageViewerMaxZoom = 5f

    /** Zoom factor a double-tap jumps to when starting from 1× in the fullscreen image viewer. */
    const val imageViewerDoubleTapZoom = 2.5f

    /** Duration (ms) of the animated double-tap zoom transition in the fullscreen image viewer. */
    const val imageViewerZoomAnimMs = 220

    /** Vertical drag distance (px) in the fullscreen image viewer that triggers swipe-to-dismiss. */
    const val imageViewerSwipeDismissThreshold = 200f

    /** Blink period (ms) of the streaming caret at the tail of a live assistant reply. */
    const val streamingCaretPeriodMs = 500

    /** Target maximum length (chars) of a single TTS chunk. Android's TextToSpeech enforces a
     *  hard limit (getMaxSpeechInputLength, ~4000 chars) — this is a tighter cap so pause/
     *  resume restarts at a finer granularity. Without it, a pause mid-chunk restarts the
     *  whole chunk from its start; smaller chunks mean less replay on resume. Sentence
     *  boundaries are still preferred, so short sentences pack up to this cap. */
    const val ttsChunkTargetMaxChars = 200

    /** Auto-reject a tool-permission prompt after this many ms if the user hasn't responded,
     *  so a forgotten prompt doesn't block the run indefinitely. The foreground service keeps
     *  the process alive, so without a timeout a prompt that the user walked away from holds
     *  the run open forever. The timeout is long (10 minutes) so a deliberate pause to read
     *  the prompt doesn't trip it; a reminder toast-style line appears in the dialog after
     *  [permissionReminderThresholdMs] to surface that the run is waiting. Set to 0 to disable. */
    const val permissionAutoRejectMs = 10L * 60 * 1000

    /** Show a "still waiting" reminder in the permission dialog after this many ms. */
    const val permissionReminderThresholdMs = 60L * 1000

    /** Duration (ms) the copy button shows its checkmark confirmation before reverting. */
    const val copyFeedbackMs = 1200

    /** Gutter width (dp) for each side (old/new) of a unified-diff line-number column. */
    const val diffGutterWidthDp = 36

    /** Width (dp) of the +/- prefix column in a unified-diff row. */
    const val diffPrefixWidthDp = 16

    /** Maximum width (dp) of a breadcrumb segment in the file browser (non-last segments). */
    const val breadcrumbSegmentMaxWidthDp = 120

    /** Maximum width (dp) of the last (current) breadcrumb segment in the file browser. */
    const val breadcrumbLastSegmentMaxWidthDp = 220

    /** Vertical scroll threshold beyond which the chat "scroll to top" FAB appears. */
    const val chatScrollToTopFabThreshold = 200

    /** Maximum width (dp) for an attachment chip's filename text before it truncates. */
    const val attachmentChipNameMaxWidthDp = 120

    /** Maximum height (dp) for the expanded todo-plan checklist in a message bubble. */
    const val todoPlanMaxHeightDp = 240

    /** Fallback height (dp) for the connection banner when no text is available (e.g. exit fade). */
    const val connectionBannerFallbackHeightDp = 44

    /** Fixed height (dp) for the connection banner used for LazyColumn top content padding.
     *  onSizeChanged was previously used to measure the banner's actual height and feed it
     *  into the LazyColumn's contentPadding, but that created a feedback loop: the banner
     *  appears → onSizeChanged fires → contentPadding changes → LazyColumn remeasures all
     *  visible items during the same frame async content (DiffView, images) is settling
     *  post-mount → draw-phase remeasure NPE. A fixed height eliminates the loop. At large
     *  accessibility font scales the banner may slightly exceed this, but the 4dp margin
     *  above it in topPad covers the common case. */
    const val connectionBannerHeightDp = 44

    /** Fixed height (dp) for the in-conversation search bar used for LazyColumn top
     *  content padding. Same rationale as connectionBannerHeightDp: onSizeChanged on the
     *  search bar fed contentPadding and triggered the draw-phase remeasure crash. */
    const val chatSearchBarHeightDp = 56

    // --- Motion tokens (shared across screens) ---

    /** Duration (ms) for horizontal slide push/pop nav transitions. M3 spec recommends 200ms
     *  for medium-duration container transitions. */
    const val motionSlideDurationMs = 200
    /** Duration (ms) for fade in/out nav and content transitions. */
    const val motionFadeDurationMs = 180

    // --- Font scaling ---

    /** Upper bound on the *combined* app×OS font scale. The chat text-size preference
     *  multiplies on top of the system font scale, so a user at a large system size and
     *  a large app size can reach ~2× and break tight layouts. The provided app scale is
     *  clamped so the product with the OS fontScale never exceeds this. */
    const val maxCombinedFontScale = 1.8f
}
