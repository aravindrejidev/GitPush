package com.gitpush.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.gitpush.app.push.PushService
import kotlinx.coroutines.launch

class GitPushApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(PushService.CHANNEL_PROGRESS, "Push progress", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(PushService.CHANNEL_RESULT, "Push results", NotificationManager.IMPORTANCE_DEFAULT),
        )
        container.appScope.launch { container.auth.restore() }
    }
}
