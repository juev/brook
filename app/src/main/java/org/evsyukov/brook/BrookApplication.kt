package org.evsyukov.brook

import android.app.Application
import org.evsyukov.brook.work.NewEntriesNotification

class BrookApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NewEntriesNotification.createChannel(this)
    }
}
