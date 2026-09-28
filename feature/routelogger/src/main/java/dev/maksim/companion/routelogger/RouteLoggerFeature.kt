package dev.maksim.companion.routelogger

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import dev.maksim.companion.core.AppLog
import dev.maksim.companion.core.BackgroundFeature
import dev.maksim.companion.core.OsmAndConnection
import java.util.concurrent.TimeUnit

/** Trip summaries: while on, checks OsmAnd's saved tracks every [POLL_INTERVAL_MS] (see [TrackWatcher]). */
class RouteLoggerFeature(context: Context, private val osmand: OsmAndConnection) : BackgroundFeature {

    val settings = RouteLoggerSettings(context)
    val watcher = TrackWatcher(context, osmand, settings)

    override val title: String = context.getString(R.string.rl_feature_title)
    override val isEnabled: Boolean get() = settings.watching

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val tick = object : Runnable {
        override fun run() {
            try {
                watcher.poll()
            } catch (e: RuntimeException) {
                // e.g. a parcel from a newer OsmAnd we can't read; keep watching rather than crash.
                AppLog.log("Check failed: $e")
            }
            handler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /** Check right away on every (re)connect: a track may have been saved while OsmAnd was gone. */
    private val onAccessGranted = OsmAndConnection.AccessListener { pollNow() }

    override fun start() {
        val thread = HandlerThread("TrackWatcher").also { it.start() }
        this.thread = thread
        handler = Handler(thread.looper)
        osmand.addAccessListener(onAccessGranted)
        pollNow()
        AppLog.log("Watching for finished trip recordings")
    }

    override fun stop() {
        osmand.removeAccessListener(onAccessGranted)
        handler = null
        thread?.quitSafely()
        thread = null
        AppLog.log("Stopped watching trip recordings")
    }

    private fun pollNow() {
        handler?.run {
            removeCallbacks(tick)
            post(tick)
        }
    }

    private companion object {
        val POLL_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30)
    }
}
