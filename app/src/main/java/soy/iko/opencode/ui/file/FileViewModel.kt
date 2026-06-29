package soy.iko.opencode.ui.file

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
import kotlinx.coroutines.launch

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

    init {
        // Observe the active connection so the file loads (or reloads) when a connection
        // becomes available — including when the view opens during a reconnect window
        // where activeConnection.value was momentarily null. collectLatest cancels the
        // in-flight load if the connection is replaced mid-read.
        viewModelScope.launch {
            container.activeConnection.collectLatest { conn ->
                if (conn == null) {
                    _state.value = FileViewState(loading = false, error = container.string(R.string.not_connected))
                    return@collectLatest
                }
                _state.value = FileViewState(loading = true)
                runCatching { conn.api.readFile(path) }
                    .onSuccess { _state.value = FileViewState(loading = false, content = it) }
                    .onFailure { _state.value = FileViewState(loading = false, error = container.friendlyError(it)) }
            }
        }
    }
}
