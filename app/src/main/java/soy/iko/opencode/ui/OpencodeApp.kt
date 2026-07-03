package soy.iko.opencode.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.ui.chat.ChatScreen
import soy.iko.opencode.ui.file.FileBrowserScreen
import soy.iko.opencode.ui.file.FileViewScreen
import soy.iko.opencode.ui.server.ServerEditScreen
import soy.iko.opencode.ui.server.ServerListScreen
import soy.iko.opencode.ui.search.GlobalSearchScreen
import soy.iko.opencode.ui.components.LocalChatTextScale
import soy.iko.opencode.ui.components.LocalCodeWrap
import soy.iko.opencode.ui.session.SessionListScreen
import soy.iko.opencode.ui.session.TwoPaneSessionChat
import soy.iko.opencode.ui.settings.DiagnosticsScreen
import soy.iko.opencode.ui.settings.SettingsScreen
import soy.iko.opencode.ui.usage.UsageScreen
import soy.iko.opencode.ui.mcp.McpScreen
import soy.iko.opencode.util.runCatchingCancellable

@Composable
fun OpencodeApp(container: AppContainer) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val pendingShare by container.pendingShare.collectAsStateWithLifecycle()
    val pendingSharedMedia by container.pendingSharedMedia.collectAsStateWithLifecycle()
    val pendingOpenSession by container.pendingOpenSession.collectAsStateWithLifecycle()
    val pendingNewSession by container.pendingNewSession.collectAsStateWithLifecycle()
    val connection by container.activeConnection.collectAsStateWithLifecycle()
    // Chat presentation preferences, provided to the whole nav graph so the markdown/code
    // renderers honor the user's text-size and code-wrap choices everywhere.
    val chatTextScale by container.settingsStore.chatTextScale.collectAsStateWithLifecycle(initialValue = 1f)
    val codeWrap by container.settingsStore.codeWrap.collectAsStateWithLifecycle(initialValue = false)

    // The foreground service that holds priority while any run is active is driven from the
    // process-lived AppContainer scope (see AppContainer.observeRunForegroundService), not
    // from here — a composition-scoped collector pauses while backgrounded, which is exactly
    // when a run started via the notification reply needs the service to start.

    // When text is shared into the app, surface the session list so the user can pick
    // (or create) a conversation to drop it into. The chosen session's draft is set
    // in [onOpenSession] below. Keyed on `connection` too so a share that arrives
    // before auto-reconnect completes is retried once the connection is established.
    // Track the last-handled share payloads (there's no share nonce on the container) so a
    // *new* share arriving while already connected — a warm start where onNewIntent re-fires
    // setPendingShare under an unchanged connection id — still navigates, while a re-trigger
    // carrying no new share (a bare server switch, which routes through a null connection, or
    // a share the user already navigated for) leaves them where they are.
    //
    // Reset the remembered media when the pending list is drained (the chosen ChatScreen
    // consumes it via consumePendingSharedMedia). Without this, re-sharing the *same* images
    // after consumption compares equal structurally ([A,B] == [A,B]) and would be treated as
    // "already handled", silently skipping the session-list navigation the second time.
    //
    // rememberSaveable (not remember) so a process-kill restore doesn't reset the dedup
    // state and re-navigate to SESSIONS for a share the user already handled before the kill.
    var handledShareText by rememberSaveable { mutableStateOf<String?>(null) }
    var handledSharedMedia by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(pendingShare, pendingSharedMedia, connection) {
        // Reset the remembered handled state for each channel independently as it drains,
        // so re-sharing the same payload after consumption is still recognized as new. A
        // joint reset (only when both are empty) would drop a text-only re-share that
        // follows a text+media share of the same text: the text channel matches its
        // remembered value while the media channel is empty (not "new"), so neither clause
        // fires and the share is silently swallowed.
        if (pendingShare == null) handledShareText = null
        if (pendingSharedMedia.isEmpty()) handledSharedMedia = emptyList()
        if (pendingShare == null && pendingSharedMedia.isEmpty()) return@LaunchedEffect
        if (connection == null) return@LaunchedEffect
        val newShare = (pendingShare != null && pendingShare != handledShareText) ||
            (pendingSharedMedia.isNotEmpty() && pendingSharedMedia != handledSharedMedia)
        if (!newShare) return@LaunchedEffect
        handledShareText = pendingShare
        handledSharedMedia = pendingSharedMedia
        if (!navController.popBackStack(Routes.SESSIONS, inclusive = false)) {
            navController.navigate(Routes.SESSIONS) { launchSingleTop = true }
        }
    }

    // "New session" from a launcher shortcut / QS tile: once connected, create a fresh
    // session and open it. The pending counter is consumed only *after* createSession
    // succeeds, so a transient failure leaves the request pending to retry (on the next
    // connection change or a re-tap that increments the counter) instead of silently
    // dropping the tap.
    LaunchedEffect(pendingNewSession, connection) {
        if (pendingNewSession == 0) return@LaunchedEffect
        val conn = connection ?: return@LaunchedEffect
        val session = runCatchingCancellable { conn.repository.createSession() }.getOrNull()
            ?: return@LaunchedEffect
        if (!container.consumePendingNewSession()) return@LaunchedEffect
        if (!navController.popBackStack(Routes.SESSIONS, inclusive = false)) {
            navController.navigate(Routes.SESSIONS) { launchSingleTop = true }
        }
        navController.navigate(Routes.chat(session.id)) { launchSingleTop = true }
    }

    // The chat text-size preference multiplies on top of the OS font scale (sp already
    // honors the system setting), so a large system size × large app size can reach ~2×
    // and break tight layouts. Clamp the provided app scale so the product with the OS
    // fontScale never exceeds NetworkConfig.maxCombinedFontScale, while never scaling
    // below the user's chosen value's intent (floor of 1× app scale is preserved when the
    // system size alone already exceeds the cap).
    val systemFontScale = LocalDensity.current.fontScale
    val clampedChatTextScale = if (systemFontScale <= 0f) {
        chatTextScale
    } else {
        chatTextScale.coerceAtMost(
            (NetworkConfig.maxCombinedFontScale / systemFontScale).coerceAtLeast(1f)
        )
    }

    CompositionLocalProvider(
        LocalChatTextScale provides clampedChatTextScale,
        LocalCodeWrap provides codeWrap,
    ) {
    // Adaptive: on wide screens (tablets / unfolded foldables) show the session list and
    // the open conversation side by side instead of a single-pane back stack.
    BoxWithConstraints {
        val isTwoPane = maxWidth >= NetworkConfig.twoPaneWidthThresholdDp.dp && connection != null

        // Open a session requested by a notification tap or deep link, once connected.
        LaunchedEffect(pendingOpenSession, connection, isTwoPane) {
            val pending = pendingOpenSession ?: return@LaunchedEffect
            if (connection == null) return@LaunchedEffect
            // If the notification body-tap carries a profile id from a different server,
            // switch to it first so the session opens under the server that ran it, not
            // whichever is active. The effect re-runs when connection changes (keyed on it),
            // so on the second run the profile matches and the open proceeds.
            if (pending.profileId != null && connection?.profile?.id != pending.profileId) {
                if (!container.connectByProfileId(pending.profileId)) return@LaunchedEffect
                return@LaunchedEffect // re-run on the new connection
            }
            // In two-pane mode the detail pane (TwoPaneSessionChat, hosted on the SESSIONS
            // route) consumes the request and opens it in the detail pane. But it only
            // exists while the NavHost is on SESSIONS, so if the user is currently on
            // Files/Settings/FileView bring SESSIONS back to front first — otherwise the
            // pending request is never consumed and the session never opens. Don't consume
            // here; let TwoPaneSessionChat's effect do it once it's composed.
            if (isTwoPane) {
                if (!navController.popBackStack(Routes.SESSIONS, inclusive = false)) {
                    navController.navigate(Routes.SESSIONS) { launchSingleTop = true }
                }
                return@LaunchedEffect
            }
            container.consumePendingOpenSession()
            // Ensure SESSIONS sits on the back stack below the chat destination so backing
            // out of the chat lands on the session list (not the server list). Bring SESSIONS
            // to front (or push it if a cold-start deep link left only SERVERS on the stack),
            // then push chat on top — yielding SERVERS -> SESSIONS -> chat.
            if (!navController.popBackStack(Routes.SESSIONS, inclusive = false)) {
                navController.navigate(Routes.SESSIONS) { launchSingleTop = true }
            }
            navController.navigate(Routes.chat(pending.sessionId)) { launchSingleTop = true }
        }

        NavHost(
            navController = navController,
            startDestination = Routes.SERVERS,
            // Slide horizontally for forward/back navigation to feel native; fade for
            // the root so the first frame doesn't slide in from off-screen.
            enterTransition = {
                slideInHorizontally(animationSpec = tween(NetworkConfig.motionSlideDurationMs)) { it } +
                    fadeIn(tween(NetworkConfig.motionFadeDurationMs))
            },
            exitTransition = { fadeOut(tween(NetworkConfig.motionFadeDurationMs)) },
            popEnterTransition = { fadeIn(tween(NetworkConfig.motionFadeDurationMs)) },
            popExitTransition = {
                slideOutHorizontally(animationSpec = tween(NetworkConfig.motionSlideDurationMs)) { it } +
                    fadeOut(tween(NetworkConfig.motionFadeDurationMs))
            },
        ) {

        composable(Routes.SERVERS) {
            ServerListScreen(
                container = container,
                onConnected = {
                    // Only leave the server list if we're still on it. A pending deep-link /
                    // notification open sets activeConnection (which drives the open-session
                    // effect: SERVERS -> SESSIONS -> chat) *before* autoConnectDone fires this
                    // callback, so a blind navigate(SESSIONS) here would slam a second SESSIONS
                    // on top of the chat it just opened, burying it. Order-independent: if the
                    // deep link already navigated away from SERVERS, this is a no-op.
                    if (navController.currentDestination?.route == Routes.SERVERS) {
                        navController.navigate(Routes.SESSIONS) { launchSingleTop = true }
                    }
                },
                onAddProfile = { navController.navigate(Routes.serverEdit()) },
                onEditProfile = { id -> navController.navigate(Routes.serverEdit(id)) },
                onDuplicateProfile = { id -> navController.navigate(Routes.serverEditDuplicate(id)) },
            )
        }

        composable(
            route = "${Routes.SERVER_EDIT}?id={id}&dup={dup}",
            arguments = listOf(
                navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("dup") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            ServerEditScreen(
                container = container,
                profileId = entry.arguments?.getString("id"),
                sourceId = entry.arguments?.getString("dup"),
                onDone = { navController.popBackStack() },
            )
        }

        composable(Routes.SESSIONS) {
            if (isTwoPane) {
                TwoPaneSessionChat(
                    container = container,
                    onOpenFiles = { navController.navigate(Routes.FILES) },
                    onOpenFile = { path -> navController.navigate(Routes.fileView(path)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenSearch = { navController.navigate(Routes.SEARCH) },
                    onDisconnect = {
                        scope.launch { runCatchingCancellable { container.disconnect() } }
                        navController.popBackStack(Routes.SERVERS, inclusive = false)
                    },
                    onAddServer = { navController.navigate(Routes.serverEdit()) },
                )
            } else {
                SessionListScreen(
                    container = container,
                    onOpenSession = { id ->
                        // Set the draft synchronously in-memory before navigating so the
                        // ChatScreen sees it on first composition. The async persistence
                        // to disk happens in the background via draftStore.set.
                        container.consumePendingShare()?.let { shareText ->
                            container.draftStore.setImmediate(id, shareText)
                            scope.launch { runCatchingCancellable { container.draftStore.set(id, shareText) } }
                        }
                        navController.navigate(Routes.chat(id))
                    },
                    onDisconnect = {
                        scope.launch { runCatchingCancellable { container.disconnect() } }
                        navController.popBackStack(Routes.SERVERS, inclusive = false)
                    },
                    onOpenFiles = { navController.navigate(Routes.FILES) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenSearch = { navController.navigate(Routes.SEARCH) },
                    onAddServer = { navController.navigate(Routes.serverEdit()) },
                )
            }
        }

        composable(
            route = "${Routes.CHAT}/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            ChatScreen(
                container = container,
                sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenFile = { path -> navController.navigate(Routes.fileView(path)) },
                onOpenSession = { id -> navController.navigate(Routes.chat(id)) },
            )
        }

        composable(Routes.FILES) {
            FileBrowserScreen(
                container = container,
                onOpenFile = { path, line -> navController.navigate(Routes.fileView(path, line)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = "${Routes.FILE_VIEW}?path={path}&line={line}",
            arguments = listOf(
                navArgument("path") { type = NavType.StringType; defaultValue = "" },
                navArgument("line") { type = NavType.IntType; defaultValue = -1 },
            ),
        ) { entry ->
            val line = entry.arguments?.getInt("line") ?: -1
            FileViewScreen(
                container = container,
                path = entry.arguments?.getString("path").orEmpty(),
                initialLine = line.takeIf { it > 0 },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                onBack = { navController.popBackStack() },
                // Navigate to the server list as a new entry on top of Sessions so the
                // user can back out to their conversations. Previously this popped back
                // to the root SERVERS destination, which removed Sessions from the stack
                // and left the user unable to return to their conversations without
                // reconnecting. Pop only Settings, then push SERVERS; back from the
                // server list lands on Sessions (the screen the user was on before
                // Settings), and connecting pushes a fresh SESSIONS as usual.
                onManageServers = {
                    navController.navigate(Routes.SERVERS) {
                        popUpTo(Routes.SETTINGS) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onOpenUsage = { navController.navigate(Routes.USAGE) },
                onOpenMcp = { navController.navigate(Routes.MCP) },
            )
        }

        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.USAGE) {
            UsageScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onOpenSession = { id -> navController.navigate(Routes.chat(id)) },
            )
        }

        composable(Routes.MCP) {
            McpScreen(container = container, onBack = { navController.popBackStack() })
        }

        composable(Routes.SEARCH) {
            GlobalSearchScreen(
                container = container,
                onOpenSession = { id -> navController.navigate(Routes.chat(id)) },
                onBack = { navController.popBackStack() },
            )
        }
        }
    }
    }
}
