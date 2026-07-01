package soy.iko.opencode

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
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
