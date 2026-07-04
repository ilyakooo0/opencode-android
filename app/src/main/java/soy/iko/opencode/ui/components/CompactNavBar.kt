package soy.iko.opencode.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import soy.iko.opencode.R
import soy.iko.opencode.ui.Routes

/** A top-level destination surfaced on the compact-screen bottom navigation bar. Mirrors
 *  [RailDest] in [LargeScreenNavRail] but is kept separate so the two bars can diverge — a
 *  bottom bar is limited to 3-5 items for thumb reach, while the rail can show all seven. */
private data class BottomDest(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    val requiresConnection: Boolean,
)

// Five items: the most-used destinations that make sense from anywhere in the app. Servers and
// Usage/MCP stay in the session list / settings overflow menus to avoid overcrowding the bar.
private val BOTTOM_DESTINATIONS = listOf(
    BottomDest(Routes.SESSIONS, R.string.sessions_title, Icons.AutoMirrored.Filled.Chat, true),
    BottomDest(Routes.SEARCH, R.string.search_all_title, Icons.Filled.Search, true),
    BottomDest(Routes.FILES, R.string.files, Icons.Filled.Folder, true),
    BottomDest(Routes.SETTINGS, R.string.settings, Icons.Filled.Settings, false),
    BottomDest(Routes.SERVERS, R.string.servers_title, Icons.Filled.Dns, false),
)

/**
 * A Material 3 [NavigationBar] shown at the bottom of compact-screen layouts (phones), so the
 * top-level destinations are one tap away instead of buried in a screen's overflow menu. Mirrors
 * the large-screen [LargeScreenNavRail] — the two are mutually exclusive based on window width.
 *
 * @param currentRoute the current NavHost destination route, used to highlight the active item.
 * @param connected whether a server connection is active; connection-dependent items are disabled.
 * @param unreadCount total unread messages across sessions, shown as a badge on the Sessions item.
 * @param runActive whether any agent run is active, shown as a dot badge on the Sessions item.
 * @param onNavigate invoked with the destination's route when an item is selected.
 */
@Composable
fun CompactNavBar(
    currentRoute: String?,
    connected: Boolean,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0,
    runActive: Boolean = false,
) {
    NavigationBar(modifier = modifier.fillMaxWidth()) {
        BOTTOM_DESTINATIONS.forEach { dest ->
            val selected = currentRoute == dest.route ||
                (dest.route == Routes.SESSIONS && currentRoute != null &&
                    currentRoute.startsWith("${Routes.CHAT}/")) ||
                (dest.route == Routes.FILES && currentRoute != null &&
                    currentRoute.startsWith(Routes.FILE_VIEW))
            val showUnreadBadge = dest.route == Routes.SESSIONS && unreadCount > 0
            val showRunBadge = dest.route == Routes.SESSIONS && runActive
            NavigationBarItem(
                selected = selected,
                enabled = !dest.requiresConnection || connected,
                onClick = { onNavigate(dest.route) },
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
                colors = NavigationBarItemDefaults.colors(),
            )
        }
    }
}
