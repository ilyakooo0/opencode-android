package soy.iko.opencode.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** A server discovered on the local network via mDNS/DNS-SD. */
data class DiscoveredServer(val name: String, val host: String, val port: Int) {
    val baseUrl: String get() = "http://$host:$port"
}

/**
 * Discovers opencode servers advertised on the LAN. opencode's `--mdns` publishes a DNS-SD
 * `_http._tcp` service named `opencode-<port>` (with a `path=/` TXT record), so we browse
 * that service type and keep only names that look like opencode. Requires no extra
 * permission — [NsdManager] manages the multicast lock internally.
 */
open class NsdDiscovery(context: Context?) {

    protected constructor() : this(null)

    private val appContext = context?.applicationContext

    /**
     * A cold [Flow] that starts a discovery when collected and stops it when the collector
     * cancels. Emits the growing/shrinking set of discovered servers. Resolutions are
     * serialized through a channel because [NsdManager.resolveService] can only process one
     * at a time on older platforms (a concurrent resolve fails with ALREADY_ACTIVE).
     */
    open fun discover(): Flow<List<DiscoveredServer>> = callbackFlow {
        val nsd = appContext?.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        // Thread-safe: mutated by the resolver coroutine and the (main-thread) lost callback.
        val found = java.util.concurrent.ConcurrentHashMap<String, DiscoveredServer>()
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        val resolver = launch {
            for (info in toResolve) {
                val resolved = resolveService(nsd, info) ?: continue
                val name = resolved.serviceName.orEmpty()
                // Keep only opencode's own advertisements, not every _http._tcp service
                // (printers, routers, other web servers) on the network.
                if (!name.startsWith("opencode", ignoreCase = true)) continue
                @Suppress("DEPRECATION")
                val host = resolved.host?.hostAddress ?: continue
                found[name] = DiscoveredServer(name = name, host = host, port = resolved.port)
                trySend(found.values.sortedBy { it.name })
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                toResolve.trySend(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName ?: return
                if (found.remove(name) != null) trySend(found.values.sortedBy { it.name })
            }
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "mDNS discovery start failed: $errorCode")
                close()
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
        }

        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { close(it) }

        awaitClose {
            resolver.cancel()
            toResolve.close()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    private suspend fun resolveService(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(serviceInfo)
                }
            }
            runCatching { @Suppress("DEPRECATION") nsd.resolveService(info, listener) }
                .onFailure { if (cont.isActive) cont.resume(null) }
        }

    private companion object {
        const val TAG = "NsdDiscovery"
        // opencode advertises bonjour type "http" → DNS-SD "_http._tcp.".
        const val SERVICE_TYPE = "_http._tcp."
    }
}
