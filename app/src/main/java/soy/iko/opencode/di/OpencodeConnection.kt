package soy.iko.opencode.di

import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.data.network.HttpClientFactory
import soy.iko.opencode.data.network.OpencodeApiClient
import soy.iko.opencode.data.repo.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * An active connection to one opencode server. Bundles the Ktor client, REST API,
 * SSE event stream, and the reducing repository, all sharing a connection-scoped
 * coroutine scope that is cancelled on [close].
 */
class OpencodeConnection(val profile: ServerProfile) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = HttpClientFactory.create(profile)
    val api = OpencodeApiClient(client)
    val events = EventStreamClient(client, scope)
    val repository = SessionRepository(api, events)

    fun close() {
        scope.cancel()
        client.close()
    }
}
