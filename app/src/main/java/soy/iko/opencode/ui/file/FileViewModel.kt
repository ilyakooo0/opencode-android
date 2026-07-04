package soy.iko.opencode.ui.file

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.FileContent
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import soy.iko.opencode.util.runCatchingCancellable

@Immutable
data class FileViewState(
    val loading: Boolean = true,
    val content: FileContent? = null,
    val error: String? = null,
    val size: Long? = null,
    val mtime: Long? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class FileViewModel(
    private val container: AppContainer,
    private val path: String,
) : ViewModel() {

    private val _state = MutableStateFlow(FileViewState())
    val state: StateFlow<FileViewState> = _state.asStateFlow()

    /** Incremented by [reload] to trigger a fresh fetch without waiting for a connection change. */
    private val _reload = MutableStateFlow(0)

    fun reload() { _reload.value++ }

    init {
        // Observe the active connection so the file loads (or reloads) when a connection
        // becomes available — including when the view opens during a reconnect window
        // where activeConnection.value was momentarily null. collectLatest cancels the
        // in-flight load if the connection is replaced mid-read. The reload trigger is
        // merged in so a manual retry (e.g. after a transient error) re-fetches.
        viewModelScope.launch {
            merge(container.activeConnection, _reload).collectLatest {
                val conn = container.activeConnection.value
                if (conn == null) {
                    _state.value = FileViewState(loading = false, error = container.string(R.string.not_connected))
                    return@collectLatest
                }
                // Keep any already-loaded content visible during a reload (only the initial
                // load has null content) so a manual refresh doesn't blank the file — matching
                // the file browser, which keeps the prior listing while refreshing.
                _state.update { it.copy(loading = true, error = null) }
                runCatchingCancellable { conn.api.readFile(path) }
                    .onSuccess { result ->
                        // Fetch the file's size/mtime. The server doesn't expose these on the
                        // content endpoint, so look the file up in its parent directory listing
                        // (which carries size/mtime when the server emits them). A missing/failed
                        // metadata fetch is non-fatal — the header just stays hidden — so a
                        // metadata failure can't clobber a successful read.
                        val meta = runCatchingCancellable { fetchMetadata(conn.api, path) }.getOrNull()
                        _state.value = FileViewState(
                            loading = false,
                            content = result,
                            size = meta?.size,
                            mtime = meta?.mtime,
                        )
                    }
                    .onFailure { _state.value = FileViewState(loading = false, error = container.friendlyError(it)) }
            }
        }
    }

    /** Look up [path] in its parent directory listing to read size/mtime. Returns null when
     *  the server doesn't emit size/mtime or the listing fails — the caller treats metadata
     *  as optional. Matches by the full [path] (FileNode.path), falling back to the name so a
     *  server that returns only relative names still resolves. */
    private suspend fun fetchMetadata(api: soy.iko.opencode.data.network.OpencodeApiClient, path: String): soy.iko.opencode.data.model.FileNode? {
        val trimmed = path.trimEnd('/')
        val parent = trimmed.substringBeforeLast('/', "")
        val name = trimmed.substringAfterLast('/')
        val listing = api.listDirectory(parent.ifBlank { "/" })
        return listing.firstOrNull { it.path == path }
            ?: listing.firstOrNull { it.name == name }
    }
}
