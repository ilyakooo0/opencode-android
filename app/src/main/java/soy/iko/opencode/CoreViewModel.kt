package soy.iko.opencode

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import soy.iko.opencode.core.Event
import soy.iko.opencode.core.OpencodeCore

/**
 * Lifecycle-scoped host for the [OpencodeCore] so a single core (and its SSE
 * connection) survives configuration changes. It also persists the last server
 * URL / username so reconnecting is one tap.
 */
class CoreViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("opencode", Context.MODE_PRIVATE)

    val core = OpencodeCore(viewModelScope)

    init {
        prefs.getString(KEY_URL, null)?.let { core.dispatch(Event.ServerUrlChanged(it)) }
        prefs.getString(KEY_USER, null)?.let { core.dispatch(Event.UsernameChanged(it)) }
        // The password is stored in plain text in SharedPreferences — a deliberate
        // convenience trade-off for a local dev tool so reconnecting is one tap.
        prefs.getString(KEY_PASSWORD, null)?.let { core.dispatch(Event.PasswordChanged(it)) }

        // On a fresh process after a configuration change, resume the SSE stream if
        // the core is already connected (no-op otherwise — it guards on `connected`).
        core.resumeStreamingIfConnected()

        // Returning users shouldn't have to tap Connect: if a URL was restored and we
        // aren't already connected, kick off the connection attempt on launch. The core
        // handles an empty/invalid URL, so this only fires when there's something to try.
        core.view.value.let { restored ->
            if (restored.serverUrl.isNotEmpty() && !restored.connected) {
                core.dispatch(Event.Connect)
            }
        }

        // Persist connection details once a connection succeeds.
        viewModelScope.launch {
            core.view.distinctUntilChangedBy { it.connected to it.serverUrl }.collect { state ->
                if (state.connected) {
                    prefs.edit {
                        putString(KEY_URL, state.serverUrl)
                        putString(KEY_USER, state.username)
                        putString(KEY_PASSWORD, state.password)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        core.shutdown()
    }

    private companion object {
        const val KEY_URL = "serverUrl"
        const val KEY_USER = "username"
        const val KEY_PASSWORD = "password"
    }
}
