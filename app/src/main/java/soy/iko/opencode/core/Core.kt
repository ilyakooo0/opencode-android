package soy.iko.opencode.core

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The bridge between the Crux core (Rust or pure-Kotlin fallback) and the
 * Jetpack Compose UI.
 *
 * - Sends [Event]s to the core via [update]
 * - Processes [Effect]s returned by the core (Render, Http)
 * - Exposes the current [ViewModel] as a [StateFlow] for Compose to observe
 */
class Core : androidx.lifecycle.ViewModel() {
    private val ffi: CoreFfi = CoreFfi.create()
    private val httpClient = HttpClient()
    private val sseClient = SseClient()

    private val _view = MutableStateFlow(getInitialView())
    val view: StateFlow<ViewModel> = _view.asStateFlow()

    init {
        // Emit Start event to initialize the model
        update(Event.Start)

        // Subscribe to SSE events
        viewModelScope.launch {
            sseClient.events.collect { data ->
                update(Event.EventReceived(data))
            }
        }
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
            // Refresh the view after resolving
            _view.value = ViewModel.bincodeDeserialize(ffi.view())

            // Connect SSE when we have a server URL and are connected
            val currentView = _view.value
            if (currentView.connected && currentView.currentSessionId != null) {
                sseClient.connect("${currentView.serverUrl}/event")
            }
        }
    }

    private fun getInitialView(): ViewModel {
        return try {
            ViewModel.bincodeDeserialize(ffi.view())
        } catch (e: Throwable) {
            ViewModel(
                screen = Screen.CONNECT,
                serverUrl = "http://localhost:4096",
                connected = false,
                loading = false,
                error = null,
                sessions = emptyList(),
                currentSessionId = null,
                messages = emptyList(),
                draftMessage = "",
            )
        }
    }

    override fun onCleared() {
        sseClient.disconnect()
    }
}
