package com.sajat.hangasszisztens

import android.app.Application

class NeoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FloatingAssistantService.installCrashHandler(this)
    }
}
