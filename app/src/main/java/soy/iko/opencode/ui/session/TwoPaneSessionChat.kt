package soy.iko.opencode.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.R
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.ui.chat.ChatScreen
import soy.iko.opencode.ui.components.EmptyState
import soy.iko.opencode.ui.vmFactory

/**
 * Master–detail layout for wide screens (≥ 840dp, e.g. tablets / unfolded foldables):
 * the session list on the left, the selected conversation on the right. On compact
 * widths the app uses the single-pane [androidx.navigation] back stack instead.
 *
 * Each side hosts an existing screen (which carries its own Scaffold + app bar), so the
 * two panes are self-contained. Selecting a session updates the detail pane rather than
 * pushing a destination; the chat [onBack] clears the selection. The chat composition is
 * keyed by session id so each conversation gets its own ViewModel.
 */
@Composable
fun TwoPaneSessionChat(
    container: AppContainer,
    onOpenFiles: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDisconnect: () -> Unit,
    onAddServer: () -> Unit,
    onOpenSearch: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // Bumped by the empty-detail "New session" button to open the left pane's new-session
    // directory picker (the same dialog the FAB uses) instead of creating a directory-less
    // session; the created session then opens into the detail pane via onOpenSession.
    var newSessionTrigger by remember { mutableStateOf(0) }
    val pendingOpenSession by container.pendingOpenSession.collectAsStateWithLifecycle()
    val pendingShare by container.pendingShare.collectAsStateWithLifecycle()
    val activeConnection by container.activeConnection.collectAsStateWithLifecycle()
    // Same VM instance the left pane's SessionListScreen creates (shared ViewModelStoreOwner,
    // keyed by class), so its session list drives the detail pane's stale-selection cleanup.
    val sessionListVm: SessionListViewModel = viewModel(factory = vmFactory { SessionListViewModel(container) })
    val sessionListState by sessionListVm.state.collectAsStateWithLifecycle()

    // A notification tap / deep link requests a session: open it in the detail pane.
    // The connection switch (if the pending session is from a different server) is handled
    // by the LaunchedEffect in OpencodeApp before we get here, so by the time this runs
    // the active connection already matches.
    LaunchedEffect(pendingOpenSession) {
        pendingOpenSession?.let {
            selected = it.sessionId
            container.consumePendingOpenSession()
        }
    }

    // Clear the selection when the active server profile changes (a server switch via
    // the ServerSwitcherMenu). Without this, `selected` still points at the old
    // server's session id and the detail pane renders a ChatScreen for a session that
    // doesn't exist on the new server, showing a load error with no explanation.
    // Skips the initial transition (null -> a profile id) so a deep link / pending
    // open session that sets `selected` on the same first composition isn't clobbered.
    var lastProfileId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeConnection?.profile?.id) {
        val currentId = activeConnection?.profile?.id
        if (lastProfileId != null && lastProfileId != currentId) selected = null
        lastProfileId = currentId
    }

    StaleSelectionCleanup(
        loading = sessionListState.loading,
        error = sessionListState.error,
        sessions = sessionListState.sessions,
        selected = selected,
        onAdvance = { id -> selected = id },
        onClear = { selected = null },
    )

    // Auto-select the most-recent session into the detail pane so a wide screen doesn't
    // open on a blank right pane. Runs at most once per composition (the `autoSelected`
    // latch, non-saveable so it re-arms after process death), and only once the first list
    // load has completed. It defers to any explicit selection already present — a restored
    // `selected`, a deep-link/notification (pendingOpenSession), or a user pick — and, since
    // the latch is set the moment any of those wins, it never re-selects after a user clears
    // the pane (BackHandler) or a server switch nulls `selected`.
    var autoSelected by remember { mutableStateOf(false) }
    LaunchedEffect(
        sessionListState.loading,
        sessionListState.sessions,
        sessionListState.archivedIds,
        sessionListState.showArchived,
        pendingOpenSession,
        selected,
    ) {
        if (autoSelected || sessionListState.loading) return@LaunchedEffect
        // A pending deep-link or an existing selection takes precedence; yield the pane to it.
        if (pendingOpenSession != null || selected != null) { autoSelected = true; return@LaunchedEffect }
        // Pick by recency regardless of the list's current sort mode, but only among
        // sessions visible under the current archive filter so we never open a hidden row.
        val id = mostRecentSessionId(
            sessionListState.sessions,
            sessionListState.archivedIds,
            sessionListState.showArchived,
        ) ?: return@LaunchedEffect
        selected = id
        autoSelected = true
    }

    // Inject a pending share into the currently selected session's draft (if any).
    // Unlike single-pane mode where the session list is navigated to specifically for
    // the share, in two-pane mode the list is always visible — so we inject into the
    // session the user is already viewing. If no session is selected, the share is
    // deferred until the user picks one (the LaunchedEffect re-fires when `selected`
    // changes to non-null).
    LaunchedEffect(pendingShare, selected) {
        val target = selected
        if (pendingShare != null && target != null) {
            container.consumePendingShare()?.let { container.draftStore.set(target, it) }
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(NetworkConfig.twoPaneLeftWeight)
                .widthIn(max = 460.dp),
        ) {
            SessionListScreen(
                container = container,
                onOpenSession = { id ->
                    selected = id
                },
                onDisconnect = onDisconnect,
                onOpenFiles = onOpenFiles,
                onOpenSettings = onOpenSettings,
                onAddServer = onAddServer,
                onOpenSearch = onOpenSearch,
                externalNewSessionTrigger = newSessionTrigger,
                selectedSessionId = selected,
            )
        }

        // Clear boundary between the list and detail panes (the custom split has no divider
        // of its own).
        VerticalDivider(modifier = Modifier.fillMaxHeight())

        Box(modifier = Modifier.weight(NetworkConfig.twoPaneRightWeight).fillMaxSize()) {
            val sessionId = selected
            if (sessionId == null) {
                // Route through the same new-session directory picker the left-pane FAB
                // opens (rather than creating a directory-less session here); the created
                // session opens into the detail pane via the shared onOpenSession callback.
                EmptyDetail(onNewSession = { newSessionTrigger++ })
            } else {
                BackHandler { selected = null }
                // key() on the session id so switching conversations in two-pane mode
                // gives ChatScreen a fresh composition. Without it, position-anchored
                // state (rememberLazyListState, didInitialScroll, and the LaunchedEffect(Unit)
                // that wires error/retry snackbars) persists across the switch: the new
                // session inherits the old scroll offset, skips its initial scroll-to-bottom,
                // and its error events go unhandled. viewModel(key=sessionId) already swaps
                // the VM; this aligns the composition state with it.
                key(sessionId) {
                    ChatScreen(
                        container = container,
                        sessionId = sessionId,
                        onBack = { selected = null },
                        onOpenFile = onOpenFile,
                        onOpenSession = { id -> selected = id },
                    )
                }
            }
        }
    }
}

/**
 * Id of the most recently updated (or created) session that is *visible* under the current
 * archive filter (archived sessions excluded unless [showArchived]), or null when none
 * qualify. Auto-selecting a hidden/archived session would open the detail pane on a row with
 * no corresponding highlighted entry in the list.
 */
private fun mostRecentSessionId(
    sessions: List<Session>,
    archivedIds: Set<String>,
    showArchived: Boolean,
): String? =
    sessions
        .filter { showArchived || it.id !in archivedIds }
        .maxByOrNull { it.time?.updated ?: it.time?.created ?: 0L }
        ?.id

/** Whether a session with [id] is present in [sessions]. */
private fun containsSession(sessions: List<Session>, id: String): Boolean =
    sessions.any { it.id == id }

/**
 * Keeps the detail pane pointed at a real conversation. When the open session disappears from
 * the list (deleted, or removed server-side), advance to a neighbor instead of blanking the
 * pane — matches the Gmail/Mail master-detail convention where deleting the open conversation
 * shows the next one rather than an empty detail. Only falls back to [onClear] when the list
 * is genuinely empty (nothing to advance to).
 *
 * Two guards:
 *
 *  - Act only when the open session is *removed* — present in the list, then dropped out. A
 *    freshly created session sets `selected` before its async refresh lands, so the id is
 *    legitimately absent for a moment; the `hasAppeared` latch (reset when `selected` changes)
 *    distinguishes "never appeared yet" (keep) from "appeared, then removed" (advance).
 *  - The `hasAppeared` latch can't survive process death, so a `selected` restored across a
 *    kill whose session was deleted server-side while dead never "appears". Once the first list
 *    load completes without the restored target, advance to an existing session (or clear when
 *    none). Runs once (non-saveable flag, re-arms after death) and only on a successful,
 *    completed load — not mid-load, and not for a freshly-created pending row.
 */
@Composable
private fun StaleSelectionCleanup(
    loading: Boolean,
    error: String?,
    sessions: List<Session>,
    selected: String?,
    onAdvance: (String) -> Unit,
    onClear: () -> Unit,
) {
    var hasAppeared by remember(selected) { mutableStateOf(false) }
    // Snapshot of the list as of the last time the target was present, so when the target
    // disappears we can advance to the session that took its row (the next neighbor) rather
    // than jumping to an unrelated row. Tracked here rather than derived, because [sessions]
    // arriving at this call no longer contains the removed id.
    var prevList by remember { mutableStateOf<List<Session>>(emptyList()) }
    LaunchedEffect(selected, sessions) {
        val target = selected ?: return@LaunchedEffect
        if (containsSession(sessions, target)) {
            hasAppeared = true
            prevList = sessions
        } else if (hasAppeared) {
            // Removed mid-session: advance to the neighbor that now occupies the target's
            // slot, falling back to the prior slot, then to any remaining session.
            val prevIndex = prevList.indexOfFirst { it.id == target }.coerceAtLeast(0)
            val replacement = sessions.getOrNull(prevIndex)?.id
                ?: sessions.getOrNull(prevIndex - 1)?.id
                ?: sessions.firstOrNull()?.id
            if (replacement != null && replacement != target) onAdvance(replacement) else onClear()
            prevList = sessions
        }
    }
    var initialSelectionValidated by remember { mutableStateOf(false) }
    LaunchedEffect(loading, error, sessions) {
        if (initialSelectionValidated) return@LaunchedEffect
        if (loading || error != null) return@LaunchedEffect
        initialSelectionValidated = true
        val target = selected ?: return@LaunchedEffect
        if (!containsSession(sessions, target)) {
            // Process-death case: prefer advancing to an existing session over a blank pane.
            val replacement = sessions.firstOrNull()?.id
            if (replacement != null) onAdvance(replacement) else onClear()
        }
    }
}

@Composable
private fun EmptyDetail(onNewSession: () -> Unit) {
    EmptyState(
        icon = Icons.AutoMirrored.Filled.Chat,
        title = stringResource(R.string.empty_detail_title),
        description = stringResource(R.string.empty_detail_pane),
        actionIcon = Icons.Filled.Add,
        actionLabel = stringResource(R.string.new_session),
        onAction = onNewSession,
        modifier = Modifier.fillMaxSize(),
    )
}
