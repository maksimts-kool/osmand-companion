package dev.maksim.companion.timetable

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.edit
import dev.maksim.companion.core.AppLog
import dev.maksim.companion.core.OsmAndConnection
import net.osmand.aidlapi.gpx.HideGpxParams
import net.osmand.aidlapi.gpx.ImportGpxParams
import net.osmand.aidlapi.gpx.RemoveGpxParams
import net.osmand.aidlapi.map.ALatLon
import net.osmand.aidlapi.map.SetMapLocationParams
import net.osmand.aidlapi.maplayer.AMapLayer
import net.osmand.aidlapi.maplayer.AddMapLayerParams
import net.osmand.aidlapi.maplayer.RemoveMapLayerParams
import net.osmand.aidlapi.maplayer.point.AMapPoint
import net.osmand.aidlapi.maplayer.point.ShowMapPointParams
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Shows a trip on OsmAnd's map the way OsmAnd shows its own transport routes: the line in the vehicle's color with
 * a marker per stop, all of it in view, and the route's card open at the bottom with every stop and its time. Like
 * OsmAnd's own, it goes away as soon as the card does (a tap elsewhere on the map, or Back): [watch] asks OsmAnd
 * every half second whether its menu is still open, as the API has no "closed" event.
 *
 * The line is the operator's own, from today's feed, so it's right even where OsmAnd's own transport routes aren't
 * ([OsmRouteCheck]). OsmAnd only takes lines as tracks, so it's a track of this app's while it's shown. If this
 * app's process dies meanwhile, [clearLeftover] removes it next time.
 */
internal object OsmAndRoute {

    private const val FILE_NAME = "OsmAnd Companion route.gpx"
    private const val LAYER_ID = "transit_route"
    private const val CARD_ID = "route"
    private const val PREFS = "osmand_route"
    private const val KEY_SHOWN = "shown"

    /** How long OsmAnd takes to come to the front; it only moves its map once it's there. */
    private const val FIT_DELAY_MS = 1_000L
    private const val POLL_MS = 500L

    /** OsmAnd didn't come up with the card (e.g. the phone went back to this app): remove the route after all. */
    private const val CARD_TIMEOUT_MS = 20_000L

    /** Runs [watch]; one at a time, for the one route shown. */
    private var watcher: HandlerThread? = null

    /**
     * Blocking. Draws [trip] in OsmAnd and opens its card, at [fromStopId] if it's one of the trip's stops. OsmAnd
     * shows the card once it's in front, which is the caller's job, as OsmAnd can't bring itself there. False if
     * OsmAnd didn't take the route (no API access, or not connected).
     */
    fun show(context: Context, osmand: OsmAndConnection, trip: Trip, shape: List<LatLon>, fromStopId: String?): Boolean {
        if (!osmand.hasAccess) return false
        stopWatching()
        val color = String.format(Locale.ROOT, "#%06X", Mode.of(trip.mode).color and 0xFFFFFF)
        val points = shape.ifEmpty { trip.stops.map { LatLon(it.stop.lat, it.stop.lon) } }
        val gpx = gpx(context, trip, points, color)
        // Hidden first: OsmAnd only rereads a track that's on the map when it's shown again.
        osmand.call("hideGpx") { it.hideGpx(HideGpxParams(FILE_NAME)) }
        val imported = osmand.call("importGpx") { it.importGpx(ImportGpxParams(gpx, FILE_NAME, color, true)) } == true
        if (!imported) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_SHOWN, true) }

        val card = card(context, trip, fromStopId)
        osmand.call("removeMapLayer") { it.removeMapLayer(RemoveMapLayerParams(LAYER_ID)) }
        osmand.call("addMapLayer") { it.addMapLayer(AddMapLayerParams(layer(context, card))) }
        osmand.call("showMapPoint") { it.showMapPoint(ShowMapPointParams(LAYER_ID, card)) }
        watch(context.applicationContext, osmand, points)
        return true
    }

    /** Removes a route that's still in OsmAnd from before this app's process died. Blocking. */
    fun clearLeftover(context: Context, osmand: OsmAndConnection) {
        val shown = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOWN, false)
        if (shown && synchronized(this) { watcher == null } && osmand.hasAccess) clear(context, osmand)
    }

    /**
     * Once OsmAnd is in front, fits the whole route in view; then removes it once its card has been open and
     * isn't anymore.
     */
    private fun watch(context: Context, osmand: OsmAndConnection, points: List<LatLon>) = synchronized(this) {
        val thread = HandlerThread("Route in OsmAnd").also { it.start() }
        watcher = thread
        val handler = Handler(thread.looper)
        val started = SystemClock.elapsedRealtime()
        var seenOpen = false
        val poll = object : Runnable {
            override fun run() {
                if (watcher !== thread) return
                // OsmAnd throws when its map screen is gone; that's not an answer either way.
                val open = runCatching { osmand.api?.isMenuOpen }.getOrNull()
                when {
                    open == true -> seenOpen = true
                    open == false && seenOpen -> return clear(context, osmand)
                    !seenOpen && SystemClock.elapsedRealtime() - started > CARD_TIMEOUT_MS -> return clear(context, osmand)
                }
                handler.postDelayed(this, POLL_MS)
            }
        }
        handler.postDelayed({
            if (watcher === thread) fit(context, osmand, points)
            poll.run()
        }, FIT_DELAY_MS)
    }

    private fun stopWatching() = synchronized(this) {
        watcher?.quitSafely()
        watcher = null
    }

    private fun clear(context: Context, osmand: OsmAndConnection) {
        stopWatching()
        osmand.call("hideGpx") { it.hideGpx(HideGpxParams(FILE_NAME)) }
        // Also out of OsmAnd's list of tracks. OsmAnd only deletes tracks it knows an app imported, which it doesn't
        // always note; hidden is what matters.
        osmand.call("removeGpx") { it.removeGpx(RemoveGpxParams(FILE_NAME)) }
        osmand.call("removeMapLayer") { it.removeMapLayer(RemoveMapLayerParams(LAYER_ID)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_SHOWN, false) }
        AppLog.log("Timetables: removed the route from OsmAnd")
    }

    /** Moves OsmAnd's map to show all of [points], keeping its menu open. */
    private fun fit(context: Context, osmand: OsmAndConnection, points: List<LatLon>) {
        val south = points.minOf { it.lat }
        val north = points.maxOf { it.lat }
        val west = points.minOf { it.lon }
        val east = points.maxOf { it.lon }
        osmand.call("setMapLocation") {
            it.setMapLocation(
                SetMapLocationParams((south + north) / 2, (west + east) / 2, fitZoom(context, south, west, north, east), 0f, true),
            )
        }
    }

    /**
     * The route's card in OsmAnd's menu, like OsmAnd's own for its transport routes: "Bus 10 → Vana-Pääsküla", the
     * route's long name, and every stop with its time as the detail rows.
     */
    private fun card(context: Context, trip: Trip, fromStopId: String?): AMapPoint {
        val mode = Mode.of(trip.mode)
        val at = trip.stops.find { it.stop.id == fromStopId } ?: trip.stops.first()
        val stops = trip.stops.map { stop ->
            val line = "${TransitFormat.clock(stop.time(trip.serviceDay))}  ${stop.stop.name}"
            if (stop === at && fromStopId != null) "$line · ${context.getString(R.string.tt_your_stop)}" else line
        }
        val params = mapOf(
            AMapPoint.POINT_IMAGE_URI_PARAM to StopIconProvider.uri(context, mode),
            AMapPoint.POINT_TYPE_ICON_NAME_PARAM to mode.osmandIcon,
        )
        return AMapPoint(
            CARD_ID, trip.route, title(context, trip), trip.longName, LAYER_ID, mode.color,
            ALatLon(at.stop.lat, at.stop.lon), stops, params,
        )
    }

    private fun layer(context: Context, card: AMapPoint) =
        AMapLayer(LAYER_ID, context.getString(R.string.tt_route_layer_name), Z_ORDER, listOf(card)).apply {
            isImagePoints = true
            setCirclePointZoomBounds(1, 14)
            setSmallPointZoomBounds(15, MAX_ZOOM)
            setBigPointZoomBounds(MAX_ZOOM + 1, MAX_ZOOM + 1)
        }

    /** "Bus 10 → Vana-Pääsküla". */
    private fun title(context: Context, trip: Trip) =
        listOf(context.getString(Mode.of(trip.mode).label), trip.route, "→", trip.headsign)
            .filter { it.isNotEmpty() }.joinToString(" ")

    /** Above the stops layer, so the route's own stop is the one tapped. */
    private const val Z_ORDER = 5.6f
    private const val MAX_ZOOM = 25

    /** The zoom at which the box fits a phone screen with some room around it, like OsmAnd's own "fit". */
    private fun fitZoom(context: Context, south: Double, west: Double, north: Double, east: Double): Int {
        val metrics = context.resources.displayMetrics
        // OsmAnd's tiles are 256 px at its default density; leave a third of the screen for its panels.
        val widthTiles = metrics.widthPixels / metrics.density / 256.0 * 0.8
        val heightTiles = metrics.heightPixels / metrics.density / 256.0 * 0.6
        val lonSpan = max(east - west, 1e-4)
        val latSpan = max(north - south, 1e-4) / cos(Math.toRadians((north + south) / 2))
        val zoomX = ln(360.0 * widthTiles / lonSpan) / ln(2.0)
        val zoomY = ln(360.0 * heightTiles / latSpan) / ln(2.0)
        return floor(min(zoomX, zoomY)).toInt().coerceIn(5, 17)
    }

    private fun gpx(context: Context, trip: Trip, shape: List<LatLon>, color: String): String = buildString {
        val name = title(context, trip)
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="OsmAnd Companion" xmlns="http://www.topografix.com/GPX/1/1" """)
        append("""xmlns:osmand="https://osmand.net">""").append('\n')
        append("<metadata><name>").append(escape(name)).append("</name></metadata>\n")
        for (stop in trip.stops) {
            append("""<wpt lat="${stop.stop.lat}" lon="${stop.stop.lon}"><name>""")
            append(escape("${TransitFormat.clock(stop.time(trip.serviceDay))} ${stop.stop.name}"))
            append("</name><type>").append(escape(name)).append("</type>")
            append("<extensions><osmand:color>").append(color).append("</osmand:color>")
            append("<osmand:icon>").append(ICONS[Mode.of(trip.mode)] ?: "special_marker").append("</osmand:icon>")
            append("<osmand:background>circle</osmand:background></extensions></wpt>\n")
        }
        append("<trk><name>").append(escape(name)).append("</name><trkseg>\n")
        for (point in shape) append("""<trkpt lat="${point.lat}" lon="${point.lon}"/>""").append('\n')
        append("</trkseg></trk>\n")
        append("<extensions><osmand:color>").append(color).append("</osmand:color>")
        append("<osmand:width>bold</osmand:width><osmand:show_arrows>true</osmand:show_arrows></extensions>\n")
        append("</gpx>\n")
    }

    /** OsmAnd's own icon names for waypoints. */
    private val ICONS = mapOf(
        Mode.BUS to "highway_bus_stop",
        Mode.REGIONAL to "highway_bus_stop",
        Mode.TROLLEYBUS to "public_transport_stop_position_trolleybus",
        Mode.TRAM to "railway_tram_stop",
        Mode.RAIL to "railway_station",
        Mode.FERRY to "amenity_ferry_terminal",
    )

    private fun escape(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
