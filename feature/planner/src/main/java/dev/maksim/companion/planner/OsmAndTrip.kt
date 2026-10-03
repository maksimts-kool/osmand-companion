package dev.maksim.companion.planner

import android.content.Context
import android.content.Intent
import dev.maksim.companion.core.OsmAndConnection
import dev.maksim.companion.timetable.Mode
import dev.maksim.companion.timetable.OsmAndRoute
import dev.maksim.companion.timetable.TransitFormat
import net.osmand.aidlapi.navigation.NavigateParams

/**
 * The planner in OsmAnd: an itinerary drawn on its map like a trip's route ([OsmAndRoute]), a track per walk and ride
 * in their colors with the stops on it, and OsmAnd's walking navigation to the first stop. Where you are, where
 * OsmAnd navigates to and where its map is are also OsmAnd's ([places]), so this app needs no location permission.
 *
 * Blocking: call it off the main thread.
 */
object OsmAndTrip {

    /** Walks: a blue-grey that reads on OsmAnd's day and night maps alike. */
    private const val WALK_COLOR = 0xFF78909C.toInt()

    /** OsmAnd's profile for walking. */
    private const val PEDESTRIAN = "pedestrian"

    /** Draws [itinerary] to [destination] in OsmAnd with its card open; the caller brings OsmAnd to the front. */
    fun show(context: Context, osmand: OsmAndConnection, itinerary: Itinerary, destination: String): Boolean {
        val tracks = itinerary.legs.mapIndexed { i, leg ->
            val ride = leg.ride
            if (ride == null) {
                val marks = if (i == itinerary.legs.lastIndex) listOf(mark(leg.to)) else emptyList()
                OsmAndRoute.Track(context.getString(R.string.pl_walk, ItineraryViews.walk(context, leg)), leg.line, WALK_COLOR, marks, OsmAndRoute.DEFAULT_ICON)
            } else {
                val mode = Mode.of(ride.mode)
                OsmAndRoute.Track(
                    "${ItineraryViews.vehicle(context, ride)} → ${ride.headsign}", leg.line, mode.color,
                    listOf(mark(leg.from), mark(leg.to)), OsmAndRoute.ICONS[mode] ?: OsmAndRoute.DEFAULT_ICON,
                )
            }
        }
        val first = itinerary.rides.firstOrNull()
        val card = OsmAndRoute.card(
            context,
            first?.ride?.let { Mode.of(it.mode) } ?: Mode.OTHER,
            first?.ride?.route.orEmpty(),
            context.getString(R.string.pl_title, destination),
            context.getString(
                R.string.pl_trip_card, TransitFormat.clock(itinerary.start), TransitFormat.clock(itinerary.end),
                ItineraryViews.duration(context, itinerary.end - itinerary.start),
            ),
            (first?.from ?: itinerary.legs.first().from).point,
            steps(context, itinerary),
        )
        return OsmAndRoute.showTracks(context, osmand, tracks, card)
    }

    /**
     * Starts OsmAnd's walking navigation from where you are to [stop]. OsmAnd only takes it while its map screen is
     * up, so bring OsmAnd to the front first.
     */
    fun walkTo(osmand: OsmAndConnection, stop: Call): Boolean = osmand.call("navigate") {
        // No start: from where OsmAnd has you.
        it.navigate(NavigateParams(null, 0.0, 0.0, stop.name, stop.lat, stop.lon, PEDESTRIAN, true, true))
    } == true

    /** What OsmAnd knows of where things are. */
    class Places(val myLocation: Place?, val destination: Place?, val mapCenter: Place?)

    /** Where OsmAnd has you, where it navigates to, and where its map is; null if OsmAnd can't be asked. */
    fun places(context: Context, osmand: OsmAndConnection): Places? {
        if (!osmand.hasAccess) return null
        val info = osmand.call("getAppInfo") { it.appInfo } ?: return null
        fun place(at: net.osmand.aidlapi.map.ALatLon?, name: Int, detail: Int, mine: Boolean = false) =
            at?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }?.let {
                Place(context.getString(name), it.latitude, it.longitude, context.getString(detail), isMyLocation = mine)
            }
        return Places(
            place(info.lastKnownLocation, R.string.pl_my_location, R.string.pl_my_location_detail, mine = true),
            place(info.destinationLocation, R.string.pl_osmand_destination, R.string.pl_osmand_destination_detail),
            place(info.mapLocation, R.string.pl_map_center, R.string.pl_map_center_detail),
        )
    }

    /** OsmAnd's launch intent, to bring it to the front; null if it isn't installed. */
    fun launchIntent(context: Context, osmand: OsmAndConnection): Intent? =
        (osmand.osmandPackage ?: osmand.findInstalledOsmand())?.let { context.packageManager.getLaunchIntentForPackage(it) }

    private fun mark(call: Call) = OsmAndRoute.Mark(call.lat, call.lon, "${TransitFormat.clock(call.expected)} ${call.name}")

    /** The card's rows: "15:04  Walk 5 min to Vabaduse väljak", "15:09  Bus 23 → Raja +2", "15:31  Get off at Raja". */
    private fun steps(context: Context, itinerary: Itinerary): List<String> = itinerary.legs.flatMap { leg ->
        val ride = leg.ride
        if (ride == null) {
            val walk = ItineraryViews.walk(context, leg)
            val line = if (leg === itinerary.legs.last()) context.getString(R.string.pl_walk, walk)
            else context.getString(R.string.pl_walk_to, walk, leg.to.name)
            listOf("${TransitFormat.clock(leg.departure)}  $line")
        } else {
            val delay = ItineraryViews.delay(leg.from)?.let { " $it" }.orEmpty()
            listOf(
                "${TransitFormat.clock(leg.departure)}  ${ItineraryViews.vehicle(context, ride)} → ${ride.headsign}$delay",
                "${TransitFormat.clock(leg.arrival)}  ${context.getString(R.string.pl_get_off, leg.to.name)}",
            )
        }
    }
}
