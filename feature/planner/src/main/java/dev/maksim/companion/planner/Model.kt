package dev.maksim.companion.planner

import dev.maksim.companion.timetable.LatLon
import java.io.Serializable
import kotlin.math.roundToInt

/**
 * Where a trip starts or ends: an address, a stop, or a point on OsmAnd's map. [isMyLocation]: wherever OsmAnd has the
 * phone when it's planned, so it's asked again each time.
 */
data class Place(
    val name: String,
    val lat: Double,
    val lon: Double,
    val detail: String? = null,
    val isMyLocation: Boolean = false,
) : Serializable

/** Which planner found an itinerary. */
enum class Source { TALLINN, RIDANGO, PEATUS }

/**
 * Where a ride's live times come from, besides what the planner that found it already had: Tallinn's city lines
 * from the city's feed, Harjumaa's county buses from Ridango. Others are as the planner had them.
 */
enum class LiveFeed { TALLINN, COUNTY, NONE }

/**
 * A stop (or the start or end of a walk) and when the leg is there, epoch ms: [scheduled] by the timetable, [expected]
 * as it looks now, which is the vehicle's time if [isLive].
 */
data class Call(
    val name: String,
    val lat: Double,
    val lon: Double,
    val scheduled: Long,
    val expected: Long = scheduled,
    val isLive: Boolean = false,
    /** peatus.ee's id, e.g. "estonia:1323"; null when the planner had another. */
    val stopId: String? = null,
    /** The code on the stop's sign, e.g. "12402-1"; how the live feeds know it. */
    val code: String? = null,
) : Serializable {
    /** How late (more than 0) or early, in whole minutes. */
    val delayMinutes: Int get() = ((expected - scheduled) / 60_000.0).roundToInt()

    val point: LatLon get() = LatLon(lat, lon)
}

/** What a leg rides on. */
data class Ride(
    /** Short name, e.g. "23" or "T4". */
    val route: String,
    /** BUS, TRAM, RAIL... as the timetable feature has them (PeatusClient.TROLLEYBUS, PeatusClient.REGIONAL). */
    val mode: String,
    val headsign: String,
    val longName: String? = null,
    /** peatus.ee's ids, when known: for the trip's screen. */
    val routeId: String? = null,
    val tripId: String? = null,
    /** The trip's service day, yyyyMMdd. */
    val serviceDate: String? = null,
    /** When the trip leaves its first stop by the timetable, epoch ms, if known. */
    val tripStart: Long? = null,
    val feed: LiveFeed = LiveFeed.NONE,
    /** The trip planned on can't be caught any more, and this is the next one of the line. */
    val replaced: Boolean = false,
    /** The live feed says its vehicle has already left the stop it's to be got on at. */
    val gone: Boolean = false,
) : Serializable

/** A walk ([ride] null) or a ride, [from] one call [to] another, through [stops] for a ride. */
data class Leg(
    val ride: Ride?,
    val from: Call,
    val to: Call,
    val stops: List<Call> = emptyList(),
    /** The way it goes, for OsmAnd's map; empty when only the calls are known. */
    val shape: List<LatLon> = emptyList(),
    /** Meters. */
    val distance: Double = 0.0,
) : Serializable {
    val isWalk: Boolean get() = ride == null
    val departure: Long get() = from.expected
    val arrival: Long get() = to.expected

    /** How long it takes by the timetable; a walk takes as long whenever it's walked. */
    val duration: Long get() = to.scheduled - from.scheduled

    /** [shape], or straight lines between the calls. */
    val line: List<LatLon> get() = shape.ifEmpty { (listOf(from) + stops + to).map { it.point } }
}

/** One way to get there: walks and rides, one after the other. */
data class Itinerary(val legs: List<Leg>, val source: Source) : Serializable {
    val rides: List<Leg> get() = legs.filter { !it.isWalk }

    /** When to leave: the start of the first walk, timed to catch the first ride as it's expected now. */
    val start: Long get() = legs.first().departure
    val end: Long get() = legs.last().arrival
    val transfers: Int get() = maxOf(0, rides.size - 1)
    val walkMeters: Double get() = legs.filter { it.isWalk }.sumOf { it.distance }

    /** Whether any ride's times come from its vehicle. */
    val isLive: Boolean get() = rides.any { it.from.isLive || it.to.isLive }

    /**
     * The margin of each change between rides, in ms: when the next leaves, less when the one before arrives and the
     * walk between. Less than 0 is a missed connection.
     */
    val changes: List<Change>
        get() {
            val rides = legs.withIndex().filter { !it.value.isWalk }
            return rides.zipWithNext { (i, before), (j, after) ->
                val walk = legs.subList(i + 1, j).sumOf { it.duration }
                Change(after.from, after.departure - before.arrival - walk)
            }
        }

    class Change(val at: Call, val margin: Long) : Serializable

    /** The walks retimed to the rides: the first ends as the first ride leaves, the others start as the ride before arrives. */
    fun walksRetimed(): Itinerary {
        val firstRide = legs.indexOfFirst { !it.isWalk }
        if (firstRide < 0) return this
        val legs = legs.toMutableList()
        // Back from the first ride.
        var time = legs[firstRide].departure
        for (i in firstRide - 1 downTo 0) {
            legs[i] = legs[i].timed(time - legs[i].duration)
            time = legs[i].departure
        }
        // On from it.
        time = legs[firstRide].arrival
        for (i in firstRide + 1 until legs.size) {
            if (legs[i].isWalk) legs[i] = legs[i].timed(time)
            time = legs[i].arrival
        }
        return copy(legs = legs)
    }

    /** Walks one after the other as one: OpenTripPlanner sometimes splits a walk at a stop it doesn't ride from. */
    fun walksJoined(): Itinerary {
        val joined = ArrayList<Leg>(legs.size)
        for (leg in legs) {
            val last = joined.lastOrNull()
            if (last != null && last.isWalk && leg.isWalk) {
                joined[joined.lastIndex] = last.copy(
                    to = leg.to,
                    shape = if (last.shape.isEmpty() || leg.shape.isEmpty()) emptyList() else last.shape + leg.shape,
                    distance = last.distance + leg.distance,
                )
            } else {
                joined += leg
            }
        }
        return copy(legs = joined)
    }

    /** The legs' rides: route and stops, so the same itinerary found twice is seen as one. */
    val signature: String
        get() = rides.joinToString("|") { leg ->
            val ride = leg.ride!!
            "${ride.route}:${ride.mode}:${leg.from.key}>${leg.to.key}@${leg.from.scheduled / 60_000}"
        }.ifEmpty { "walk" }
}

/** A walk leg moved to start at [start]. */
private fun Leg.timed(start: Long): Leg {
    val duration = duration
    return copy(from = from.copy(scheduled = start, expected = start), to = to.copy(scheduled = start + duration, expected = start + duration))
}

/** What tells two calls at the same stop apart from others: its code, else its name. */
internal val Call.key: String get() = code ?: name.lowercase()
