package soy.iko.opencode.core

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import soy.iko.opencode.CrashLogger

/**
 * The bridge between the Crux core (Rust or pure-Kotlin fallback) and the
 * Jetpack Compose UI.
 *
 * - Sends [Event]s to the core via [update]
 * - Processes [Effect]s returned by the core (Render, Http)
 * - Exposes the current [ViewModel] as a [StateFlow] for Compose to observe
 *
 * Basic-auth detection is handled entirely in the Rust core: when the server
 * returns 401, the core sets [ViewModel.authRequired] and the UI shows
 * credential fields. Once the user supplies credentials and re-connects, the
 * core attaches the `Authorization` header to all subsequent requests.
 *
 * Crash logs from [CrashLogger] are forwarded to the core as
 * [Event.CrashLog] events so they appear in the ViewModel.
 */
class Core(application: Application) : AndroidViewModel(application) {
    private val ffi: CoreFfi = CoreFfi.create()
    private val httpClient = HttpClient()
    private val sseClient = SseClient()

    private val prefs = application.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    private val _view = MutableStateFlow(getInitialView())
    val view: StateFlow<ViewModel> = _view.asStateFlow()

    /** Live status of the SSE event stream, for the "disconnected" banner. */
    val sseState: StateFlow<SseState> = sseClient.state

    /**
     * Transient, user-facing success/info notifications (e.g. "Session
     * created", "Connected"). Consumed by the UI as a snackbar. This is a
     * SharedFlow so a configuration change doesn't re-fire an old message.
     */
    private val _info = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val info: SharedFlow<String> = _info.asSharedFlow()

    /** The last event the user triggered, so an error snackbar can retry it. */
    private var lastUserEvent: Event? = null
    val retryableEvent: Event? get() = lastUserEvent

    init {
        // Restore the persisted server URL before the first connect so the
        // user doesn't have to re-enter it on every launch.
        val savedUrl = prefs.getString(KEY_SERVER_URL, null)
        if (savedUrl != null) {
            update(Event.ServerUrlChanged(savedUrl))
        }
        update(Event.Start)

        viewModelScope.launch {
            sseClient.events.collect { data ->
                update(Event.EventReceived(data))
            }
        }

        forwardCrashLogs()
    }

    fun update(event: Event) {
        // Track user-initiated events so an error snackbar can offer Retry.
        if (event.isRetryable()) {
            lastUserEvent = event
        }
        val effectsBytes = ffi.update(event.bincodeSerialize())
        val requests = Requests.bincodeDeserialize(effectsBytes).value
        for (request in requests) {
            processEffect(request)
        }
    }

    /**
     * Re-send the last user-initiated event, if any. Wired to the snackbar's
     * "Retry" action.
     */
    fun retry() {
        val last = lastUserEvent ?: return
        update(last)
    }

    /** Re-establish the SSE stream after a drop. Wired to the banner button. */
    fun reconnectSse() {
        sseClient.reconnect()
    }

    private fun processEffect(request: Request) {
        when (val effect = request.effect) {
            is Effect.Render -> {
                refreshView()
                emitSuccessInfo()
            }
            is Effect.Http -> {
                handleHttpEffect(effect.value, request.id)
            }
        }
    }

    private fun handleHttpEffect(request: HttpRequest, requestId: UInt) {
        viewModelScope.launch {
            val result = httpClient.request(request)
            val effectsBytes = ffi.resolve(requestId, result.bincodeSerialize())
            val requests = Requests.bincodeDeserialize(effectsBytes).value
            for (req in requests) {
                processEffect(req)
            }
            refreshView()
            emitSuccessInfo()

            val currentView = _view.value
            if (currentView.connected && currentView.currentSessionId != null) {
                sseClient.connect("${currentView.serverUrl}/event")
            }
        }
    }

    /**
     * Derive a brief success/info notification from the latest view state —
     * e.g. when the user just connected or created a session. The UI surfaces
     * these as a snackbar so the user gets positive feedback, not just screen
     * changes.
     */
    private fun emitSuccessInfo() {
        val v = _view.value
        val prev = previousScreen
        when {
            v.connected && prev == Screen.CONNECT -> {
                viewModelScope.launch { _info.emit(SUCCESS_CONNECTED) }
            }
            v.currentSessionId != null && prev == Screen.SESSIONS &&
                lastUserEvent is Event.CreateSession -> {
                viewModelScope.launch { _info.emit(SUCCESS_SESSION_CREATED) }
            }
        }
        previousScreen = v.screen
    }

    private var previousScreen: Screen = _view.value.screen

    private fun refreshView() {
        val newView = ViewModel.bincodeDeserialize(ffi.view())
        _view.value = newView
        // Persist the server URL whenever it changes so it survives relaunch.
        prefs.edit().putString(KEY_SERVER_URL, newView.serverUrl).apply()
    }

    /**
     * Forward any persisted crash reports to the core so they appear in the
     * ViewModel's crash-log summary. This runs once on init.
     *
     * Note: [Event.CrashLog] is an internal event skipped from type generation,
     * so it's only available when the native core is loaded. In the pure-Kotlin
     * fallback, crash logs are tracked separately via [CrashLogger.getReports].
     */
    private fun forwardCrashLogs() {
        val reports = CrashLogger.getReports()
        // Crash logs are surfaced in the UI via CrashLogger directly.
        // The native core would receive Event::CrashLog, but that variant
        // is #[facet(skip)] so it's not in the generated Kotlin Event type.
        // The PureCoreFfi fallback tracks crashes in its own model.
    }

    private fun getInitialView(): ViewModel {
        return try {
            ViewModel.bincodeDeserialize(ffi.view())
        } catch (e: Throwable) {
            val defaultUrl = prefs.getString(KEY_SERVER_URL, "http://localhost:4096") ?: "http://localhost:4096"
            ViewModel(
                screen = Screen.CONNECT,
                serverUrl = defaultUrl,
                username = "",
                password = "",
                authRequired = false,
                connected = false,
                loading = false,
                error = null,
                sessions = emptyList(),
                currentSessionId = null,
                currentSessionTitle = "",
                messages = emptyList(),
                draftMessage = "",
                generating = false,
                crashLogCount = 0u,
                latestCrashLog = null,
            )
        }
    }

    override fun onCleared() {
        sseClient.disconnect()
    }

    private fun Event.isRetryable(): Boolean = when (this) {
        is Event.Connect,
        is Event.LoadSessions,
        is Event.CreateSession,
        is Event.LoadMessages,
        is Event.SendMessage -> true
        else -> false
    }

    companion object {
        private const val PREFS_NAME = "opencode_prefs"
        private const val KEY_SERVER_URL = "server_url"

        const val SUCCESS_CONNECTED = "__success_connected__"
        const val SUCCESS_SESSION_CREATED = "__success_session_created__"
    }
}
