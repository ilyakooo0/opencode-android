package soy.iko.opencode

import android.app.Application

class OpencodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)
    }
}
