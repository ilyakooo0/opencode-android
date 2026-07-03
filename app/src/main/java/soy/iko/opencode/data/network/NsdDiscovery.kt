package soy.iko.opencode.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A server discovered on the local network via mDNS/DNS-SD. */
data class DiscoveredServer(val name: String, val host: String, val port: Int) {
    val baseUrl: String get() {
        // IPv6 literals (e.g. "fe80::1%wlan0", "2001:db8::1") must be bracketed to form a valid
        // URL authority; a link-local zone id's '%' is percent-encoded to "%25" per RFC 6874
        // ("[fe80::1%25wlan0]") rather than dropped, so link-local hosts stay dialable. IPv4 unchanged.
        val formattedHost = if (host.contains(':')) "[${host.replace("%", "%25")}]" else host
        return "http://$formattedHost:$port"
    }
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
        // Per-name epoch, bumped on every onServiceLost. Each queued resolve is stamped with the
        // epoch that was current when it was enqueued; the resolver discards its result if the
        // epoch has advanced since. This makes loss ordering authoritative: a resolve queued
        // BEFORE a loss — a stale duplicate onServiceFound (stock NsdManager can emit these on
        // multi-homed devices), or a resolve still in flight when the service departs — is
        // dropped, while a resolve queued AFTER a loss (a genuine re-announcement of a restarted
        // server) still inserts. It fixes the phantom a plain "lost-pending" flag missed:
        // onServiceLost removing an already-resolved entry couldn't tell a second, still-queued
        // duplicate resolve to skip re-inserting the departed service — yet it also avoids that
        // flag's converse hazard of leaking a marker that suppresses a later legitimate re-appearance.
        val epoch = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val toResolve = Channel<Pair<NsdServiceInfo, Int>>(Channel.UNLIMITED)

        val resolver = launch {
            for ((info, stampedEpoch) in toResolve) {
                // Key the map by the name from discovery: onServiceFound/onServiceLost both use
                // NsdServiceInfo.serviceName, whereas the resolved info's serviceName can differ —
                // keying by the resolved name would leave a departed server that onServiceLost
                // can't match, so it would linger in the emitted list forever.
                val discoveredName = info.serviceName.orEmpty()
                // NsdManager.resolveService occasionally never invokes its listener. Without a
                // timeout, one stuck resolve would hang this single serialized resolver forever,
                // so every later-discovered service queued behind it is never processed and the
                // emitted list silently stops updating. Abandon a stuck resolve and keep draining.
                val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolveService(nsd, info) }
                // Drop the result if the service was reported lost since this resolve was queued
                // (its epoch advanced), or if the resolve failed/timed out (the service is gone).
                if ((epoch[discoveredName] ?: 0) != stampedEpoch || resolved == null) continue
                val name = resolved.serviceName.orEmpty()
                // Keep only opencode's own advertisements, not every _http._tcp service
                // (printers, routers, other web servers) on the network.
                if (!name.startsWith("opencode", ignoreCase = true)) continue
                @Suppress("DEPRECATION")
                val host = resolved.host?.hostAddress ?: continue
                found[discoveredName] = DiscoveredServer(name = name, host = host, port = resolved.port)
                // Close the check-then-insert race: an onServiceLost firing in the non-suspending
                // window since the check above bumped the epoch but couldn't remove this name from
                // `found` (nothing was inserted yet). Re-check and evict now — the epoch is
                // monotonic, so any loss that raced the insert is still visible here.
                if ((epoch[discoveredName] ?: 0) != stampedEpoch) found.remove(discoveredName)
                trySend(found.values.sortedBy { it.name })
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Stamp the resolve with the name's current epoch so a loss after this point (but
                // before the serialized resolver reaches it) invalidates the result.
                val name = serviceInfo.serviceName.orEmpty()
                toResolve.trySend(serviceInfo to (epoch[name] ?: 0))
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName ?: return
                // Advance the epoch so any resolve queued before now (including a duplicate
                // onServiceFound whose result hasn't landed yet) is discarded and can't resurrect
                // this service, then drop it from the emitted list if it was already resolved.
                epoch.merge(name, 1) { old, _ -> old + 1 }
                if (found.remove(name) != null) {
                    trySend(found.values.sortedBy { it.name })
                }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34 deprecated the one-shot resolveService (which has no cancel API and
                // whose listener leaks on timeout, wedging the platform's serialized slot so the
                // next resolve fails with ALREADY_ACTIVE) in favour of a persistent callback that
                // can be detached. A timed-out (cancelled) resolve unregisters its callback in
                // invokeOnCancellation, and a settled one unregisters itself, so nothing lingers.
                val settled = java.util.concurrent.atomic.AtomicBoolean(false)
                lateinit var callback: NsdManager.ServiceInfoCallback
                callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        if (settled.compareAndSet(false, true) && cont.isActive) cont.resume(null)
                    }
                    override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                        if (settled.compareAndSet(false, true)) {
                            runCatching { nsd.unregisterServiceInfoCallback(callback) }
                            if (cont.isActive) cont.resume(serviceInfo)
                        }
                    }
                    override fun onServiceLost() {
                        if (settled.compareAndSet(false, true)) {
                            runCatching { nsd.unregisterServiceInfoCallback(callback) }
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    override fun onServiceInfoCallbackUnregistered() {}
                }
                cont.invokeOnCancellation {
                    if (settled.compareAndSet(false, true)) {
                        runCatching { nsd.unregisterServiceInfoCallback(callback) }
                    }
                }
                runCatching { nsd.registerServiceInfoCallback(info, { it.run() }, callback) }
                    .onFailure { if (settled.compareAndSet(false, true) && cont.isActive) cont.resume(null) }
            } else {
                // Pre-34: the deprecated one-shot resolveService has no cancel API, so a stuck
                // resolve abandoned by RESOLVE_TIMEOUT_MS can still wedge the platform's single
                // serialized slot; this is a platform limitation we can only bound, not clear.
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
        }

    private companion object {
        const val TAG = "NsdDiscovery"
        // opencode advertises bonjour type "http" → DNS-SD "_http._tcp.".
        const val SERVICE_TYPE = "_http._tcp."
        // Upper bound on a single mDNS resolve before it's abandoned so a stuck platform
        // callback can't wedge the serialized resolver. Local-network resolves are sub-second.
        const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
