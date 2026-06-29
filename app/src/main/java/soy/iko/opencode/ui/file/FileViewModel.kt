package soy.iko.opencode.ui.file

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.FileContent
import soy.iko.opencode.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FileViewState(
    val loading: Boolean = true,
    val content: FileContent? = null,
    val error: String? = null,
)

class FileViewModel(
    private val container: AppContainer,
    private val path: String,
) : ViewModel() {

    private val _state = MutableStateFlow(FileViewState())
    val state: StateFlow<FileViewState> = _state.asStateFlow()

    init {
        val api = container.activeConnection.value?.api
        if (api == null) {
            _state.value = FileViewState(loading = false, error = "Not connected")
        } else {
            viewModelScope.launch {
                runCatching { api.readFile(path) }
                    .onSuccess { _state.value = FileViewState(loading = false, content = it) }
                    .onFailure { _state.value = FileViewState(loading = false, error = it.message ?: "Failed to read $path") }
            }
        }
    }
}
