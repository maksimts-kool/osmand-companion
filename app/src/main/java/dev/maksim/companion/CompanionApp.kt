package dev.maksim.companion

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.maksim.companion.core.Analytics
import dev.maksim.companion.core.BackgroundFeature
import dev.maksim.companion.core.CompanionHost
import dev.maksim.companion.core.OsmAndConnection
import dev.maksim.companion.planner.TripFeature
import dev.maksim.companion.timetable.TimetableFeature
import dev.maksim.companion.update.Updater

/** Holds the OsmAnd connection and the features for the whole process, shared by the UI and the service. */
class CompanionApp : Application(), CompanionHost, Application.ActivityLifecycleCallbacks {

    override lateinit var osmand: OsmAndConnection
        private set
    override lateinit var features: List<BackgroundFeature>
        private set

    override fun onCreate() {
        super.onCreate()
        Analytics.init(this, Analytics.Keys(BuildConfig.SENTRY_DSN, BuildConfig.VERSION_NAME, BuildConfig.DEBUG))
        osmand = OsmAndConnection(this)
        features = listOf(
            TimetableFeature(this, osmand),
            TripFeature(this, osmand),
        )
        // Not connected to OsmAnd yet: that starts OsmAnd if it isn't running, and keeps it running while connected,
        // and this process also starts for things that don't need it (the OsmAnd watcher on boot, the daily update
        // check, OsmAnd itself asking for a stop's icon). The screens connect when they open; the service when it
        // starts; the watcher when OsmAnd opens.
        registerActivityLifecycleCallbacks(this)
        Updater.init(this)
    }

    private var startedScreens = 0

    override val hasScreenOpen: Boolean get() = startedScreens > 0

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        osmand.connect()
    }

    override fun onActivityStarted(activity: Activity) {
        startedScreens++
    }

    override fun onActivityStopped(activity: Activity) {
        startedScreens--
    }

    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
