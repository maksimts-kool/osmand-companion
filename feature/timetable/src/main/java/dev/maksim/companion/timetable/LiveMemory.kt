package dev.maksim.companion.timetable

import java.util.concurrent.ConcurrentHashMap

/**
 * The live times Tallinn's city feed ([TallinnLive]) last gave for a trip at each of its stops, kept for the app's
 * lifetime. The feed forgets a stop as soon as the vehicle has left it, so the stops behind the vehicle would only
 * have the timetable; those it was seen live at keep the time it was last expected at, about when it left. (Ridango
 * has the stops behind the vehicle itself, [RidangoLive].)
 *
 * Filled whenever a city line's live times come in: a trip's ([PeatusClient.live]), and a stop's next departures
 * (the stop screen, the Next departures sheet), so a stop looked at before the bus came has its time on the trip too.
 */
internal object LiveMemory {

    /** A trip at a stop, where peatus.ee has it at [timetabled] seconds from [serviceDay]: a loop calls twice. */
    private data class Key(val serviceDay: Long, val tripId: String, val stopId: String, val timetabled: Int)

    /** The feed's timetabled and expected times, in seconds from the service day. */
    private class Seen(val scheduled: Int, val expected: Int)

    private val seen = ConcurrentHashMap<Key, Seen>()

    @Volatile
    private var prunedAt = 0L

    /**
     * The feed has trip [tripId] at [stopId] at [scheduled] by its timetable and [expected] by its vehicle, where
     * peatus.ee has [timetabled]; all in seconds from [serviceDay].
     */
    fun remember(serviceDay: Long, tripId: String, stopId: String, timetabled: Int, scheduled: Int, expected: Int) {
        seen[Key(serviceDay, tripId, stopId, timetabled)] = Seen(scheduled, expected)
        prune()
    }

    /** [stop], one of [trip]'s, at the live time it was last seen at, or null if it never was. */
    fun recall(trip: Trip, stop: TripStop): TripStop? =
        seen[Key(trip.serviceDay, trip.id, stop.stop.id, stop.scheduled)]?.let {
            stop.copy(scheduled = it.scheduled, expected = it.expected, isRealtime = true)
        }

    /**
     * [trip] with the live times last seen at its stops that have none now, where that time has passed by [now]
     * (epoch seconds): the vehicle has most likely left those. One still to come is only an old guess.
     */
    fun recallPassed(trip: Trip, now: Long): Trip = trip.copy(
        stops = trip.stops.map { stop ->
            if (stop.isRealtime) return@map stop
            recall(trip, stop)?.takeIf { trip.serviceDay + it.expected <= now } ?: stop
        },
    )

    private fun prune() {
        val now = System.currentTimeMillis() / 1000
        if (now - prunedAt < PRUNE_EVERY_S) return
        prunedAt = now
        seen.entries.removeIf { (key, time) -> key.serviceDay + time.expected < now - KEEP_S }
    }

    /** Long enough to look back at a trip later in the day. */
    private const val KEEP_S = 6 * 60 * 60L
    private const val PRUNE_EVERY_S = 10 * 60L
}
