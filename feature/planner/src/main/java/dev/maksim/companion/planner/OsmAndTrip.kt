package dev.maksim.companion.planner

import android.content.Context
import android.content.Intent
import dev.maksim.companion.core.OsmAndConnection
import dev.maksim.companion.timetable.LatLon
import dev.maksim.companion.timetable.Mode
import dev.maksim.companion.timetable.OsmAndRoute
import dev.maksim.companion.timetable.TransitFormat
import dev.maksim.companion.timetable.distanceMeters
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

    /** OsmAnd navigating to within this of a stop is navigating to it. */
    private const val SAME_PLACE_M = 60.0

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

    /** OsmAnd's navigation as it is: [left] ms and [meters] to go, there at [arrival] (epoch ms). */
    class Navigation(val left: Long, val meters: Int, val arrival: Long)

    /**
     * OsmAnd's own ETA for walking to [to], if it's navigating there now (as *Walk there with OsmAnd* starts it): its
     * route goes by the streets as you walk them, where the planner's walk is a guess. Null if it navigates elsewhere,
     * or not at all.
     */
    fun navigationTo(osmand: OsmAndConnection, to: Call, now: Long = System.currentTimeMillis()): Navigation? =
        position(osmand, to, now)?.navigation

    /**
     * Where OsmAnd has you ([here]; while OsmAnd is in the background, only as of when it last had its map up), and its
     * [navigation] to the place asked about, if it's navigating there. [routeDone]: OsmAnd's destination is that place
     * but there's no way left to go, as with its map up once it's got you there (in the background it clears the
     * destination straight away instead: [TripPosition.osmandArrived]).
     */
    class Position(val here: LatLon?, val navigation: Navigation?, val routeDone: Boolean = false)

    /** Where OsmAnd has you, and its ETA to [to] as for [navigationTo], in one go; null if OsmAnd can't be asked. */
    fun position(osmand: OsmAndConnection, to: Call, now: Long = System.currentTimeMillis()): Position? {
        if (!osmand.hasAccess) return null
        val info = osmand.call("getAppInfo") { it.appInfo } ?: return null
        val here = info.lastKnownLocation?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }?.let { LatLon(it.latitude, it.longitude) }
        val destination = info.destinationLocation
        val goingThere = destination != null && distanceMeters(destination.latitude, destination.longitude, to.lat, to.lon) <= SAME_PLACE_M
        val navigation = if (goingThere && info.leftTime > 0) {
            val left = info.leftTime * 1000L
            Navigation(left, info.leftDistance, info.arrivalTime.takeIf { it > now } ?: (now + left))
        } else {
            null
        }
        return Position(here, navigation, routeDone = goingThere && info.leftTime <= 0)
    }

    /** Where a trip at [progress] is walking to, if it is: the stop of the next ride, or the end. */
    fun walkTarget(itinerary: Itinerary, progress: TripProgress.Progress): Call? {
        val legs = itinerary.legs
        val leg = legs.getOrNull(progress.leg) ?: return null
        return when (progress.kind) {
            TripProgress.Kind.LEAVE, TripProgress.Kind.TO_STOP -> if (leg.isWalk) leg.to else leg.from
            TripProgress.Kind.WALK_THERE -> legs.last().to
            else -> null
        }
    }

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
