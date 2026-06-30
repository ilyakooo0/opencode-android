@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package soy.iko.opencode.ui.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import soy.iko.opencode.data.model.Agent
import soy.iko.opencode.data.model.BusEvent
import soy.iko.opencode.data.model.Command
import soy.iko.opencode.data.model.FileContent
import soy.iko.opencode.data.model.FileNode
import soy.iko.opencode.data.model.FileStatusEntry
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.ModelRef
import soy.iko.opencode.data.model.PermissionResponse
import soy.iko.opencode.data.model.ProvidersResponse
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.data.network.OpencodeApiClient
import soy.iko.opencode.data.repo.DraftStore
import soy.iko.opencode.data.repo.ProfileStore
import soy.iko.opencode.data.repo.SessionRepository
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.di.OpencodeConnection
import soy.iko.opencode.di.ProbeResult

// ---------------------------------------------------------------------------
// FakeDraftStore
// ---------------------------------------------------------------------------

class FakeDraftStore : DraftStore() {
    private val store = mutableMapOf<String, String>()
    private val _ready = MutableStateFlow(true)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()

    var lastFlushed: Pair<String, String>? = null
        private set

    override fun get(sessionId: String): String = store[sessionId].orEmpty()

    override suspend fun set(sessionId: String, text: String) {
        if (text.isBlank()) store.remove(sessionId) else store[sessionId] = text
    }

    override fun setImmediate(sessionId: String, text: String) {
        if (text.isBlank()) store.remove(sessionId) else store[sessionId] = text
    }

    override suspend fun remove(sessionId: String) {
        store.remove(sessionId)
    }

    override fun flushDraft(sessionId: String, text: String) {
        lastFlushed = sessionId to text
        if (text.isBlank()) store.remove(sessionId) else store[sessionId] = text
    }

    override fun shutdown() { /* no-op */ }
}

// ---------------------------------------------------------------------------
// FakeProfileStore
// ---------------------------------------------------------------------------

class FakeProfileStore : ProfileStore() {
    private val _profiles = MutableStateFlow<List<ServerProfile>>(emptyList())

    override val profiles: Flow<List<ServerProfile>> = _profiles.asStateFlow()

    fun setProfiles(profiles: List<ServerProfile>) {
        _profiles.value = profiles
    }

    var savedProfile: ServerProfile? = null
        private set

    /** When non-null, [save] throws this exception instead of persisting. */
    var saveException: Exception? = null

    override suspend fun resolve(profile: ServerProfile): ServerProfile = profile

    override suspend fun save(profile: ServerProfile) {
        saveException?.let { throw it }
        savedProfile = profile
        val current = _profiles.value.toMutableList()
        current.removeAll { it.id == profile.id }
        current.add(profile)
        _profiles.value = current
    }

    override suspend fun delete(id: String) {
        _profiles.value = _profiles.value.filterNot { it.id == id }
    }
}

// ---------------------------------------------------------------------------
// FakeOpencodeApiClient
// ---------------------------------------------------------------------------

class FakeOpencodeApiClient : OpencodeApiClient() {
    var pingThrows: Throwable? = null
    var sessions: List<Session> = emptyList()
    var createdSession: Session = Session(id = "new-session", title = "New")
    var deletedSessions: List<String> = emptyList()
    var messages: List<MessageWithParts> = emptyList()
    var providersResponse: ProvidersResponse = ProvidersResponse()
    var agentsList: List<Agent> = emptyList()
    var commandsList: List<Command> = emptyList()
    var directoryListing: List<FileNode> = emptyList()
    var fileSearchResults: List<String> = emptyList()
    var fileStatusEntries: List<FileStatusEntry> = emptyList()
    var fileContent: FileContent = FileContent(content = "")
    var sendPromptThrows: Throwable? = null
    var respondPermissionThrows: Throwable? = null
    var cacheInvalidated = false

    var sendPromptCalls: List<Triple<String, String, ModelRef?>> = emptyList()
        private set
    var runCommandCalls: List<Triple<String, String, String?>> = emptyList()
        private set
    var respondPermissionCalls: List<Triple<String, String, PermissionResponse>> = emptyList()
        private set

    override suspend fun ping() {
        pingThrows?.let { throw it }
    }

    override suspend fun listSessions(): List<Session> = sessions

    override suspend fun createSession(title: String?): Session = createdSession

    override suspend fun updateSession(id: String, title: String): Session {
        return sessions.firstOrNull { it.id == id }?.copy(title = title) ?: Session(id = id, title = title)
    }

    override suspend fun deleteSession(id: String) {
        deletedSessions = deletedSessions + id
    }

    override suspend fun listMessages(sessionId: String): List<MessageWithParts> = messages

    override suspend fun sendPrompt(
        sessionId: String,
        text: String,
        model: ModelRef?,
        agent: String?,
    ): MessageWithParts {
        sendPromptCalls = sendPromptCalls + Triple(sessionId, text, model)
        sendPromptThrows?.let { throw it }
        return MessageWithParts(
            info = soy.iko.opencode.data.model.UserMessage(id = "msg-${sendPromptCalls.size}", sessionID = sessionId),
            parts = emptyList(),
        )
    }

    override suspend fun abort(sessionId: String) { /* no-op */ }

    override suspend fun runCommand(
        sessionId: String,
        command: String,
        arguments: String,
        agent: String?,
    ): MessageWithParts {
        runCommandCalls = runCommandCalls + Triple(sessionId, command, agent)
        return MessageWithParts(
            info = soy.iko.opencode.data.model.UserMessage(id = "cmd-${runCommandCalls.size}", sessionID = sessionId),
            parts = emptyList(),
        )
    }

    override suspend fun providers(): ProvidersResponse = providersResponse
    override suspend fun agents(): List<Agent> = agentsList
    override suspend fun commands(): List<Command> = commandsList

    override suspend fun invalidateCache() { cacheInvalidated = true }

    override suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
    ): String {
        respondPermissionCalls = respondPermissionCalls + Triple(sessionId, permissionId, response)
        respondPermissionThrows?.let { throw it }
        return ""
    }

    override suspend fun findFiles(query: String): List<String> = fileSearchResults
    override suspend fun listDirectory(path: String): List<FileNode> = directoryListing
    override suspend fun readFile(path: String): FileContent = fileContent
    override suspend fun fileStatus(): List<FileStatusEntry> = fileStatusEntries
}

// ---------------------------------------------------------------------------
// FakeEventStreamClient
// ---------------------------------------------------------------------------

class FakeEventStreamClient : EventStreamClient() {
    val fakeState = MutableStateFlow(EventStreamClient.ConnectionState.Connected)
    override val state: StateFlow<EventStreamClient.ConnectionState> = fakeState.asStateFlow()

    val fakeEvents = MutableSharedFlow<BusEvent>(extraBufferCapacity = NetworkConfig.sseEventBufferCapacity)
    override val events: kotlinx.coroutines.flow.SharedFlow<BusEvent> = fakeEvents.asSharedFlow()

    override fun triggerReconnect() { /* no-op */ }
}

// ---------------------------------------------------------------------------
// FakeSessionRepository
// ---------------------------------------------------------------------------

class FakeSessionRepository(
    private val api: FakeOpencodeApiClient,
    private val eventStream: FakeEventStreamClient,
) : SessionRepository(api, eventStream) {
    var sessions: List<Session> = emptyList()
    var createdSession: Session = Session(id = "new-session", title = "New")
    var messages: List<MessageWithParts> = emptyList()
    var sendPromptThrows: Throwable? = null
    var listSessionsThrows: Throwable? = null
    var abortCalls: List<String> = emptyList()
        private set

    /** When non-null, [observeMessages] returns this flow instead of [messages]. */
    var observeMessagesOverride: kotlinx.coroutines.flow.Flow<List<MessageWithParts>>? = null

    override suspend fun listSessions(): List<Session> {
        listSessionsThrows?.let { throw it }
        return sessions
    }
    override suspend fun createSession(title: String?): Session = createdSession
    override suspend fun deleteSession(id: String) { /* no-op */ }
    override suspend fun abort(sessionId: String) { abortCalls = abortCalls + sessionId }

    override suspend fun sendPrompt(
        sessionId: String,
        text: String,
        model: ModelRef?,
        agent: String?,
    ): MessageWithParts {
        sendPromptThrows?.let { throw it }
        return MessageWithParts(
            info = soy.iko.opencode.data.model.UserMessage(id = "msg-1", sessionID = sessionId),
            parts = emptyList(),
        )
    }

    override suspend fun runCommand(
        sessionId: String,
        command: String,
        arguments: String,
        agent: String?,
    ): MessageWithParts = MessageWithParts(
        info = soy.iko.opencode.data.model.UserMessage(id = "cmd-1", sessionID = sessionId),
        parts = emptyList(),
    )

    override fun observeMessages(sessionId: String): Flow<List<MessageWithParts>> =
        observeMessagesOverride ?: flowOf(messages)
}

// ---------------------------------------------------------------------------
// FakeOpencodeConnection
// ---------------------------------------------------------------------------

class FakeOpencodeConnection(
    override val api: FakeOpencodeApiClient,
    override val events: FakeEventStreamClient,
    override val repository: FakeSessionRepository,
    profile: ServerProfile,
) : OpencodeConnection(profile) {
    override suspend fun close() { /* no-op */ }
}

// ---------------------------------------------------------------------------
// FakeAppContainer
// ---------------------------------------------------------------------------

class FakeAppContainer : AppContainer() {
    val fakeDraftStore = FakeDraftStore()
    val fakeProfileStore = FakeProfileStore()
    val fakeUnread = MutableStateFlow<Set<String>>(emptySet())
    private val fakeActiveConnection = MutableStateFlow<OpencodeConnection?>(null)

    override val draftStore: DraftStore = fakeDraftStore
    override val profileStore: ProfileStore = fakeProfileStore
    override val activeConnection: StateFlow<OpencodeConnection?> = fakeActiveConnection.asStateFlow()
    override val unread: StateFlow<Set<String>> = fakeUnread.asStateFlow()

    var connectResult: OpencodeConnection? = null
    var connectCalls: List<ServerProfile> = emptyList()
        private set
    var disconnectCalls = 0
        private set

    /** When non-null, [probeServer] returns this instead of building a real client. */
    var probeResult: ProbeResult? = null
    var probeCalls: List<String> = emptyList()
        private set

    override fun string(id: Int, vararg formatArgs: Any): String = "test-string-$id"

    override fun friendlyError(t: Throwable): String = "test-error: ${t.message ?: t::class.simpleName}"

    override fun friendlyErrorFor(t: Throwable, baseUrl: String): String =
        "test-error: ${t.message ?: t::class.simpleName}"

    override suspend fun connect(profile: ServerProfile): OpencodeConnection {
        connectCalls = connectCalls + profile
        connectResult?.let { return it }
        // Create a fake connection on the fly if none pre-configured
        val api = FakeOpencodeApiClient()
        val events = FakeEventStreamClient()
        val repo = FakeSessionRepository(api, events)
        return FakeOpencodeConnection(api, events, repo, profile)
    }

    override suspend fun disconnect() {
        disconnectCalls++
        fakeActiveConnection.value = null
    }

    override suspend fun probeServer(baseUrl: String): ProbeResult {
        probeCalls = probeCalls + baseUrl
        return probeResult ?: ProbeResult.Reachable
    }

    fun setActiveConnection(conn: OpencodeConnection?) {
        fakeActiveConnection.value = conn
    }
}
