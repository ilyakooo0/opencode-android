package soy.iko.opencode.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.ui.chat.ChatScreen
import soy.iko.opencode.ui.file.FileBrowserScreen
import soy.iko.opencode.ui.file.FileViewScreen
import soy.iko.opencode.ui.server.ServerEditScreen
import soy.iko.opencode.ui.server.ServerListScreen
import soy.iko.opencode.ui.session.SessionListScreen
import soy.iko.opencode.ui.settings.SettingsScreen

@Composable
fun OpencodeApp(container: AppContainer) {
    val navController = rememberNavController()
    val pendingShare by container.pendingShare.collectAsStateWithLifecycle()

    // When text is shared into the app, surface the session list so the user can pick
    // (or create) a conversation to drop it into. The chosen session's draft is set
    // in [onOpenSession] below. Fresh launches rely on auto-reconnect to get here.
    LaunchedEffect(pendingShare) {
        if (pendingShare == null) return@LaunchedEffect
        if (container.activeConnection.value == null) return@LaunchedEffect
        if (!navController.popBackStack(Routes.SESSIONS, inclusive = false)) {
            navController.navigate(Routes.SESSIONS) { launchSingleTop = true }
        }
    }

    NavHost(navController = navController, startDestination = Routes.SERVERS) {

        composable(Routes.SERVERS) {
            ServerListScreen(
                container = container,
                onConnected = { navController.navigate(Routes.SESSIONS) },
                onAddProfile = { navController.navigate(Routes.serverEdit()) },
                onEditProfile = { id -> navController.navigate(Routes.serverEdit(id)) },
            )
        }

        composable(
            route = "${Routes.SERVER_EDIT}?id={id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            ServerEditScreen(
                container = container,
                profileId = entry.arguments?.getString("id"),
                onDone = { navController.popBackStack() },
            )
        }

        composable(Routes.SESSIONS) {
            SessionListScreen(
                container = container,
                onOpenSession = { id ->
                    container.consumePendingShare()?.let { container.draftStore.set(id, it) }
                    navController.navigate(Routes.chat(id))
                },
                onDisconnect = {
                    container.disconnect()
                    navController.popBackStack(Routes.SERVERS, inclusive = false)
                },
                onOpenFiles = { navController.navigate(Routes.FILES) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
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
                onManageServers = {
                    navController.popBackStack(Routes.SERVERS, inclusive = false)
                },
            )
        }
    }
}
