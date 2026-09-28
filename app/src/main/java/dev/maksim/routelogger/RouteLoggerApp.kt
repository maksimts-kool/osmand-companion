package dev.maksim.routelogger

import android.app.Application

/** Holds the OsmAnd connection and the watcher for the whole process, shared by the UI and [WatcherService]. */
class RouteLoggerApp : Application() {

    lateinit var settings: Settings
        private set
    lateinit var osmand: OsmAndConnection
        private set
    lateinit var watcher: TrackWatcher
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        osmand = OsmAndConnection(this)
        watcher = TrackWatcher(this, osmand, settings)

        // Check right away on every (re)connect: a track may have been saved while OsmAnd was gone.
        osmand.onAccessGranted = { WatcherService.running?.pollNow() }
        osmand.connect()
    }
}
