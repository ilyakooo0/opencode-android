package soy.iko.opencode.ui.file

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import soy.iko.opencode.R
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.ui.components.EmptyState

/**
 * Master–detail layout for the file browser on wide screens (≥ 840dp): the directory listing
 * on the left, the selected file's viewer on the right. Mirrors [TwoPaneSessionChat]: each
 * side hosts an existing screen (which carries its own Scaffold + app bar), so the panes are
 * self-contained. Selecting a file updates the detail pane rather than pushing a destination;
 * the viewer's onBack clears the selection.
 */
@Composable
fun TwoPaneFileBrowser(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // A deep-linked file open (opencode://file/{path}) targets the detail pane here.
    val pendingOpenFile by container.pendingOpenFile.collectAsStateWithLifecycle()
    LaunchedEffect(pendingOpenFile) {
        pendingOpenFile?.let {
            selected = it
            container.consumePendingOpenFile()
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(NetworkConfig.twoPaneLeftWeight)
                .widthIn(max = 460.dp),
        ) {
            FileBrowserScreen(
                container = container,
                // Selecting a file opens it in the detail pane (not the back stack).
                onOpenFile = { path, _ -> selected = path },
                onBack = onBack,
            )
        }

        VerticalDivider(modifier = Modifier.fillMaxHeight())

        Box(modifier = Modifier.weight(NetworkConfig.twoPaneRightWeight).fillMaxSize()) {
            val path = selected
            if (path == null) {
                EmptyState(
                    icon = Icons.Filled.Description,
                    title = stringResource(R.string.empty_file_detail_title),
                    description = stringResource(R.string.empty_file_detail_desc),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                BackHandler { selected = null }
                // key() on the path so switching files in two-pane mode gives FileViewScreen a
                // fresh composition (its find/wrap state and scroll are per-file, like the chat
                // pane's per-session keying).
                key(path) {
                    FileViewScreen(
                        container = container,
                        path = path,
                        onBack = { selected = null },
                    )
                }
            }
        }
    }
}
