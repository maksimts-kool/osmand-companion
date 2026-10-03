package dev.maksim.companion.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

/**
 * Keeps this process (and so the OsmAnd connection) alive while any [BackgroundFeature] is on, and runs
 * those features. Android kills background apps freely; a foreground service with a quiet notification is
 * the only reliable way to still be around when you tap Finish in OsmAnd, or tap a stop on its map.
 */
class CompanionService : Service() {

    private val host get() = application as CompanionHost
    private val handler = Handler(Looper.getMainLooper())
    private val running = mutableSetOf<BackgroundFeature>()

    /** Retries the bind if OsmAnd wasn't installed, and notices when the user enables us in OsmAnd → Plugins. */
    private val keepConnected = object : Runnable {
        override fun run() {
            val osmand = host.osmand
            if (!osmand.isConnected) osmand.connect() else if (!osmand.hasAccess) osmand.checkAccess()
            handler.postDelayed(this, CONNECTION_CHECK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        handler.post(keepConnected)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sync()
        return START_STICKY
    }

    /** Runs exactly the features that are on, and says so in the notification. Main thread. */
    private fun sync() {
        val enabled = host.features.filter { it.isEnabled }
        // Always go foreground first: startForegroundService() demands it even if we stop right away.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(enabled),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        (running - enabled.toSet()).forEach {
            it.stop()
            running -= it
        }
        (enabled - running).forEach {
            it.start()
            running += it
        }
        if (enabled.isEmpty()) stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        running.forEach { it.stop() }
        running.clear()
        handler.removeCallbacks(keepConnected)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(features: List<BackgroundFeature>) = NotificationCompat.Builder(this, createChannel())
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.core_notification_title))
        .setContentText(features.joinToString(" · ") { it.title })
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
            },
        )
        .build()

    private fun createChannel(): String {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.core_notification_channel), NotificationManager.IMPORTANCE_MIN,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return CHANNEL_ID
    }

    companion object {
        private const val CHANNEL_ID = "watcher"
        private const val NOTIFICATION_ID = 1
        private val CONNECTION_CHECK_MS = TimeUnit.SECONDS.toMillis(15)

        /** The running service, if it is. */
        @Volatile
        private var instance: CompanionService? = null

        /**
         * Starts, updates or stops the service to match which features are on, and, with [FollowOsmAnd], whether
         * OsmAnd is in use. Call it after turning one on or off, from any thread. Starting it only works from the
         * foreground (activity), a boot broadcast or [OsmAndWatcher]: Android 12+ forbids that from the background.
         * Updating it once it runs, or stopping it, works from anywhere.
         */
        fun update(context: Context) {
            val intent = Intent(context, CompanionService::class.java)
            val running = instance
            when {
                !FollowOsmAnd.wantsService(context) -> context.stopService(intent)
                running != null -> running.handler.post { if (instance === running) running.sync() }
                else -> ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
