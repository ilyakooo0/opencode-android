package soy.iko.opencode.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.navigation.NavType
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.ui.chat.ChatScreen
import soy.iko.opencode.ui.file.FileBrowserScreen
import soy.iko.opencode.ui.file.FileViewScreen
import soy.iko.opencode.ui.file.TwoPaneFileBrowser
import soy.iko.opencode.ui.server.ServerEditScreen
import soy.iko.opencode.ui.server.ServerListScreen
import soy.iko.opencode.ui.server.ServerSettingsScreen
import soy.iko.opencode.ui.search.GlobalSearchScreen
import androidx.compose.ui.platform.LocalHapticFeedback
import soy.iko.opencode.ui.components.LargeScreenNavRail
import soy.iko.opencode.ui.components.CompactNavBar
import soy.iko.opencode.ui.components.LocalChatTextScale
import soy.iko.opencode.ui.components.LocalCodeWrap
import soy.iko.opencode.ui.components.LocalReducedMotion
import soy.iko.opencode.ui.components.isReducedMotion
import soy.iko.opencode.ui.components.rememberGatedHaptics
import soy.iko.opencode.ui.session.SessionListScreen
import soy.iko.opencode.ui.session.TwoPaneSessionChat
import soy.iko.opencode.ui.settings.DiagnosticsScreen
import soy.iko.opencode.ui.settings.SettingsScreen
import soy.iko.opencode.ui.usage.UsageScreen
import soy.iko.opencode.ui.mcp.McpScreen
import soy.iko.opencode.util.findActivity
import soy.iko.opencode.util.runCatchingCancellable

@Composable
fun OpencodeApp(container: AppContainer) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val pendingShare by container.pendingShare.collectAsStateWithLifecycle()
    val pendingSharedMedia by container.pendingSharedMedia.collectAsStateWithLifecycle()
    val pendingOpenSession by container.pendingOpenSession.collectAsStateWithLifecycle()
    val pendingNewSession by container.pendingNewSession.collectAsStateWithLifecycle()
    val pendingOpenFile by container.pendingOpenFile.collectAsStateWithLifecycle()
    val pendingDiagnostics by container.pendingDiagnostics.collectAsStateWithLifecycle()
    val connection by container.activeConnection.collectAsStateWithLifecycle()
    // Chat presentation preferences, provided to the whole nav graph so the markdown/code
    // renderers honor the user's text-size and code-wrap choices everywhere.
    val chatTextScale by container.settingsStore.chatTextScale.collectAsStateWithLifecycle(initialValue = 1f)
    val codeWrap by container.settingsStore.codeWrap.collectAsStateWithLifecycle(initialValue = false)
    // Haptics gating: collected once at the root so the in-app toggle can disable all
    // performHapticFeedback calls everywhere via a single CompositionLocalProvider, instead
    // of each call site needing to read the setting. Defaults true (the prior behavior) so
    // the brief window before DataStore loads still haptics as before.
    val hapticsEnabled by container.settingsStore.hapticsEnabled.collectAsStateWithLifecycle(initialValue = true)

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

    // Crash-relaunch prompt: navigate to the Diagnostics screen when the user taps
    // "View report" on the cold-start crash dialog. Doesn't require a connection.
    LaunchedEffect(pendingDiagnostics) {
        if (!pendingDiagnostics) return@LaunchedEffect
        container.consumePendingDiagnostics()
        navController.navigate(Routes.DIAGNOSTICS) { launchSingleTop = true }
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

    // Capture the reduced-motion preference once (in the composable scope) so the
    // non-composable NavHost transition lambdas can use it to skip animations, and provide
    // it via LocalReducedMotion so in-composition animations (chat row placement, banners,
    // image zoom) honor the same accessibility preference.
    val reducedMotion = isReducedMotion()
    CompositionLocalProvider(
        LocalChatTextScale provides clampedChatTextScale,
        LocalCodeWrap provides codeWrap,
        LocalReducedMotion provides reducedMotion,
        LocalHapticFeedback provides rememberGatedHaptics(hapticsEnabled),
    ) {
    // Slide direction for push/pop transitions, mirrored in RTL so the animation reads
    // naturally instead of "going the wrong way". +1 = LTR (push from right), -1 = RTL.
    val slideDir = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    // Adaptive: on wide screens (tablets / unfolded foldables) show the session list and
    // the open conversation side by side instead of a single-pane back stack.
    // Posture-aware: a foldable in HALF_OPENED (tabletop/book) mode has a hinge across the
    // content area — splitting there would place the list/detail boundary on the hinge, which
    // is hard to read and interact with. Detect a FoldingFeature and suppress two-pane in
    // those postures so the app falls back to a single-pane layout that respects the fold.
    val windowContext = LocalContext.current
    var foldState by remember { mutableStateOf<androidx.window.layout.FoldingFeature?>(null) }
    androidx.compose.runtime.LaunchedEffect(windowContext) {
        val activity = windowContext.findActivity()
        if (activity != null) {
            androidx.window.layout.WindowInfoTracker.getOrCreate(windowContext)
                .windowLayoutInfo(activity)
                .collect { info ->
                    foldState = info.displayFeatures
                        .filterIsInstance<androidx.window.layout.FoldingFeature>()
                        .firstOrNull()
                }
        }
    }
    val isHalfOpened = foldState?.state == androidx.window.layout.FoldingFeature.State.HALF_OPENED
    BoxWithConstraints(
        modifier = Modifier.onPreviewKeyEvent { ev ->
            // Global keyboard shortcuts for hardware-keyboard users (tablets, DeX, Chromebook):
            // Ctrl+1..6 switches between top-level destinations. Only fires on KeyDown so a
            // held Ctrl doesn't re-trigger on every repeat. Per-screen shortcuts (Ctrl+K palette,
            // Esc stop, Ctrl+F find) are handled in each screen's own onPreviewKeyEvent and take
            // precedence (they're closer to the focused composable in the tree).
            if (ev.type != KeyEventType.KeyDown || !ev.isCtrlPressed) {
                // Alt+Left (a familiar desktop "Back" gesture) pops the back stack. Not gated
                // on Ctrl so it works as a standalone modifier. Per-screen shortcuts (Esc
                // stop, Ctrl+F find, Ctrl+K palette) register their own onPreviewKeyEvent
                // closer to the focused composable and consume first, so this only pops when
                // nothing nearer handled the key — avoiding conflicts with open dialogs and
                // text fields. Esc is deliberately NOT mapped to back here for that reason.
                if (ev.type == KeyEventType.KeyDown &&
                    ev.key == Key.DirectionLeft &&
                    ev.isAltPressed
                ) {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                        return@onPreviewKeyEvent true
                    }
                }
                return@onPreviewKeyEvent false
            }
            val route = when (ev.key) {
                Key.NumPad1, Key.One -> Routes.SESSIONS
                Key.NumPad2, Key.Two -> Routes.SEARCH
                Key.NumPad3, Key.Three -> Routes.FILES
                Key.NumPad4, Key.Four -> Routes.USAGE
                Key.NumPad5, Key.Five -> Routes.MCP
                Key.NumPad6, Key.Six -> Routes.SETTINGS
                // Ctrl+7 navigates to the server list (the 7th nav-rail destination).
                // Previously Ctrl+1..6 covered the other destinations but Servers was
                // missing, an inconsistency since the nav rail shows 7 destinations.
                Key.NumPad7, Key.Seven -> Routes.SERVERS
                else -> null
            }
            if (route != null) {
                // Servers is reachable even without an active connection (it's the start
                // destination and handles the not-connected state); the other routes
                // require a connection to be meaningful.
                if (route == Routes.SERVERS || connection != null) {
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                    true
                } else false
            } else false
        },
    ) {
        val isTwoPane = maxWidth >= NetworkConfig.twoPaneWidthThresholdDp.dp &&
            connection != null &&
            !isHalfOpened
        // On large screens, surface a navigation rail alongside the content so top-level
        // destinations (Sessions, Files, Search, …) are one tap away instead of buried in a
        // screen's overflow menu. Mirrors the standard large-screen M3 layout.
        val showNavRail = maxWidth >= NetworkConfig.navigationRailThresholdDp.dp
        // Observe the current destination reactively so the rail's selected item tracks navigation
        // (reading currentDestination once would go stale after the first composition).
        val currentEntry by navController.currentBackStackEntryAsState()
        val currentRoute = currentEntry?.destination?.route

        // Open a session requested by a notification tap or deep link, once connected.
        LaunchedEffect(pendingOpenSession, connection, isTwoPane) {
            val pending = pendingOpenSession ?: return@LaunchedEffect
            if (connection == null) return@LaunchedEffect
            // If the notification body-tap carries a profile id from a different server,
            // switch to it first so the session opens under the server that ran it, not
            // whichever is active. The effect re-runs when connection changes (keyed on it),
            // so on the second run the profile matches and the open proceeds.
            if (pending.profileId != null && connection?.profile?.id != pending.profileId) {
                // If the originating profile no longer exists (e.g. deleted after the
                // notification was queued), connectByProfileId returns false: the active
                // connection is unchanged and none of this effect's keys change, so it
                // would never re-run — the request would sit in _pendingOpenSession
                // forever and re-trigger on every later connection change. Consume it so
                // the stale request is dropped, mirroring the invalid-deep-link path in
                // MainActivity.
                if (!container.connectByProfileId(pending.profileId)) {
                    container.consumePendingOpenSession()
                    return@LaunchedEffect
                }
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

        // Deep-linked file open (opencode://file/{path}): navigate to the file viewer once a
        // connection exists (the viewer fetches content from the server). Path comes pre-validated
        // by MainActivity's deep-link guard. In two-pane mode the detail pane (TwoPaneFileBrowser,
        // hosted on the FILES route) consumes the request into its detail pane, mirroring how
        // session deep links are routed — so don't consume or push the full-screen viewer here.
        LaunchedEffect(pendingOpenFile, connection, isTwoPane) {
            val path = pendingOpenFile ?: return@LaunchedEffect
            if (connection == null) return@LaunchedEffect
            if (isTwoPane) {
                if (!navController.popBackStack(Routes.FILES, inclusive = false)) {
                    navController.navigate(Routes.FILES) { launchSingleTop = true }
                }
                return@LaunchedEffect
            }
            container.consumePendingOpenFile()
            if (!navController.popBackStack(Routes.FILES, inclusive = false)) {
                navController.navigate(Routes.FILES) { launchSingleTop = true }
            }
            navController.navigate(Routes.fileView(path)) { launchSingleTop = true }
        }

        val navHost = @Composable {
        NavHost(
            modifier = Modifier.fillMaxSize(),
            navController = navController,
            startDestination = Routes.SERVERS,
            // Motion-aware transitions: respect the user's reduced-motion setting
            // (Developer Options → animator scale 0) by skipping animations entirely.
            // Per-destination tuning: a chat→chat navigation (switching peer conversations
            // within the same surface) crossfades instead of sliding, so it reads as a peer
            // switch rather than drilling into a new section. Top-level destinations and
            // detail pushes keep the horizontal slide.
            enterTransition = {
                if (reducedMotion) {
                    EnterTransition.None
                } else if (targetState.destination.route?.startsWith("${Routes.CHAT}/") == true) {
                    fadeIn(tween(NetworkConfig.motionFadeDurationMs))
                } else {
                    slideInHorizontally(animationSpec = tween(NetworkConfig.motionSlideDurationMs)) { it * slideDir } +
                        fadeIn(tween(NetworkConfig.motionFadeDurationMs))
                }
            },
            exitTransition = {
                if (reducedMotion) {
                    ExitTransition.None
                } else {
                    fadeOut(tween(NetworkConfig.motionFadeDurationMs))
                }
            },
            popEnterTransition = {
                if (reducedMotion) {
                    EnterTransition.None
                } else {
                    fadeIn(tween(NetworkConfig.motionFadeDurationMs))
                }
            },
            popExitTransition = {
                if (reducedMotion) {
                    ExitTransition.None
                } else {
                    slideOutHorizontally(animationSpec = tween(NetworkConfig.motionSlideDurationMs)) { it * slideDir } +
                        fadeOut(tween(NetworkConfig.motionFadeDurationMs))
                }
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
                onAddProfile = { navController.navigate(Routes.SERVER_EDIT) },
                onServerSettings = { id -> navController.navigate(Routes.serverSettings(id)) },
            )
        }

        composable(Routes.SERVER_EDIT) {
            ServerEditScreen(
                container = container,
                onDone = { navController.popBackStack() },
            )
        }

        composable(
            route = "${Routes.SERVER_SETTINGS}/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            ServerSettingsScreen(
                container = container,
                profileId = entry.arguments?.getString("id").orEmpty(),
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
                    onAddServer = { navController.navigate(Routes.SERVER_EDIT) },
                    onEditProfile = { id -> navController.navigate(Routes.serverSettings(id)) },
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
                    onAddServer = { navController.navigate(Routes.SERVER_EDIT) },
                    onEditProfile = { id -> navController.navigate(Routes.serverSettings(id)) },
                )
            }
        }

        composable(
            route = "${Routes.CHAT}/{sessionId}?focus={focus}",
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("focus") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            ChatScreen(
                container = container,
                sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                focusMessageId = entry.arguments?.getString("focus"),
                onBack = { navController.popBackStack() },
                onOpenFile = { path -> navController.navigate(Routes.fileView(path)) },
                onOpenSession = { id ->
                    navController.navigate(Routes.chat(id)) {
                        // Prevent the same chat from being pushed twice when re-tapping a
                        // session deep link, and keep the back stack bounded when chaining
                        // chat → linked chat → … (otherwise every hop piles a new entry).
                        launchSingleTop = true
                    }
                },
                onEditProfile = { id -> navController.navigate(Routes.serverSettings(id)) },
            )
        }

        composable(Routes.FILES) {
            if (isTwoPane) {
                TwoPaneFileBrowser(
                    container = container,
                    onBack = { navController.popBackStack() },
                )
            } else {
                FileBrowserScreen(
                    container = container,
                    onOpenFile = { path, line -> navController.navigate(Routes.fileView(path, line)) },
                    onBack = { navController.popBackStack() },
                )
            }
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
                onEditProfile = { id -> navController.navigate(Routes.serverSettings(id)) },
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
                onOpenSession = { id, focusMessageId -> navController.navigate(Routes.chat(id, focusMessageId)) },
                onBack = { navController.popBackStack() },
            )
        }
        }
        }

        // Collect unread/run state for nav badges (rail on large screens, bottom bar on
        // compact screens), so a user on Files/Settings sees pending activity at a glance.
        val unread by container.unread.collectAsStateWithLifecycle()
        val anyRunActive by container.anyRunActive.collectAsStateWithLifecycle()
        val totalUnread = remember(unread) { unread.values.sum() }

        if (showNavRail) {
            Row(modifier = Modifier.fillMaxSize()) {
                LargeScreenNavRail(
                    currentRoute = currentRoute,
                    connected = connection != null,
                    unreadCount = totalUnread,
                    runActive = anyRunActive,
                    // Use the standard M3 nav-bar pattern: popUpTo the start destination
                    // saving state, and restoreState on re-entry, so switching between
                    // top-level tabs via the rail preserves each tab's scroll/selection
                    // state and back always exits cleanly instead of popping through
                    // every sibling tab the user visited.
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { navHost() }
            }
        } else {
            // Compact screens: a bottom NavigationBar surfaces the top-level destinations one
            // tap away (Sessions/Search/Files/Settings/Servers) instead of buried in each
            // screen's overflow menu. The navHost fills the space above the bar; the bar's
            // own height is accounted for by the NavigationBar's intrinsic measurement.
            androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { navHost() }
                CompactNavBar(
                    currentRoute = currentRoute,
                    connected = connection != null,
                    unreadCount = totalUnread,
                    runActive = anyRunActive,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
    }
    }
}
