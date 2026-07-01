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
import androidx.compose.runtime.rememberCoroutineScope
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
    val pendingOpenSession by container.pendingOpenSession.collectAsStateWithLifecycle()
    val connection by container.activeConnection.collectAsStateWithLifecycle()

    // When text is shared into the app, surface the session list so the user can pick
    // (or create) a conversation to drop it into. The chosen session's draft is set
    // in [onOpenSession] below. Keyed on `connection` too so a share that arrives
    // before auto-reconnect completes is retried once the connection is established.
    LaunchedEffect(pendingShare, connection) {
        if (pendingShare == null) return@LaunchedEffect
        if (connection == null) return@LaunchedEffect
        if (!navController.popBackStack(Routes.SESSIONS, inclusive = false)) {
            navController.navigate(Routes.SESSIONS) { launchSingleTop = true }
        }
    }

    // Adaptive: on wide screens (tablets / unfolded foldables) show the session list and
    // the open conversation side by side instead of a single-pane back stack.
    BoxWithConstraints {
        val isTwoPane = maxWidth >= NetworkConfig.twoPaneWidthThresholdDp.dp && connection != null

        // Open a session requested by a notification tap or deep link, once connected.
        // In two-pane mode the detail pane consumes the request instead of navigating.
        LaunchedEffect(pendingOpenSession, connection, isTwoPane) {
            val id = pendingOpenSession ?: return@LaunchedEffect
            if (connection == null || isTwoPane) return@LaunchedEffect
            container.consumePendingOpenSession()
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
                onConnected = { navController.navigate(Routes.SESSIONS) },
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
            )
        }

        composable(Routes.FILES) {
            FileBrowserScreen(
                container = container,
                onOpenFile = { path -> navController.navigate(Routes.fileView(path)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = "${Routes.FILE_VIEW}?path={path}",
            arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            FileViewScreen(
                container = container,
                path = entry.arguments?.getString("path").orEmpty(),
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
