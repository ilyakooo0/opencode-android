package soy.iko.opencode

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.Call
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.network.HttpClientFactory
import soy.iko.opencode.data.repo.CrashLogger
import soy.iko.opencode.di.AppContainer
import java.util.concurrent.atomic.AtomicBoolean

class OpencodeApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    private val shutdownCalled = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        CrashLogger.get(this).install()
        container = AppContainer(this)
        // onTerminate() is only called in emulated process environments, not on real
        // devices. Register a JVM shutdown hook so cleanup (network callback unregister,
        // scope cancellation, connection close) runs when the process is actually killed.
        Runtime.getRuntime().addShutdownHook(Thread {
            shutdown()
        })
    }

    // Process-wide Coil singleton. The defaults are sane; the explicit factory adds a
    // disk cache so scrolling back to an image-bearing message doesn't re-fetch it over
    // the (auth-protected) network every time. Data-URI (base64) images skip the disk
    // cache automatically; only resolved HTTP/S URLs are cached, keyed by URL.
    //
    // Route fetches through a Call.Factory that applies the SAME per-profile certificate
    // pinning as the REST/SSE client (see HttpClientFactory). Server-hosted images carry the
    // server's HTTP Basic auth header (see RemoteImage), so without this they would travel over
    // an UNPINNED TLS connection, silently defeating the pin the user opted into for that host.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(50L * 1024 * 1024)
                .build()
        }
        .callFactory { PinnedImageCallFactory(container) }
        .build()

    override fun onTerminate() {
        super.onTerminate()
        shutdown()
    }

    private fun shutdown() {
        // Guard against double shutdown — onTerminate and the shutdown hook can both
        // fire in emulated environments.
        if (!shutdownCalled.compareAndSet(false, true)) return
        container.shutdown()
    }
}

/**
 * A Coil [Call.Factory] that pins image loads exactly like [HttpClientFactory] pins the
 * REST/SSE channel. The [ImageLoader] is a process singleton built once, but the active
 * [ServerProfile] — and therefore its pins — changes when the user switches servers, so the
 * certificate pinner is resolved *per request* from [AppContainer.activeConnection] rather than
 * baked in at build time. This fails closed: a profile that configures pins always gets a pinned
 * connection for its images (an unparseable pinned host throws, matching HttpClientFactory), so
 * image loads for a pinned host are never silently downgraded to an unpinned channel — and a
 * later server switch is picked up immediately instead of retaining the first profile's pins.
 */
private class PinnedImageCallFactory(private val container: AppContainer) : Call.Factory {
    // A single shared base client; each derived pinned client reuses this one's connection pool
    // and dispatcher via newBuilder(), so per-profile pinning doesn't spin up parallel resources.
    private val base = OkHttpClient()

    // Cache the last derived client keyed by (host, pins) so we don't rebuild it on every request;
    // a server switch changes the signature and triggers a rebuild. Guarded because Coil invokes
    // newCall from arbitrary background threads, possibly concurrently.
    private val lock = Any()
    private var cachedSignature: String? = null
    private var cachedClient: OkHttpClient = base

    override fun newCall(request: Request): Call =
        clientFor(container.activeConnection.value?.profile).newCall(request)

    private fun clientFor(profile: ServerProfile?): OkHttpClient {
        val pins = parsePins(profile?.certPin)
        if (pins.isEmpty()) return base
        // effectiveBaseUrl upgrades http->https when pinning is on, so the pinned host matches the
        // https origin RemoteImage resolves image URLs against. Fail closed on an unparseable host
        // (never fall back to an unpinned client), mirroring HttpClientFactory.create().
        val effectiveUrl = effectiveBaseUrl(profile!!)
        val host = effectiveUrl.toHttpUrlOrNull()?.host
            ?: throw IllegalStateException(
                "Certificate pinning is configured but the server host could not be parsed from $effectiveUrl",
            )
        val signature = "$host\n${pins.sorted().joinToString(" ")}"
        synchronized(lock) {
            if (signature != cachedSignature) {
                val pinner = CertificatePinner.Builder().apply { pins.forEach { add(host, it) } }.build()
                cachedClient = base.newBuilder().certificatePinner(pinner).build()
                cachedSignature = signature
            }
            return cachedClient
        }
    }

    // parsePins / effectiveBaseUrl mirror the private helpers in HttpClientFactory (the source of
    // truth). This file may not edit HttpClientFactory and those helpers aren't exposed, so the
    // pin-parsing and http->https upgrade logic is replicated here and must be kept in sync.
    private fun parsePins(raw: String?): List<String> =
        raw?.split(Regex("[\\s,]+"))?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun effectiveBaseUrl(profile: ServerProfile): String {
        val normalized = HttpClientFactory.normalizeBaseUrl(profile.baseUrl)
        val forceHttps = profile.requireHttps || parsePins(profile.certPin).isNotEmpty()
        return if (forceHttps && normalized.lowercase().startsWith("http://")) {
            "https://" + normalized.substring("http://".length)
        } else {
            normalized
        }
    }
}
