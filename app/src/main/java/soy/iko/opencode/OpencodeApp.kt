package soy.iko.opencode

import android.app.Application
import soy.iko.opencode.data.repo.CrashLogger
import soy.iko.opencode.di.AppContainer
import java.util.concurrent.atomic.AtomicBoolean

class OpencodeApp : Application() {
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
