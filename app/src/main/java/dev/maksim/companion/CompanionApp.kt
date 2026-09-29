package dev.maksim.companion

import android.app.Application
import dev.maksim.companion.core.BackgroundFeature
import dev.maksim.companion.core.CompanionHost
import dev.maksim.companion.core.OsmAndConnection
import dev.maksim.companion.timetable.TimetableFeature
import dev.maksim.companion.update.Updater

/** Holds the OsmAnd connection and the features for the whole process, shared by the UI and the service. */
class CompanionApp : Application(), CompanionHost {

    override lateinit var osmand: OsmAndConnection
        private set
    override lateinit var features: List<BackgroundFeature>
        private set

    override fun onCreate() {
        super.onCreate()
        osmand = OsmAndConnection(this)
        features = listOf(
            TimetableFeature(this, osmand),
        )
        osmand.connect()
        Updater.init(this)
    }
}
