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
open class OpencodeConnection(val profile: ServerProfile) {

    protected constructor() : this(ServerProfile(id = "", label = "", baseUrl = "http://localhost"))

    private val scopeJob = SupervisorJob()
    private val scope by lazy { CoroutineScope(scopeJob + Dispatchers.IO) }
    private val client by lazy { HttpClientFactory.create(profile) }

    open val api: OpencodeApiClient by lazy { OpencodeApiClient(client) }
    open val events: EventStreamClient by lazy { EventStreamClient(client, scope) }
    open val repository: SessionRepository by lazy { SessionRepository(api, events) }

    /**
     * Cancel the coroutine scope and wait for in-flight work (including the SSE reader)
     * to wind down before closing the HTTP client. Calling client.close() while the SSE
     * coroutine is still mid-request produces a spurious "stream error, will retry"
     * warning during normal teardown.
     */
    open suspend fun close() {
        scope.cancel()
        scopeJob.join()
        runCatching { client.close() }
    }
}
