package soy.iko.opencode.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import soy.iko.opencode.ui.session.SessionListScreen
import soy.iko.opencode.ui.session.TwoPaneSessionChat
import soy.iko.opencode.ui.settings.DiagnosticsScreen
import soy.iko.opencode.ui.settings.SettingsScreen
import soy.iko.opencode.util.runCatchingCancellable

@Composable
fun OpencodeApp(container: AppContainer) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val pendingShare by container.pendingShare.collectAsStateWithLifecycle()
    val pendingSharedMedia by container.pendingSharedMedia.collectAsStateWithLifecycle()
    val pendingOpenSession by container.pendingOpenSession.collectAsStateWithLifecycle()
    val connection by container.activeConnection.collectAsStateWithLifecycle()

    // Hold a foreground priority for as long as ANY session is actively running — not just
    // while a running chat is on screen — so a run started and then backgrounded (or left
    // for the session list) still keeps the SSE stream alive to deliver its completion
    // notification. Doze/app-standby would otherwise choke the socket once priority drops.
    val anyRunActive by container.anyRunActive.collectAsStateWithLifecycle()
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(anyRunActive) {
        if (anyRunActive) {
            soy.iko.opencode.notification.RunForegroundService.start(appContext)
        } else {
            soy.iko.opencode.notification.RunForegroundService.stop(appContext)
        }
    }

    // When text is shared into the app, surface the session list so the user can pick
    // (or create) a conversation to drop it into. The chosen session's draft is set
    // in [onOpenSession] below. Keyed on `connection` too so a share that arrives
    // before auto-reconnect completes is retried once the connection is established.
    // Tracks the last connection id seen so a server switch (a non-null -> non-null
    // change) doesn't re-bounce the user to the session list for a share they've
    // already decided to abandon; only the null -> non-null initial connect retries.
    var lastConnectionId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingShare, pendingSharedMedia, connection) {
        if (pendingShare == null && pendingSharedMedia.isEmpty()) return@LaunchedEffect
        if (connection == null) { lastConnectionId = null; return@LaunchedEffect }
        val currentId = connection?.profile?.id
        // Only (re)navigate on the initial null -> non-null connect. A subsequent
        // server switch while a share is pending (rare) leaves the user where they
        // are instead of yanking them back to the session list.
        if (lastConnectionId != null && lastConnectionId == currentId) return@LaunchedEffect
        lastConnectionId = currentId
        if (!navController.popBackStack(Routes.SESSIONS, inclusive = false)) {
            navController.navigate(Routes.SESSIONS) { launchSingleTop = true }
        }
    }

    // Adaptive: on wide screens (tablets / unfolded foldables) show the session list and
    // the open conversation side by side instead of a single-pane back stack.
    BoxWithConstraints {
        val isTwoPane = maxWidth >= NetworkConfig.twoPaneWidthThresholdDp.dp && connection != null

        // Open a session requested by a notification tap or deep link, once connected.
        LaunchedEffect(pendingOpenSession, connection, isTwoPane) {
            val id = pendingOpenSession ?: return@LaunchedEffect
            if (connection == null) return@LaunchedEffect
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
            navController.navigate(Routes.chat(id)) { launchSingleTop = true }
        }

        NavHost(
            navController = navController,
            startDestination = Routes.SERVERS,
            // Slide horizontally for forward/back navigation to feel native; fade for
            // the root so the first frame doesn't slide in from off-screen.
            enterTransition = { slideInHorizontally(animationSpec = tween(220)) { it } + fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { slideOutHorizontally(animationSpec = tween(220)) { it } + fadeOut(tween(180)) },
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
            )
        }

        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(onBack = { navController.popBackStack() })
        }
        }
    }
}
