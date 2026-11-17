package com.gitpush.app.push

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gitpush.app.GitPushApp
import com.gitpush.app.MainActivity
import com.gitpush.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service whose only job is to keep the process alive while a push runs
 * and to surface progress in the notification shade. The work itself lives in [PushCoordinator].
 */
class PushService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val coordinator = (application as GitPushApp).container.coordinator
        if (intent?.action == ACTION_CANCEL) {
            coordinator.cancel()
            return START_NOT_STICKY
        }
        startInForeground(progressNotification("Preparing…", 0, 0))
        if (job?.isActive == true) return START_NOT_STICKY
        job = scope.launch {
            coordinator.state.collect { st ->
                val nm = getSystemService(NotificationManager::class.java)
                when (st) {
                    is PushState.Working -> nm.notify(ID_PROGRESS, progressNotification(
                        "${if (st.push) "Pushing" else "Reviewing"} ${st.repo} · ${st.phase.label}", st.done, st.total,
                    ))
                    is PushState.Finished -> {
                        val text = if (st.result.upToDate) "Already up to date" else "Pushed ${st.result.commitSha?.take(7).orEmpty()} to ${st.spec.fullName}"
                        nm.notify(ID_RESULT, resultNotification("Push complete", text))
                        finish()
                    }
                    is PushState.Failed -> {
                        nm.notify(ID_RESULT, resultNotification("Push failed", st.message.lines().first()))
                        finish()
                    }
                    is PushState.Planned, PushState.Idle -> finish()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID_PROGRESS, n)
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(text: String, done: Long, total: Long): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, PushService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("GitPush")
            .setContentText(text)
            .setContentIntent(openApp())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "Cancel", cancel)
        if (total > 0) b.setProgress(1000, ((done.toDouble() / total) * 1000).toInt().coerceIn(0, 1000), false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun resultNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_PROGRESS = "push_progress"
        const val CHANNEL_RESULT = "push_result"
        private const val ID_PROGRESS = 1001
        private const val ID_RESULT = 1002
        private const val ACTION_CANCEL = "com.gitpush.app.CANCEL"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PushService::class.java))
            } catch (_: Exception) {
                // Work continues without the notification if the system refuses the service.
            }
        }
    }
}
