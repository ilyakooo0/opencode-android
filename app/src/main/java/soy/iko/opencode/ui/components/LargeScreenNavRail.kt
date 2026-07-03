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
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import soy.iko.opencode.R

/** A top-level destination surfaced on the large-screen navigation rail. */
private data class RailDest(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    /** Whether the destination needs an active server connection. */
    val requiresConnection: Boolean,
)

private val RAIL_DESTINATIONS = listOf(
    RailDest("sessions", R.string.sessions_title, Icons.AutoMirrored.Filled.Chat, true),
    RailDest("search", R.string.search_all_title, Icons.Filled.Search, true),
    RailDest("files", R.string.files, Icons.Filled.Folder, true),
    RailDest("usage", R.string.usage_title, Icons.Filled.QueryStats, true),
    RailDest("mcp", R.string.mcp_servers, Icons.Filled.Hub, true),
    RailDest("settings", R.string.settings, Icons.Filled.Settings, false),
    RailDest("servers", R.string.servers_title, Icons.Filled.Dns, false),
)

/**
 * A [NavigationRail] shown alongside the nav content on large screens (tablets / foldables), so
 * the top-level destinations are one tap away instead of buried in a screen's overflow menu.
 *
 * @param currentRoute the current NavHost destination route, used to highlight the active item.
 * @param connected whether a server connection is active; connection-dependent items (sessions,
 *  files, …) are disabled until one exists.
 * @param onNavigate invoked with the destination's route when an item is selected.
 */
@Composable
fun LargeScreenNavRail(
    currentRoute: String?,
    connected: Boolean,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(modifier = modifier.fillMaxHeight()) {
        RAIL_DESTINATIONS.forEach { dest ->
            // Match exact top-level routes. Sub-routes (chat/{id}, file_view) don't highlight any
            // rail item — the rail tracks top-level position, not the detail back stack.
            val selected = currentRoute == dest.route ||
                (dest.route == "sessions" && currentRoute != null && currentRoute.startsWith("chat/"))
            NavigationRailItem(
                selected = selected,
                enabled = !dest.requiresConnection || connected,
                onClick = { onNavigate(dest.route) },
                icon = { Icon(dest.icon, contentDescription = null) },
                label = { Text(stringResource(dest.labelRes)) },
                colors = NavigationRailItemDefaults.colors(),
            )
        }
    }
}
