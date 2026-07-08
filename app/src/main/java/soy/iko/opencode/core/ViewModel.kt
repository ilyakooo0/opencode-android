package soy.iko.opencode.core

/**
 * Immutable snapshot the UI renders. Mirrors the Rust core's `ViewModel`, so
 * the two implementations stay behaviourally identical.
 */

enum class Screen { Connect, Sessions, Chat }

enum class MessageStatus { Sent, Pending, Failed }

data class SessionView(
    val id: String,
    val title: String,
)

data class ToolView(
    val name: String,
    val status: String,
    val title: String?,
)

data class MessageView(
    val id: String,
    val role: String,
    val text: String,
    val reasoning: String?,
    val tools: List<ToolView>,
    val time: Long,
    val status: MessageStatus,
    val streaming: Boolean,
) {
    val isUser: Boolean get() = role == "user"
}

data class UiState(
    val screen: Screen = Screen.Connect,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val authRequired: Boolean = false,
    val connected: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val sessions: List<SessionView> = emptyList(),
    val currentSessionId: String? = null,
    val currentSessionTitle: String = "",
    val messages: List<MessageView> = emptyList(),
    val draft: String = "",
    val generating: Boolean = false,
    val sseConnected: Boolean = true,
)

/**
 * Everything the shell can ask the core to do. Internal HTTP results are not
 * events here (unlike the Rust core, which routes them through `then_send`);
 * the Kotlin driver awaits them inline in coroutines instead.
 */
sealed interface Event {
    data class ServerUrlChanged(val url: String) : Event
    data class UsernameChanged(val value: String) : Event
    data class PasswordChanged(val value: String) : Event
    data object Connect : Event

    data object LoadSessions : Event
    data class SelectSession(val id: String) : Event
    data object CreateSession : Event
    data class DeleteSession(val id: String) : Event

    data class DraftChanged(val text: String) : Event
    data class SendMessage(val text: String) : Event
    data object CancelGeneration : Event

    data object NavigateToSessions : Event
    data object NavigateToConnect : Event
    data object DismissError : Event
}
