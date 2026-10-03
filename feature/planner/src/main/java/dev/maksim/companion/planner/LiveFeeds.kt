package dev.maksim.companion.planner

import dev.maksim.companion.timetable.RidangoLive
import dev.maksim.companion.timetable.Stop
import dev.maksim.companion.timetable.TallinnLive
import java.util.concurrent.ConcurrentHashMap

/**
 * The live feeds the planner asks, with what they said kept for [FRESH_MS], so the itineraries of one search share
 * their answers: Tallinn's city feed, one stop at a time by the code on its sign, and Ridango's county buses, one trip
 * at a time.
 *
 * Blocking: call it off the main thread.
 */
class LiveFeeds(
    private val tallinn: TallinnLive = TallinnLive(),
    private val ridango: RidangoLive = RidangoLive(),
) {
    private class Answer<T>(val value: T, val at: Long)

    private val stops = ConcurrentHashMap<String, Answer<TallinnLive.Times>>()
    private val trips = ConcurrentHashMap<String, Answer<List<RidangoLive.StopTime>?>>()

    /** What the city's feed lists at each of the stops with [codes], by code; a stop it didn't answer for is left out. */
    fun tallinn(codes: Collection<String>, now: Long = System.currentTimeMillis()): Map<String, TallinnLive.Times> {
        val stale = codes.distinct().filter { code -> stops[code]?.takeIf { now - it.at < FRESH_MS } == null }
        if (stale.isNotEmpty()) {
            // The feed knows a stop by the code on its sign (TallinnLive looks up its own id for it).
            val asked = stale.map { Stop(id = KEY + it, name = "", code = it, lat = 0.0, lon = 0.0, mode = null) }
            val answers = runCatching { tallinn.departures(asked) }.getOrDefault(emptyMap())
            for ((id, times) in answers) stops[id.removePrefix(KEY)] = Answer(times, now)
        }
        return codes.mapNotNull { code -> stops[code]?.let { code to it.value } }.toMap()
    }

    /** Ridango's times of county bus trip [tripId] (peatus.ee's) on [date] (yyyyMMdd), or null. */
    fun county(tripId: String, date: String, now: Long = System.currentTimeMillis()): List<RidangoLive.StopTime>? {
        val key = "$tripId@$date"
        trips[key]?.takeIf { now - it.at < FRESH_MS }?.let { return it.value }
        val times = runCatching { ridango.trip(tripId, date) }.getOrNull()
        trips[key] = Answer(times, now)
        return times
    }

    private companion object {
        /** How long an answer is good for: about as often as the feeds change. */
        const val FRESH_MS = 20_000L

        const val KEY = "code:"
    }
}
