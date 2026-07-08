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

        // On a fresh process after a configuration change, resume the SSE stream if
        // the core is already connected (no-op otherwise — it guards on `connected`).
        core.resumeStreamingIfConnected()

        // Persist connection details once a connection succeeds.
        viewModelScope.launch {
            core.view.distinctUntilChangedBy { it.connected to it.serverUrl }.collect { state ->
                if (state.connected) {
                    prefs.edit {
                        putString(KEY_URL, state.serverUrl)
                        putString(KEY_USER, state.username)
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
    }
}
