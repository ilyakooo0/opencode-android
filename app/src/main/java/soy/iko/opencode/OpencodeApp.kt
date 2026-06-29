package soy.iko.opencode

import android.app.Application
import soy.iko.opencode.data.repo.CrashLogger
import soy.iko.opencode.di.AppContainer

class OpencodeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLogger.get(this).install()
        container = AppContainer(this)
    }
}
