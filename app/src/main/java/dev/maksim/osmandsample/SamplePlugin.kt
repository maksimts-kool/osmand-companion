package dev.maksim.osmandsample

import android.content.Context
import android.content.Intent
import android.graphics.Color
import net.osmand.aidlapi.contextmenu.AContextMenuButton
import net.osmand.aidlapi.contextmenu.ContextMenuButtonsParams
import net.osmand.aidlapi.contextmenu.RemoveContextMenuButtonsParams
import net.osmand.aidlapi.map.ALatLon
import net.osmand.aidlapi.map.SetMapLocationParams
import net.osmand.aidlapi.maplayer.AMapLayer
import net.osmand.aidlapi.maplayer.AddMapLayerParams
import net.osmand.aidlapi.maplayer.RemoveMapLayerParams
import net.osmand.aidlapi.maplayer.point.AMapPoint
import net.osmand.aidlapi.maplayer.point.ShowMapPointParams
import net.osmand.aidlapi.mapmarker.AMapMarker
import net.osmand.aidlapi.mapmarker.AddMapMarkerParams
import net.osmand.aidlapi.mapwidget.AMapWidget
import net.osmand.aidlapi.mapwidget.AddMapWidgetParams
import net.osmand.aidlapi.mapwidget.RemoveMapWidgetParams
import net.osmand.aidlapi.mapwidget.UpdateMapWidgetParams
import net.osmand.aidlapi.navdrawer.NavDrawerItem
import net.osmand.aidlapi.navdrawer.SetNavDrawerItemsParams
import net.osmand.aidlapi.navigation.ANavigationUpdateParams
import net.osmand.aidlapi.navigation.NavigateParams
import net.osmand.aidlapi.navigation.StopNavigationParams

/**
 * Everything this sample adds to OsmAnd. Each public function is one AIDL feature,
 * so this file doubles as a cheat sheet for the real project.
 *
 * Icon names ("ic_action_…", "widget_…") are drawables that live inside OsmAnd, not in this app.
 */
class SamplePlugin(private val context: Context, private val osmand: OsmAndConnection) {

    private val prefs = context.getSharedPreferences("sample_plugin", Context.MODE_PRIVATE)

    /** Remembered so OsmAnd content is restored after OsmAnd restarts (it doesn't persist AIDL content). */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        private set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    private var widgetTaps = 0

    /** Assigned by OsmAnd on the first button registration and reused, so each click is delivered once. */
    private var buttonsCallbackId = -1L
    private var navigationCallbackId = -1L

    data class Place(val id: String, val name: String, val type: String, val lat: Double, val lon: Double)

    val places = listOf(
        Place("old_town", "Tallinn Old Town", "Sight", 59.4372, 24.7453),
        Place("kadriorg", "Kadriorg Palace", "Museum", 59.4381, 24.7910),
        Place("tv_tower", "Tallinn TV Tower", "Viewpoint", 59.4711, 24.8875),
    )

    /** Adds the layer, points, context-menu buttons, widget and drawer item in one go. */
    fun enable() {
        addLayerWithPoints()
        addContextMenuButtons()
        addWidget()
        addNavDrawerItem()
        enabled = true
    }

    fun disable() {
        osmand.call("removeMapLayer") { it.removeMapLayer(RemoveMapLayerParams(LAYER_ID)) }
        contextButtons.keys.forEach { id ->
            osmand.call("removeContextMenuButtons $id") {
                it.removeContextMenuButtons(RemoveContextMenuButtonsParams(id, buttonsCallbackId))
            }
        }
        buttonsCallbackId = -1L
        osmand.call("removeMapWidget") { it.removeMapWidget(RemoveMapWidgetParams(WIDGET_ID)) }
        osmand.call("setNavDrawerItems(empty)") {
            it.setNavDrawerItems(SetNavDrawerItemsParams(context.packageName, emptyList()))
        }
        enabled = false
    }

    fun addLayerWithPoints() {
        // imagePoints=false: classic colored circles with shortName inside.
        // true switches to image pins (set AMapPoint.POINT_IMAGE_URI_PARAM for your own picture).
        val layer = AMapLayer(LAYER_ID, "Sample plugin places", 5.5f, places.map(::toMapPoint))
            .apply { setImagePoints(false) }
        osmand.call("addMapLayer") { it.addMapLayer(AddMapLayerParams(layer)) }
    }

    /** Centers OsmAnd on one of our points and opens its context menu (with our buttons). */
    fun showPoint(place: Place) = osmand.call("showMapPoint ${place.name}") {
        it.showMapPoint(ShowMapPointParams(LAYER_ID, toMapPoint(place)))
    }

    private fun toMapPoint(place: Place) = AMapPoint(
        /* id = */ place.id,
        /* shortName = */ place.name.take(1), // drawn inside the circle on the map
        /* fullName = */ place.name,          // context menu title
        /* typeName = */ place.type,          // context menu subtitle
        /* layerId = */ LAYER_ID,
        /* color = */ Color.rgb(0x1E, 0x88, 0xE5),
        /* location = */ ALatLon(place.lat, place.lon),
        /* details = */ listOf("Added by ${context.getString(R.string.app_name)}"),
        /* params = */ mapOf(AMapPoint.POINT_TYPE_ICON_NAME_PARAM to "ic_action_info_dark"),
    )

    /**
     * Buttons shown in OsmAnd's context menu when the user taps one of our points.
     * A registration takes a left/right pair, but OsmAnd 5.4 only draws the right one,
     * so each button gets its own registration (= its own row).
     */
    fun addContextMenuButtons() {
        contextButtons.forEach { (id, button) ->
            val params = ContextMenuButtonsParams(
                null, button, id, context.packageName, LAYER_ID, buttonsCallbackId,
                emptyList(), // empty = every point of LAYER_ID
            )
            // An unknown callbackId makes OsmAnd register a new callback and return its id.
            osmand.call("addContextMenuButtons $id") { it.addContextMenuButtons(params, osmand.callback) }
                ?.takeIf { it >= 0 }
                ?.let { buttonsCallbackId = it }
        }
    }

    private val contextButtons = mapOf(
        "$BUTTONS_ID.marker" to AContextMenuButton(BUTTON_MARKER, "Add marker here", "", "ic_action_flag", "", true, true),
        "$BUTTONS_ID.route" to AContextMenuButton(BUTTON_ROUTE, "Route here", "", "ic_action_start_navigation", "", true, true),
    )

    fun addWidget() = osmand.call("addMapWidget") {
        it.addMapWidget(AddMapWidgetParams(buildWidget()))
    }

    /** Shows how to push live data into an existing widget (e.g. a sensor value in the real project). */
    fun bumpWidget() {
        widgetTaps++
        osmand.call("updateMapWidget") { it.updateMapWidget(UpdateMapWidgetParams(buildWidget())) }
    }

    private fun buildWidget() = AMapWidget(
        /* id = */ WIDGET_ID,
        /* menuIconName = */ "ic_action_info_dark",
        /* menuTitle = */ "Sample plugin",       // name in Configure screen → widgets list
        /* lightIconName = */ "widget_time_day",
        /* darkIconName = */ "widget_time_night",
        /* text = */ widgetTaps.toString(),
        /* description = */ "taps",
        /* order = */ 100,
        /* intentOnClick = */ Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    /** Adds an entry to OsmAnd's main menu that opens this app through the deep link in the manifest. */
    fun addNavDrawerItem() = osmand.call("setNavDrawerItems") {
        val item = NavDrawerItem(context.getString(R.string.app_name), DEEP_LINK, "ic_action_travel", -1)
        it.setNavDrawerItems(SetNavDrawerItemsParams(context.packageName, listOf(item)))
    }

    /** Adds an OsmAnd map marker (the flag list under Menu → Map markers). */
    fun addMarker(place: Place) = osmand.call("addMapMarker ${place.name}") {
        it.addMapMarker(AddMapMarkerParams(AMapMarker(ALatLon(place.lat, place.lon), place.name)))
    }

    /** Builds a car route to [place] from another sample place, so it works without GPS. */
    fun routeTo(place: Place) = osmand.call("navigate to ${place.name}") {
        val start = places.first { it != place }
        it.navigate(
            NavigateParams(start.name, start.lat, start.lon, place.name, place.lat, place.lon, "car", true, false),
        )
    }

    fun stopNavigation() = osmand.call("stopNavigation") { it.stopNavigation(StopNavigationParams()) }

    fun showTallinn() = osmand.call("setMapLocation") {
        it.setMapLocation(SetMapLocationParams(59.4450, 24.8000, 12, 0f, true))
    }

    /** Streams "next turn in N meters" into the log while OsmAnd is navigating. */
    fun setNavigationUpdates(subscribe: Boolean) {
        val params = ANavigationUpdateParams().apply {
            setSubscribeToUpdates(subscribe)
            setCallbackId(navigationCallbackId)
        }
        osmand.call(if (subscribe) "subscribe navigation" else "unsubscribe navigation") {
            it.registerForNavigationUpdates(params, osmand.callback)
        }?.let { id -> navigationCallbackId = if (subscribe) id else -1L }
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        const val LAYER_ID = "dev.maksim.osmandsample.layer"
        const val WIDGET_ID = "dev.maksim.osmandsample.widget"
        const val BUTTONS_ID = "dev.maksim.osmandsample.buttons"
        const val BUTTON_MARKER = 1
        const val BUTTON_ROUTE = 2
        const val DEEP_LINK = "osmand-sample://open"
    }
}
