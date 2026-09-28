package dev.maksim.routelogger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

/**
 * Keeps this process (and so the OsmAnd connection) alive and polls for finished recordings.
 * Android kills background apps freely; a foreground service with a quiet notification is the
 * only reliable way to still be around when you tap Finish in OsmAnd.
 */
class WatcherService : Service() {

    private val app get() = application as RouteLoggerApp
    private val thread = HandlerThread("TrackWatcher")
    private lateinit var handler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            // Retry the bind if OsmAnd wasn't installed or refused it earlier.
            if (!app.osmand.isConnected) mainHandler.post { app.osmand.connect() }
            try {
                app.watcher.poll()
            } catch (e: RuntimeException) {
                // e.g. a parcel from a newer OsmAnd we can't read; keep watching rather than crash.
                AppLog.log("Check failed: $e")
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread.start()
        handler = Handler(thread.looper)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        running = this
        AppLog.log("Watching for finished trip recordings")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        app.osmand.connect()
        pollNow()
        return START_STICKY
    }

    /** Also called when OsmAnd (re)connects, so a track saved while we were disconnected is caught at once. */
    fun pollNow() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onDestroy() {
        running = null
        thread.quitSafely()
        AppLog.log("Stopped watching")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification() = NotificationCompat.Builder(this, createChannel())
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun createChannel(): String {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_MIN,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return CHANNEL_ID
    }

    companion object {
        private const val CHANNEL_ID = "watcher"
        private const val NOTIFICATION_ID = 1
        private val POLL_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30)

        /** The live instance, if any. Only touched on the main thread. */
        var running: WatcherService? = null
            private set

        /** Only from the foreground (activity) or a boot broadcast: Android 12+ forbids it from the background. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WatcherService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatcherService::class.java))
        }
    }
}
