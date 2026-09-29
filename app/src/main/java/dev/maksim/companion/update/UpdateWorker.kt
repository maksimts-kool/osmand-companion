package dev.maksim.companion.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.maksim.companion.R
import dev.maksim.companion.core.canPostNotifications
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The daily update check. Posts a notification once per new version. */
class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val release = try {
            Updater.checkNow()
        } catch (_: IOException) {
            return Result.retry()
        }
        if (release != null && applicationContext.canPostNotifications() && Updater.shouldNotify(release)) {
            notify(release)
        }
        return Result.success()
    }

    private fun notify(release: Release) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.putExtra(EXTRA_SHOW_UPDATE, true)
            ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(dev.maksim.companion.core.R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_notification_title, release.version))
            .setContentText(context.getString(R.string.update_notification_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notification permission revoked between the check and here.
        }
    }

    companion object {
        /** On the launch intent: open the update dialog. */
        const val EXTRA_SHOW_UPDATE = "dev.maksim.companion.SHOW_UPDATE"

        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 100
        private const val WORK_NAME = "update-check"

        /** Idempotent: the app calls it on every start. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
