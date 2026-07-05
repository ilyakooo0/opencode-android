package soy.iko.opencode.ui.components

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import soy.iko.opencode.R
import soy.iko.opencode.ui.Routes

/** A top-level destination surfaced on the large-screen navigation rail. */
private data class RailDest(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    /** Whether the destination needs an active server connection. */
    val requiresConnection: Boolean,
)

private val RAIL_DESTINATIONS = listOf(
    RailDest(Routes.SESSIONS, R.string.sessions_title, Icons.AutoMirrored.Filled.Chat, true),
    RailDest(Routes.SEARCH, R.string.search_all_title, Icons.Filled.Search, true),
    RailDest(Routes.FILES, R.string.files, Icons.Filled.Folder, true),
    RailDest(Routes.USAGE, R.string.usage_title, Icons.Filled.QueryStats, true),
    RailDest(Routes.MCP, R.string.mcp_servers, Icons.Filled.Hub, true),
    RailDest(Routes.SETTINGS, R.string.settings, Icons.Filled.Settings, false),
    RailDest(Routes.SERVERS, R.string.servers_title, Icons.Filled.Dns, false),
)

/**
 * A [NavigationRail] shown alongside the nav content on large screens (tablets / foldables), so
 * the top-level destinations are one tap away instead of buried in a screen's overflow menu.
 *
 * @param currentRoute the current NavHost destination route, used to highlight the active item.
 * @param connected whether a server connection is active; connection-dependent items (sessions,
 *  files, …) are disabled until one exists.
 * @param unreadCount total unread messages across sessions, shown as a badge on the Sessions item.
 * @param runActive whether any agent run is active, shown as a dot badge on the Sessions item.
 * @param onNavigate invoked with the destination's route when an item is selected.
 */
@Composable
fun LargeScreenNavRail(
    currentRoute: String?,
    connected: Boolean,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0,
    runActive: Boolean = false,
) {
    NavigationRail(modifier = modifier.fillMaxHeight()) {
        val haptics = LocalHapticFeedback.current
        RAIL_DESTINATIONS.forEach { dest ->
            // Match exact top-level routes, and also highlight the rail item when a detail
            // route pushed on top of it is active: chat/{id} is a detail of `sessions`, and
            // file_view?path=… is a detail of `files`. Without this, drilling into a file
            // loses the FILES highlight and the user loses their place in the rail.
            val selected = currentRoute == dest.route ||
                (dest.route == Routes.SESSIONS && currentRoute != null && currentRoute.startsWith("${Routes.CHAT}/")) ||
                (dest.route == Routes.FILES && currentRoute != null && currentRoute.startsWith(Routes.FILE_VIEW))
            // Badge the Sessions item with unread count and/or a running dot, so a user on
            // Files/Settings can see at a glance that sessions need attention or a run is
            // active — mirroring the launcher badge but in-app.
            val showUnreadBadge = dest.route == Routes.SESSIONS && unreadCount > 0
            val showRunBadge = dest.route == Routes.SESSIONS && runActive
            NavigationRailItem(
                selected = selected,
                enabled = !dest.requiresConnection || connected,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onNavigate(dest.route)
                },
                icon = {
                    if (showUnreadBadge || showRunBadge) {
                        BadgedBox(
                            badge = {
                                if (showUnreadBadge) {
                                    Badge { Text(unreadCount.coerceAtMost(99).toString()) }
                                } else if (showRunBadge) {
                                    Badge()
                                }
                            },
                        ) { Icon(dest.icon, contentDescription = null) }
                    } else {
                        Icon(dest.icon, contentDescription = null)
                    }
                },
                label = { Text(stringResource(dest.labelRes)) },
                colors = NavigationRailItemDefaults.colors(),
            )
        }
    }
}
