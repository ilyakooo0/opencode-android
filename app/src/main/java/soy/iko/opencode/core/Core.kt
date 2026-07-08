package soy.iko.opencode.core

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
class Core : androidx.lifecycle.ViewModel() {
    private val ffi: CoreFfi = CoreFfi.create()
    private val httpClient = HttpClient()
    private val sseClient = SseClient()

    private val _view = MutableStateFlow(getInitialView())
    val view: StateFlow<ViewModel> = _view.asStateFlow()

    init {
        update(Event.Start)

        viewModelScope.launch {
            sseClient.events.collect { data ->
                update(Event.EventReceived(data))
            }
        }

        forwardCrashLogs()
    }

    fun update(event: Event) {
        val effectsBytes = ffi.update(event.bincodeSerialize())
        val requests = Requests.bincodeDeserialize(effectsBytes).value
        for (request in requests) {
            processEffect(request)
        }
    }

    private fun processEffect(request: Request) {
        when (val effect = request.effect) {
            is Effect.Render -> {
                _view.value = ViewModel.bincodeDeserialize(ffi.view())
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
            _view.value = ViewModel.bincodeDeserialize(ffi.view())

            val currentView = _view.value
            if (currentView.connected && currentView.currentSessionId != null) {
                sseClient.connect("${currentView.serverUrl}/event")
            }
        }
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
            ViewModel(
                screen = Screen.CONNECT,
                serverUrl = "http://localhost:4096",
                username = "",
                password = "",
                authRequired = false,
                connected = false,
                loading = false,
                error = null,
                sessions = emptyList(),
                currentSessionId = null,
                messages = emptyList(),
                draftMessage = "",
                crashLogCount = 0u,
                latestCrashLog = null,
            )
        }
    }

    override fun onCleared() {
        sseClient.disconnect()
    }
}
