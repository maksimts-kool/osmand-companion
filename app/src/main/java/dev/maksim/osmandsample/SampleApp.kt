package dev.maksim.osmandsample

import android.app.Application

/**
 * Holds the OsmAnd connection for the whole process, so it survives activity recreation and
 * keeps delivering callbacks (context-menu clicks, navigation updates) while OsmAnd is in front.
 */
class SampleApp : Application() {

    lateinit var osmand: OsmAndConnection
        private set
    lateinit var plugin: SamplePlugin
        private set

    override fun onCreate() {
        super.onCreate()
        osmand = OsmAndConnection(this)
        plugin = SamplePlugin(this, osmand)

        // OsmAnd drops everything we added when it restarts, so re-apply on every (re)connect.
        osmand.onConnected = { if (plugin.enabled) plugin.enable() }

        osmand.addListener(object : OsmAndConnection.Listener {
            override fun onConnectionChanged(connected: Boolean) {}
            override fun onLog(message: String) {}

            // Runs while OsmAnd is in front. React through the API rather than with a Toast:
            // Android suppresses background toasts unless the app may post notifications.
            override fun onContextMenuButtonClicked(buttonId: Int, pointId: String?, layerId: String?) {
                osmand.log("Context button $buttonId clicked on point $pointId")
                val place = plugin.places.firstOrNull { it.id == pointId } ?: return
                when (buttonId) {
                    SamplePlugin.BUTTON_MARKER -> plugin.addMarker(place)
                    SamplePlugin.BUTTON_ROUTE -> plugin.routeTo(place)
                }
            }
        })

        osmand.connect()
    }
}
