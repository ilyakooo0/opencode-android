package soy.iko.opencode.ui.file

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.FileNode
import soy.iko.opencode.data.model.FileStatusEntry
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.util.runCatchingCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class FileBrowserState(
    val path: String = "",
    val entries: List<FileNode> = emptyList(),
    val statusMap: Map<String, FileStatusEntry> = emptyMap(),
    val query: String = "",
    val results: List<String> = emptyList(),
    val searching: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val isSearching: Boolean get() = query.isNotBlank()
}

@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserViewModel(private val container: AppContainer) : ViewModel() {

    private val api get() = container.activeConnection.value?.api

    private val _state = MutableStateFlow(FileBrowserState())
    val state: StateFlow<FileBrowserState> = _state.asStateFlow()

    /** Transient errors (failed VCS status fetch, etc.) surfaced without hiding the file list. */
    private val _transientError = MutableStateFlow<String?>(null)
    val transientError: StateFlow<String?> = _transientError.asStateFlow()
    fun clearTransientError() { _transientError.value = null }

    private var searchJob: Job? = null
    private var openJob: Job? = null

    init {
        open("")
        // Observe the active connection so VCS status loads (or reloads) when a connection
        // becomes available — including when the view opens during a reconnect window where
        // activeConnection.value was momentarily null. Without this, loadStatus() would be
        // called once from init with a null api and never retried.
        viewModelScope.launch {
            container.activeConnection.collectLatest { conn ->
                if (conn == null) return@collectLatest
                loadStatus()
                // Re-open the current directory when a connection becomes available
                // so the initial open("") (which may have run before auto-reconnect
                // finished and found no connection) doesn't leave a stale error.
                open(_state.value.path)
            }
        }
    }

    /** Tracks the in-flight VCS status load so a server switch can cancel it. */
    private var statusJob: Job? = null

    /** Fetch the repo-wide VCS status once so file rows can show git badges. */
    private fun loadStatus() {
        val client = api ?: return
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            runCatchingCancellable { client.fileStatus() }
                .onSuccess { entries ->
                    _state.update { it.copy(statusMap = entries.associateBy { it.path }) }
                }
                .onFailure {
                    // VCS status is best-effort: don't block the file browser, but surface
                    // a transient error so the user knows why badges are missing.
                    _transientError.value = container.friendlyError(it)
                }
        }
    }

    fun open(path: String) {
        val client = api ?: run {
            _state.update { it.copy(
                path = path,
                loading = false,
                error = container.string(R.string.not_connected),
            ) }
            return
        }
        _state.update { it.copy(
            path = path,
            query = "",
            results = emptyList(),
            searching = false,
            loading = true,
            error = null,
        ) }
        openJob?.cancel()
        searchJob?.cancel()
        openJob = viewModelScope.launch {
            runCatchingCancellable { client.listDirectory(path) }
                .onSuccess { entries ->
                    _state.update { it.copy(
                        entries = entries.sortedWith(compareByDescending<FileNode> { it.isDirectory }.thenBy { it.name.lowercase() }),
                        loading = false,
                    ) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = container.friendlyError(e)) } }
        }
    }

    /** Navigate up one path segment. */
    fun up() {
        val current = _state.value.path
        if (current.isBlank()) return
        val parent = current.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
        open(parent)
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query, error = null) }
        searchJob?.cancel()
        openJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(results = emptyList(), searching = false) }
            return
        }
        val client = api ?: run {
            _state.update { it.copy(searching = false, error = container.string(R.string.not_connected)) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(NetworkConfig.fileSearchDebounceMs) // debounce
            _state.update { it.copy(searching = true) }
            runCatchingCancellable { client.findFiles(query) }
                .onSuccess { results -> _state.update { it.copy(results = results, searching = false) } }
                .onFailure { e -> _state.update { it.copy(searching = false, error = container.friendlyError(e)) } }
        }
    }
}
