package soy.iko.opencode.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.R
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.ui.chat.ChatScreen
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
    val pendingOpenSession by container.pendingOpenSession.collectAsStateWithLifecycle()
    val pendingShare by container.pendingShare.collectAsStateWithLifecycle()
    val activeConnection by container.activeConnection.collectAsStateWithLifecycle()
    // Same VM instance the left pane's SessionListScreen creates (shared ViewModelStoreOwner,
    // keyed by class), so its session list drives the detail pane's stale-selection cleanup.
    val sessionListVm: SessionListViewModel = viewModel(factory = vmFactory { SessionListViewModel(container) })
    val sessionListState by sessionListVm.state.collectAsStateWithLifecycle()

    // A notification tap / deep link requests a session: open it in the detail pane.
    LaunchedEffect(pendingOpenSession) {
        pendingOpenSession?.let {
            selected = it
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

    // Clear the selection only when the open session is *deleted* — i.e. it was present
    // in the list and then dropped out. A freshly created session sets `selected` to the
    // new id before the async refresh lands, so the id is legitimately absent from the
    // (still-stale) list for a moment; a naive "not in list" check would wrongly clear it
    // and blank the detail pane. The `hasAppeared` latch (reset whenever `selected`
    // changes, via remember keyed to it) distinguishes "never appeared yet" (keep) from
    // "appeared, then removed" (a real deletion → clear).
    var hasAppeared by remember(selected) { mutableStateOf(false) }
    LaunchedEffect(selected, sessionListState.sessions) {
        val target = selected ?: return@LaunchedEffect
        if (sessionListState.sessions.any { it.id == target }) {
            hasAppeared = true
        } else if (hasAppeared) {
            selected = null
        }
    }

    // The hasAppeared latch above can't survive process death (plain remember), so a
    // `selected` restored across process death whose session was deleted server-side while
    // dead never "appears" and is kept forever — the detail pane then renders ChatScreen for
    // a nonexistent session (permanent load error). Once the first list load completes
    // successfully without the restored target, clear it. Guarded to run once (non-saveable
    // flag, so it re-arms after process death) and only on a successful, completed load — not
    // during the initial load, and not for a freshly-created session whose refresh keeps
    // `loading` false while its row is still pending.
    var initialSelectionValidated by remember { mutableStateOf(false) }
    LaunchedEffect(sessionListState.loading, sessionListState.error, sessionListState.sessions) {
        if (initialSelectionValidated) return@LaunchedEffect
        if (sessionListState.loading || sessionListState.error != null) return@LaunchedEffect
        initialSelectionValidated = true
        val target = selected ?: return@LaunchedEffect
        if (sessionListState.sessions.none { it.id == target }) selected = null
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
                selectedSessionId = selected,
            )
        }

        Box(modifier = Modifier.weight(NetworkConfig.twoPaneRightWeight).fillMaxSize()) {
            val sessionId = selected
            if (sessionId == null) {
                EmptyDetail()
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

@Composable
private fun EmptyDetail() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Chat,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            stringResource(R.string.empty_detail_pane),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
