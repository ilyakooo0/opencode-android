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
import kotlinx.coroutines.launch
import soy.iko.opencode.util.runCatchingCancellable

@Immutable
data class FileViewState(
    val loading: Boolean = true,
    val content: FileContent? = null,
    val error: String? = null,
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
                _state.value = FileViewState(loading = true)
                runCatchingCancellable { conn.api.readFile(path) }
                    .onSuccess { _state.value = FileViewState(loading = false, content = it) }
                    .onFailure { _state.value = FileViewState(loading = false, error = container.friendlyError(it)) }
            }
        }
    }
}
