package dev.maksim.companion.planner

import dev.maksim.companion.core.Parallel
import dev.maksim.companion.timetable.Estonia
import dev.maksim.companion.timetable.PeatusClient
import dev.maksim.companion.timetable.RidangoLive
import dev.maksim.companion.timetable.TallinnLive
import dev.maksim.companion.timetable.TripStop
import dev.maksim.companion.timetable.distanceMeters
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

/**
 * Puts live times into itineraries a planner found on the timetable ([OtpPlanner]): each ride's departure where it's
 * got on and arrival where it's got off, from Tallinn's city feed for the city's lines and Ridango for the county
 * buses ([LiveFeeds]). Then checks that each ride can still be caught ([settle]): the first by walking there from
 * when the trip starts, the others by the time the one before gets in. One that can't is replaced by the next of the
 * same line from that stop, if there is one; if not, the itinerary is dropped.
 *
 * Blocking: call it off the main thread.
 */
class LiveRetimer(private val feeds: LiveFeeds, private val peatus: PeatusClient) {

    /** [itineraries] at their vehicles' times, as far as the feeds know at [now]. */
    fun retime(itineraries: List<Itinerary>, now: Long): List<Itinerary> {
        val rides = itineraries.flatMap { it.rides }
        val codes = rides.filter { it.ride?.feed == LiveFeed.TALLINN }.flatMap { listOfNotNull(it.from.code, it.to.code) }.toSet()
        val city = if (codes.isEmpty()) emptyMap() else feeds.tallinn(codes, now)
        prefetchCounty(rides, now)
        return itineraries.map { itinerary ->
            itinerary.copy(legs = itinerary.legs.map { retime(it, now, city) }).walksRetimed()
        }
    }

    private fun retime(leg: Leg, now: Long, city: Map<String, TallinnLive.Times>): Leg {
        val ride = leg.ride ?: return leg
        return when (ride.feed) {
            LiveFeed.TALLINN -> Retime.tallinn(leg, ride, now, city)
            LiveFeed.COUNTY -> {
                if (leg.from.isLive || ride.tripId == null || ride.serviceDate == null) return leg
                val times = feeds.county(ride.tripId, ride.serviceDate, now) ?: return leg
                Retime.county(leg, times, OtpPlanner.midnight(ride.serviceDate))
            }
            LiveFeed.NONE -> leg
        }
    }

    /** Ridango's county trips, a few at once, so [retime] finds them in [feeds]. */
    private fun prefetchCounty(rides: List<Leg>, now: Long) {
        val trips = rides.mapNotNull { leg ->
            val ride = leg.ride ?: return@mapNotNull null
            if (ride.feed != LiveFeed.COUNTY || leg.from.isLive) return@mapNotNull null
            ride.tripId?.let { id -> ride.serviceDate?.let { id to it } }
        }.distinct()
        if (trips.size < 2) return
        Parallel.map(trips, PARALLEL) { (id, date) -> feeds.county(id, date, now) }
    }

    /**
     * [itinerary] as it can be taken by someone ready to leave at [earliest], with the rides that can't be caught
     * any more replaced by the next of their line; null if one can't be.
     */
    fun settle(itinerary: Itinerary, earliest: Long, now: Long): Itinerary? {
        val legs = itinerary.legs.toMutableList()
        var ready = earliest
        var first = true
        var walked = 0L
        for (i in legs.indices) {
            val leg = legs[i]
            if (leg.isWalk) {
                walked += leg.duration
                continue
            }
            val atStop = ready + walked
            val missed = leg.ride!!.gone ||
                if (first) leg.departure < atStop - GRACE_MS else leg.departure < atStop
            if (missed) {
                legs[i] = replace(leg, atStop + if (first) 0 else CHANGE_MS, now) ?: return null
            }
            ready = legs[i].arrival
            walked = 0
            first = false
        }
        return itinerary.copy(legs = legs).walksRetimed()
    }

    /** The next run of [leg]'s line from its stop, ready by [after]: from the city's feed, else peatus.ee. */
    private fun replace(leg: Leg, after: Long, now: Long): Leg? {
        val ride = leg.ride ?: return null
        if (ride.feed == LiveFeed.TALLINN) {
            val codes = listOfNotNull(leg.from.code, leg.to.code)
            Retime.nextTallinn(leg, ride, after, feeds.tallinn(codes, now))?.let { return it }
        }
        return runCatching { nextByPeatus(leg, ride, after) }.getOrNull()
    }

    private fun nextByPeatus(leg: Leg, ride: Ride, after: Long): Leg? {
        val stopId = leg.from.stopId ?: peatusStop(leg.from) ?: return null
        val stop = peatus.stopWithin(stopId, REPLACE_WINDOW_S) ?: return null
        val departure = stop.departures.firstOrNull {
            it.route == ride.route && it.mode == ride.mode && it.time >= after && Retime.sameWay(it.headsign, ride.headsign)
        } ?: return null
        val date = Estonia.format("yyyyMMdd", departure.serviceDay * 1000 + NOON_MS, Locale.ROOT)
        val trip = peatus.trip(departure.tripId, date) ?: return null
        val board = trip.stops.indexOfFirst { it.stop.id == stopId && it.scheduled == departure.scheduled }
            .takeIf { it >= 0 } ?: trip.stops.indexOfFirst { it.stop.id == stopId }
        if (board < 0) return null
        val alight = (board + 1 until trip.stops.size).firstOrNull { same(trip.stops[it], leg.to) } ?: return null
        val delay = (departure.expected - departure.scheduled) * 1000L
        fun call(stop: TripStop, live: Boolean): Call {
            val scheduled = (trip.serviceDay + stop.scheduled) * 1000
            val expected = if (stop.isRealtime) (trip.serviceDay + stop.expected) * 1000 else scheduled + delay
            return Call(stop.stop.name, stop.stop.lat, stop.stop.lon, scheduled, expected, live || stop.isRealtime, stop.stop.id, stop.stop.code)
        }
        val live = departure.isRealtime
        return leg.copy(
            ride = ride.copy(
                tripId = trip.id,
                serviceDate = date,
                tripStart = trip.stops.firstOrNull()?.let { (trip.serviceDay + it.scheduled) * 1000 },
                replaced = true,
                gone = false,
            ),
            from = call(trip.stops[board], live).copy(expected = departure.time, isLive = live),
            to = call(trip.stops[alight], live),
            stops = (board + 1 until alight).map { call(trip.stops[it], live) },
        )
    }

    /** Whether [stop], one of a trip's, is where [call] is. */
    private fun same(stop: TripStop, call: Call): Boolean = when {
        call.stopId != null -> stop.stop.id == call.stopId
        call.code != null -> stop.stop.code == call.code
        else -> stop.stop.name.equals(call.name, ignoreCase = true) &&
            distanceMeters(stop.stop.lat, stop.stop.lon, call.lat, call.lon) < SAME_STOP_M
    }

    /** peatus.ee's id for a stop a planner had by its code only (Ridango's). */
    private fun peatusStop(call: Call): String? {
        val code = call.code ?: return null
        val stops = try {
            peatus.nearbyStops(call.lat, call.lon, SAME_STOP_M.toInt(), 20, 0)
        } catch (_: IOException) {
            return null
        }
        return stops.firstOrNull { it.code == code }?.id
    }

    private companion object {
        /** Running for it: a minute's worth. */
        const val GRACE_MS = 60_000L

        /** At least this between getting off one and on the next, when it's the next of the line. */
        const val CHANGE_MS = 60_000L

        const val REPLACE_WINDOW_S = 2 * 60 * 60
        const val SAME_STOP_M = 80.0
        const val PARALLEL = 4
        const val NOON_MS = 12 * 60 * 60 * 1000L
    }
}

/** The pure parts of [LiveRetimer]: given what the feeds said, the legs. */
internal object Retime {

    /**
     * [leg], a ride on one of Tallinn's city lines, at its vehicle's times from the city's feed ([city], by stop
     * code). Where it's got on, the feed lists the run with its vehicle's time; where it's got off too, unless that's
     * the run's last stop (the feed never lists those) or beyond the feed's hour, where it has the delay. A run the feed
     * no longer lists where it's got on, while it lists a later one of the line, has gone ([Ride.gone]).
     */
    fun tallinn(leg: Leg, ride: Ride, now: Long, city: Map<String, TallinnLive.Times>): Leg {
        val atFrom = leg.from.code?.let { city[it] }
        val atTo = leg.to.code?.let { city[it] }
        var from = leg.from
        var to = leg.to
        var delay: Long? = null
        var gone = false
        val listed = atFrom?.find(ride.route, ride.mode, ride.headsign, leg.from.scheduled / 1000)
        if (listed != null) {
            val late = listed.expected - listed.scheduled
            // Before it has set off, the feed's time is its vehicle's guess from the trip before: the timetable it is.
            val started = ride.tripStart?.let { it / 1000 + late <= now / 1000 } ?: true
            if (started && atFrom.isLive(listed, started = true)) {
                delay = late * 1000
                from = from.copy(scheduled = listed.scheduled * 1000, expected = listed.expected * 1000, isLive = true)
            }
        } else if (atFrom != null) {
            val firstListed = atFrom.firstListed(ride.route, ride.mode, ride.headsign)
            gone = firstListed != null && firstListed.scheduled * 1000 > leg.from.scheduled + GONE_MARGIN_MS &&
                leg.from.scheduled <= now + GONE_AHEAD_MS
        }
        val arriving = atTo?.find(ride.route, ride.mode, ride.headsign, leg.to.scheduled / 1000)
            ?.takeIf { atTo.isLive(it, started = true) }
        if (arriving != null) {
            to = to.copy(scheduled = arriving.scheduled * 1000, expected = arriving.expected * 1000, isLive = true)
            if (delay == null && !gone) {
                delay = (arriving.expected - arriving.scheduled) * 1000
                from = from.copy(expected = from.scheduled + delay, isLive = true)
            }
        } else if (delay != null) {
            to = to.copy(expected = to.scheduled + delay, isLive = true)
        }
        val stops = delay?.let { d -> leg.stops.map { it.copy(expected = it.scheduled + d, isLive = true) } } ?: leg.stops
        return leg.copy(ride = if (gone) ride.copy(gone = true) else ride, from = from, to = to, stops = stops)
    }

    /**
     * [leg], a county bus ride, at the times Ridango has ([times], seconds from the service day starting at
     * [midnight]), where they're its vehicle's.
     */
    fun county(leg: Leg, times: List<RidangoLive.StopTime>, midnight: Long): Leg {
        fun find(call: Call) = times.firstOrNull {
            abs(midnight + it.scheduled * 1000L - call.scheduled) <= MATCH_MS && (it.code == null || call.code == null || it.code == call.code)
        }
        val atFrom = find(leg.from)
        val delay = atFrom?.takeIf { it.isRealtime }?.let { (it.expected - it.scheduled) * 1000L }
        fun live(call: Call): Call {
            val time = find(call)
            return when {
                time?.isRealtime == true -> call.copy(expected = midnight + time.expected * 1000L, isLive = true)
                delay != null -> call.copy(expected = call.scheduled + delay, isLive = true)
                else -> call
            }
        }
        if (delay == null && times.none { it.isRealtime }) return leg
        return leg.copy(from = live(leg.from), to = live(leg.to), stops = leg.stops.map(::live))
    }

    /**
     * [leg] on the next run of its Tallinn line that leaves its stop at or after [after] (epoch ms), from the city's
     * feed ([city]), or null if the feed lists none. Its other times are this one's moved by as much as its timetable.
     */
    fun nextTallinn(leg: Leg, ride: Ride, after: Long, city: Map<String, TallinnLive.Times>): Leg? {
        val atFrom = leg.from.code?.let { city[it] } ?: return null
        val next = atFrom.next(ride.route, ride.mode, ride.headsign, after / 1000) ?: return null
        val shift = next.scheduled * 1000 - leg.from.scheduled
        val late = (next.expected - next.scheduled) * 1000
        val live = atFrom.isLive(next, started = true)
        val toScheduled = leg.to.scheduled + shift
        val arriving = leg.to.code?.let { city[it] }?.let { times ->
            times.find(ride.route, ride.mode, ride.headsign, toScheduled / 1000)?.takeIf { times.isLive(it, started = true) }
        }
        return leg.copy(
            ride = ride.copy(tripId = null, tripStart = ride.tripStart?.plus(shift), replaced = true, gone = false),
            from = leg.from.copy(scheduled = next.scheduled * 1000, expected = next.expected * 1000, isLive = live),
            to = if (arriving != null) {
                leg.to.copy(scheduled = arriving.scheduled * 1000, expected = arriving.expected * 1000, isLive = true)
            } else {
                leg.to.copy(scheduled = toScheduled, expected = toScheduled + late, isLive = live)
            },
            stops = leg.stops.map { it.copy(scheduled = it.scheduled + shift, expected = it.scheduled + shift + late, isLive = live) },
        )
    }

    /** Whether two headsigns are the same way, give or take a "(train station)" or the end of a longer name. */
    fun sameWay(a: String, b: String): Boolean {
        val x = place(a)
        val y = place(b)
        return x.isEmpty() || y.isEmpty() || x.startsWith(y) || y.startsWith(x)
    }

    private fun place(name: String) = name.replace(BRACKETS, "").trim().lowercase(Locale.ROOT)

    private val BRACKETS = Regex("""\s*\(.*?\)""")

    /** Ridango's times are to the minute, like the planners'. */
    private const val MATCH_MS = 90_000L

    /** A run due this long before the first the feed still lists of its line has gone, if it's due soon. */
    private const val GONE_MARGIN_MS = 2 * 60_000L
    private const val GONE_AHEAD_MS = 30 * 60_000L
}
