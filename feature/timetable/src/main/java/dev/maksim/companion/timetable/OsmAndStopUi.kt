package dev.maksim.companion.timetable

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.LocaleList
import android.view.KeyEvent
import dev.maksim.companion.core.OsmAndConnection
import net.osmand.aidlapi.IOsmAndAidlCallback
import net.osmand.aidlapi.contextmenu.AContextMenuButton
import net.osmand.aidlapi.contextmenu.ContextMenuButtonsParams
import net.osmand.aidlapi.contextmenu.RemoveContextMenuButtonsParams
import net.osmand.aidlapi.customization.PreferenceParams
import net.osmand.aidlapi.gpx.AGpxBitmap
import net.osmand.aidlapi.logcat.OnLogcatMessageParams
import net.osmand.aidlapi.map.ALatLon
import net.osmand.aidlapi.maplayer.AMapLayer
import net.osmand.aidlapi.maplayer.AddMapLayerParams
import net.osmand.aidlapi.maplayer.RemoveMapLayerParams
import net.osmand.aidlapi.maplayer.UpdateMapLayerParams
import net.osmand.aidlapi.maplayer.point.AMapPoint
import net.osmand.aidlapi.maplayer.point.RemoveMapPointParams
import net.osmand.aidlapi.maplayer.point.ShowMapPointParams
import net.osmand.aidlapi.maplayer.point.UpdateMapPointParams
import net.osmand.aidlapi.mapwidget.AMapWidget
import net.osmand.aidlapi.mapwidget.AddMapWidgetParams
import net.osmand.aidlapi.mapwidget.RemoveMapWidgetParams
import net.osmand.aidlapi.mapwidget.UpdateMapWidgetParams
import net.osmand.aidlapi.navdrawer.NavDrawerItem
import net.osmand.aidlapi.navdrawer.SetNavDrawerItemsParams
import net.osmand.aidlapi.navigation.ADirectionInfo
import net.osmand.aidlapi.navigation.OnVoiceNavigationParams
import net.osmand.aidlapi.search.SearchResult
import java.util.Locale

/**
 * What the timetable feature adds to OsmAnd's own screens. Apps can't extend OsmAnd's built-in transport
 * stops, so we bring our own stops, which open OsmAnd's usual context menu:
 *
 *  - a map layer with the stops around the map center (dots from zoom 13, vehicle pins from 15; Configure map
 *    has a switch for it). Tapping a stop shows its name, which way it goes, and when that was loaded;
 *  - three buttons in that menu: "Next departures" opens the live next departures, and "Full day" the rest of
 *    today by route, each in a sheet over the map ([DaySheetActivity]); "Show in Companion" opens the stop's full
 *    timetable in this app;
 *  - a "Next departure" widget (Configure screen → widgets) for the stop you last pressed a button on, or the one
 *    nearest the map center. Tapping it opens the stop's full timetable in this app;
 *  - a "Transit timetables" item in OsmAnd's main menu that opens the stop search here.
 *
 * All of it is in OsmAnd's language ([strings]), which can differ from this app's.
 *
 * OsmAnd keeps layers, widgets and buttons only in memory, so [register] runs again after every reconnect, and
 * when OsmAnd's language changes ([languageChanged]). Call everything from the feature's worker thread.
 */
class OsmAndStopUi(private val context: Context, private val osmand: OsmAndConnection) {

    /** A button in a stop's menu was pressed. Runs on a binder thread. */
    var onButton: ((button: Int, stopId: String) -> Unit)? = null

    /** OsmAnd's id for each registered button row, by our row id; needed to remove them. */
    private val buttonCallbackIds = HashMap<String, Long>()
    private val shown = HashMap<String, AMapPoint>()
    private var widgetText: String? = null
    private var widgetStopId: String? = null

    /** OsmAnd's language as of [register]. */
    var locales: LocaleList = LocaleList.getDefault()
        private set

    /** For text shown in OsmAnd: this app's strings in OsmAnd's language. */
    var strings: Context = context
        private set

    private val callback = object : IOsmAndAidlCallback.Stub() {
        override fun onContextMenuButtonClicked(buttonId: Int, pointId: String?, layerId: String?) {
            if (layerId == LAYER_ID && pointId != null) onButton?.invoke(buttonId, pointId)
        }

        override fun onSearchComplete(resultSet: MutableList<SearchResult>?) {}
        override fun onUpdate() {}
        override fun onAppInitialized() {}
        override fun onGpxBitmapCreated(bitmap: AGpxBitmap?) {}
        override fun updateNavigationInfo(directionInfo: ADirectionInfo?) {}
        override fun onVoiceRouterNotify(params: OnVoiceNavigationParams?) {}
        override fun onKeyEvent(event: KeyEvent?) {}
        override fun onLogcatMessage(params: OnLogcatMessageParams?) {}
    }

    /** Adds the layer, menu buttons, widget and main menu item. False if OsmAnd didn't take the layer. */
    fun register(): Boolean {
        shown.clear()
        buttonCallbackIds.clear()
        widgetText = null
        widgetStopId = null
        osmandLocales()?.let {
            locales = it
            strings = localized(context, it)
        }
        val added = osmand.call("addMapLayer") { it.addMapLayer(AddMapLayerParams(layer(emptyList()))) } == true
        if (!added) return false
        for (row in buttonRows()) {
            osmand.call("addContextMenuButtons") { it.addContextMenuButtons(row, callback) }
                ?.let { buttonCallbackIds[row.id] = it }
        }
        osmand.call("addMapWidget") { it.addMapWidget(AddMapWidgetParams(widget(NO_VALUE, null))) }
        // NEW_TASK: without it, Android 15+ won't bring this app's task to the front from OsmAnd's.
        val item = NavDrawerItem(
            strings.getString(R.string.tt_drawer_item), DEEP_LINK, Mode.BUS.osmandIcon, Intent.FLAG_ACTIVITY_NEW_TASK,
        )
        osmand.call("setNavDrawerItems") {
            it.setNavDrawerItems(SetNavDrawerItemsParams(osmand.appPackage, listOf(item)))
        }
        return true
    }

    fun unregister() {
        osmand.call("removeMapLayer") { it.removeMapLayer(RemoveMapLayerParams(LAYER_ID)) }
        for ((rowId, callbackId) in buttonCallbackIds) {
            osmand.call("removeContextMenuButtons") {
                it.removeContextMenuButtons(RemoveContextMenuButtonsParams(rowId, callbackId))
            }
        }
        buttonCallbackIds.clear()
        osmand.call("removeMapWidget") { it.removeMapWidget(RemoveMapWidgetParams(WIDGET_ID)) }
        osmand.call("setNavDrawerItems") {
            it.setNavDrawerItems(SetNavDrawerItemsParams(osmand.appPackage, emptyList()))
        }
        shown.clear()
    }

    /** Makes the layer show exactly [stops]. */
    fun showStops(stops: List<Stop>, now: Long) {
        val points = stops.map { pointOf(it, details(now)) }
        val ids = points.mapTo(HashSet()) { it.id }
        for (gone in shown.keys - ids) {
            osmand.call("removeMapPoint") { it.removeMapPoint(RemoveMapPointParams(LAYER_ID, gone)) }
            shown.remove(gone)
        }
        // OsmAnd merges the points it's given into the layer. Chunks keep each binder call well under its limit.
        for (chunk in points.chunked(POINTS_PER_CALL)) {
            osmand.call("updateMapLayer") { it.updateMapLayer(UpdateMapLayerParams(layer(chunk))) }
        }
        points.forEach { shown[it.id] = it }
    }

    /**
     * Puts [details] in the menu of [stop], which is open (a button in it was just pressed). OsmAnd redraws
     * the open menu, and also centers the map on the stop.
     */
    fun showInMenu(stop: Stop, details: List<String>) {
        val point = pointOf(stop, details)
        osmand.call("updateMapPoint") { it.updateMapPoint(UpdateMapPointParams(LAYER_ID, point, true)) }
        shown[point.id] = point
    }

    /**
     * Centers OsmAnd's map on [stop] and opens its menu, as if it had been tapped there, in place of any menu
     * that was open. OsmAnd shows it once its map is in front. False if OsmAnd didn't take it.
     */
    fun showMenu(stop: Stop, now: Long): Boolean {
        val point = pointOf(stop, details(now))
        // Into the layer as well, so the stop is still there once the menu is closed.
        osmand.call("updateMapLayer") { it.updateMapLayer(UpdateMapLayerParams(layer(listOf(point)))) }
        shown[point.id] = point
        return osmand.call("showMapPoint") { it.showMapPoint(ShowMapPointParams(LAYER_ID, point)) } == true
    }

    /**
     * The menu's detail rows: only when the stop was loaded. Its departures are a tap away, in the Next departures
     * and Full day sheets, where they have room.
     */
    private fun details(now: Long): List<String> = listOf(strings.getString(R.string.tt_updated, TransitFormat.clock(now)))

    /** Whether OsmAnd's language is no longer the one everything was registered in. */
    fun languageChanged(): Boolean = osmandLocales()?.let { it != locales } == true

    /**
     * The language OsmAnd shows itself in: the one picked in its settings, else the phone's (not this app's, which
     * can be set apart in Android's settings). Null if OsmAnd can't be asked.
     */
    private fun osmandLocales(): LocaleList? {
        // Like "ru", "pt_BR" or "b+sr+Latn"; empty when it follows the phone.
        val preferred = preference(PREF_LOCALE) ?: return null
        if (preferred.isEmpty()) return Resources.getSystem().configuration.locales
        val tag = preferred.removePrefix("b+").replace('+', '-').replace('_', '-')
        return LocaleList(Locale.forLanguageTag(tag))
    }

    /**
     * Whether OsmAnd's map screen looks dark right now, at a place near [lat], [lon], so the Full day sheet can
     * match it; null when OsmAnd goes by its light sensor or the phone, and then the sheet follows the phone.
     */
    fun isNight(lat: Double, lon: Double, now: Long): Boolean? = when (preference(PREF_DAY_NIGHT)) {
        "DAY" -> false
        "NIGHT" -> true
        "AUTO" -> Sun.isDown(lat, lon, now)
        // Like OsmAnd's own screens: its dark theme is 0, light 1, and 2 follows the phone.
        "APP_THEME" -> when (preference(PREF_APP_THEME)) {
            "0" -> true
            "1" -> false
            else -> null
        }
        else -> null
    }

    /** One of OsmAnd's settings, for its current profile. */
    private fun preference(id: String): String? = osmand.call("getPreference") { api ->
        PreferenceParams(id).takeIf { api.getPreference(it) }?.value
    }

    /** Shows the next departure from [stop] in the widget; null clears it (e.g. the map left Estonia). */
    fun updateWidget(stop: Stop?, now: Long) {
        val next = stop?.departures?.firstOrNull { it.time >= now - GRACE_MS }
        val text = when {
            stop == null -> NO_VALUE
            next == null -> strings.getString(R.string.tt_widget_none)
            else -> "${next.route} · " +
                (TransitFormat.relative(strings, next.time, now) ?: TransitFormat.clockWithDay(next.time, now, locales[0]))
        }
        if (text == widgetText && stop?.id == widgetStopId) return
        osmand.call("updateMapWidget") { it.updateMapWidget(UpdateMapWidgetParams(widget(text, stop))) }
        widgetText = text
        widgetStopId = stop?.id
    }

    private fun pointOf(stop: Stop, details: List<String>): AMapPoint {
        val mode = Mode.of(stop.mode)
        val params = mapOf(
            AMapPoint.POINT_IMAGE_URI_PARAM to StopIconProvider.uri(context, mode),
            AMapPoint.POINT_TYPE_ICON_NAME_PARAM to mode.osmandIcon,
        )
        return AMapPoint(
            stop.id, "", stop.name, TransitFormat.stopType(strings, stop), LAYER_ID, mode.color,
            ALatLon(stop.lat, stop.lon), details, params,
        )
    }

    private fun layer(points: List<AMapPoint>) =
        AMapLayer(LAYER_ID, strings.getString(R.string.tt_layer_name), Z_ORDER, points).apply {
            isImagePoints = true
            setCirclePointZoomBounds(13, 14)
            setSmallPointZoomBounds(15, MAX_ZOOM)
            // Never: OsmAnd's big pin is made for people's avatars.
            setBigPointZoomBounds(MAX_ZOOM + 1, MAX_ZOOM + 1)
        }

    /**
     * One row per button: a row is meant to hold a left and a right button, but OsmAnd (5.x) reads the right
     * one of a V2 row into the left slot, so a row with both shows only one.
     */
    private fun buttonRows(): List<ContextMenuButtonsParams> {
        fun row(id: Int, caption: Int, icon: String) = ContextMenuButtonsParams(
            AContextMenuButton(id, strings.getString(caption), "", icon, "", true, true), null,
            "${BUTTONS_ID}_$id", osmand.appPackage, LAYER_ID, 0L,
            // OsmAnd shows the buttons on a point whose id is listed here OR that is in our layer. An empty list
            // would put them on every app's points, so list something that only matches the layer rule.
            listOf(LAYER_ID),
        )
        return listOf(
            row(BUTTON_DEPARTURES, R.string.tt_button_departures, "ic_action_update"),
            row(BUTTON_FULL_DAY, R.string.tt_button_full_day, "ic_action_time"),
            row(BUTTON_SHOW_IN_APP, R.string.tt_button_show_in_app, "ic_action_external_link"),
        )
    }

    private fun widget(text: String, stop: Stop?): AMapWidget {
        // OsmAnd starts this from its application context, hence NEW_TASK.
        val onClick = (stop?.let { StopActivity.intent(context, it.id, it.name) } ?: deepLink(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val icon = Mode.BUS.osmandIcon
        return AMapWidget(
            WIDGET_ID, icon, strings.getString(R.string.tt_widget_title), icon, icon, text, "", WIDGET_ORDER, onClick,
        )
    }

    companion object {
        const val BUTTON_DEPARTURES = 1
        const val BUTTON_FULL_DAY = 2
        const val BUTTON_SHOW_IN_APP = 3

        /** Opens this app's stop search; OsmAnd's main menu item points here. */
        const val DEEP_LINK = "osmandcompanion://timetable"

        fun deepLink(context: Context): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(DEEP_LINK)).setPackage(context.packageName)

        /** [context] with its strings and dates in [locales]. */
        fun localized(context: Context, locales: LocaleList): Context =
            context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocales(locales) })

        private const val LAYER_ID = "transit_stops"
        private const val BUTTONS_ID = "transit_stop_buttons"
        private const val WIDGET_ID = "transit_next_departure"
        private const val Z_ORDER = 5.5f
        private const val WIDGET_ORDER = 100
        private const val MAX_ZOOM = 25
        private const val POINTS_PER_CALL = 40
        private const val PREF_DAY_NIGHT = "daynight_mode"
        private const val PREF_APP_THEME = "osmand_theme"
        private const val PREF_LOCALE = "preferred_locale"
        private const val NO_VALUE = "—"

        /** A departure a few seconds past is probably still at the stop. */
        private const val GRACE_MS = 30_000L
    }
}
