package com.mihad.live

import android.app.Application
import com.mihad.live.engine.Notifications

class MihadLiveApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
