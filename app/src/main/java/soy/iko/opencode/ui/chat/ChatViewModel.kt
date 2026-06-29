package soy.iko.opencode.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.ModelOption
import soy.iko.opencode.data.model.Permission
import soy.iko.opencode.data.model.PermissionReplied
import soy.iko.opencode.data.model.PermissionResponse
import soy.iko.opencode.data.model.PermissionUpdated
import soy.iko.opencode.data.model.defaultOption
import soy.iko.opencode.data.model.toOptions
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.data.repo.SessionRepository
import soy.iko.opencode.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val container: AppContainer,
    private val sessionId: String,
) : ViewModel() {

    private val connection = container.activeConnection.value

    val connected: Boolean get() = connection != null

    val messages: StateFlow<List<MessageWithParts>> =
        (connection?.repository?.observeMessages(sessionId) ?: flowOf(emptyList<MessageWithParts>()))
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _models = MutableStateFlow<List<ModelOption>>(emptyList())
    val models: StateFlow<List<ModelOption>> = _models.asStateFlow()

    private val _selectedModel = MutableStateFlow<ModelOption?>(null)
    val selectedModel: StateFlow<ModelOption?> = _selectedModel.asStateFlow()

    val connectionState: StateFlow<EventStreamClient.ConnectionState> =
        connection?.events?.state
            ?: MutableStateFlow(EventStreamClient.ConnectionState.Disconnected).asStateFlow()

    private val _pendingPermission = MutableStateFlow<Permission?>(null)
    val pendingPermission: StateFlow<Permission?> = _pendingPermission.asStateFlow()

    fun clearError() { _error.value = null }

    fun selectModel(option: ModelOption) { _selectedModel.value = option }

    init {
        // Watch the bus for run completion / errors / permission asks for this session.
        val conn = connection
        if (conn != null) {
            viewModelScope.launch {
                conn.events.events.collect { event ->
                    if (SessionRepository.isIdle(event, sessionId)) _running.value = false
                    if (SessionRepository.isError(event, sessionId)) {
                        _running.value = false
                        _error.value = "The agent reported an error."
                    }
                    when (event) {
                        is PermissionUpdated ->
                            if (event.properties.sessionID == sessionId) _pendingPermission.value = event.properties
                        is PermissionReplied ->
                            if (event.properties.permissionID == _pendingPermission.value?.id) _pendingPermission.value = null
                        else -> {}
                    }
                }
            }
            // Load the model catalog; preselect the server default. Failure is non-fatal —
            // sending with no model just uses the server's default agent/model.
            viewModelScope.launch {
                runCatching { conn.api.providers() }.getOrNull()?.let { resp ->
                    val options = resp.toOptions()
                    _models.value = options
                    _selectedModel.value = resp.defaultOption(options)
                }
            }
        }
    }

    fun send(text: String) {
        val conn = connection ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _running.value) return
        _running.value = true
        _error.value = null
        viewModelScope.launch {
            runCatching { conn.repository.sendPrompt(sessionId, trimmed, model = _selectedModel.value?.ref) }
                .onFailure { _error.value = it.message ?: "Failed to send" }
            // session.idle is the authoritative finalizer; clear here too in case the
            // POST returns first (idempotent).
            _running.value = false
        }
    }

    fun abort() {
        val conn = connection ?: return
        viewModelScope.launch {
            runCatching { conn.repository.abort(sessionId) }
            _running.value = false
        }
    }

    fun respondPermission(permission: Permission, response: PermissionResponse) {
        val conn = connection ?: return
        // Clear optimistically; permission.replied will confirm.
        _pendingPermission.value = null
        viewModelScope.launch {
            runCatching { conn.api.respondPermission(sessionId, permission.id, response) }
                .onFailure { _error.value = it.message ?: "Failed to respond to permission" }
        }
    }
}
