package dev.maksim.companion.timetable

import dev.maksim.companion.core.Analytics
import org.json.JSONObject

/**
 * Live times of Harjumaa's county buses, from the journey planner behind iil.pilet.ee (Põhja-Eesti
 * Ühistranspordikeskus's site): Ridango's OpenTripPlanner, which has the national GTFS feed like peatus.ee, and
 * the live times of the buses that send them to Ridango's ticket system. peatus.ee doesn't get those, so its
 * county bus times are only the timetable.
 *
 * It answers the same GraphQL as peatus.ee. It has each of peatus.ee's trips twice: with "1:" for "estonia:", only
 * the timetable, and the copy its live times are on, which also has a number in front ("1:74_ATL_…" for
 * "estonia:ATL_…"), and is the one its stops list. Its stops are by the code on the sign for some ("1:21207-1" is
 * peatus.ee's "estonia:1634") and by peatus.ee's number for others ("1:138215"), so a stop is asked for both ways at
 * once.
 *
 * Blocking: call it off the main thread.
 */
class RidangoLive {

    /** One of a trip's stops as Ridango has it: times in seconds from the service day. */
    class StopTime(val code: String?, val scheduled: Int, val expected: Int, val isRealtime: Boolean)

    /**
     * The live times of [departures] (peatus.ee's, from [stop]) Ridango has, as seconds from their service day, by
     * service day and trip id. Those it has no live time for are left out.
     */
    fun departures(stop: Stop, departures: List<Departure>): Map<Pair<Long, String>, Int> {
        if (departures.isEmpty()) return emptyMap()
        // With room on both sides: they're matched by trip, and a late or early bus may be outside the timetable's.
        val start = departures.minOf { it.serviceDay + it.scheduled } - MARGIN_S
        val end = departures.maxOf { it.serviceDay + it.scheduled } + MARGIN_S
        val ids = listOfNotNull(stop.code, stop.id.substringAfter(':').takeIf { it.isNotEmpty() }).distinct()
        val stops = ids.withIndex().joinToString(" ") { (i, id) ->
            "s$i: stop(id: \"$FEED:$id\") { code $DEPARTURES }"
        }
        val variables = JSONObject().put("start", start).put("range", (end - start).toInt()).put("n", MAX_DEPARTURES)
        val data = request("query(\$start: Long!, \$range: Int!, \$n: Int!) { $stops }", variables)
        // A number that's another stop's in Ridango's list won't have this one's code.
        val json = ids.indices.mapNotNull { data.optJSONObject("s$it") }
            .firstOrNull { stop.code == null || it.optNullableString("code") == stop.code }
            ?: return emptyMap()
        return json.optJSONArray("stoptimesWithoutPatterns")?.objects().orEmpty()
            .filter { it.optBoolean("realtime") }
            .associate {
                (it.getLong("serviceDay") to tripId(it.getJSONObject("trip").getString("gtfsId"))) to
                    it.getInt("realtimeDeparture")
            }
    }

    /** "estonia:ATL_…" for Ridango's "1:74_ATL_…" (or "1:ATL_…"), noting the number in front for [trip]. */
    private fun tripId(ridangoId: String): String {
        val bare = ridangoId.substringAfter(':')
        val prefix = LIVE_PREFIX.find(bare)?.value ?: return "estonia:$bare"
        if (prefix !in prefixes) prefixes = prefixes + prefix
        return "estonia:" + bare.removePrefix(prefix)
    }

    /**
     * Every stop of trip [tripId] (peatus.ee's) on [date] (yyyyMMdd), or null if Ridango doesn't know it. Asks for
     * each of the ids the trip may have there at once (with each of [prefixes] in front, and without), and takes the
     * one with live times, if any.
     */
    fun trip(tripId: String, date: String): List<StopTime>? {
        val bare = tripId.substringAfter(':')
        val ids = (prefixes.map { "$FEED:$it$bare" } + "$FEED:$bare").distinct()
        val trips = ids.withIndex().joinToString(" ") { (i, id) ->
            "t$i: trip(id: \"$id\") { stoptimesForDate(serviceDate: \$date) { $TRIP_STOP } }"
        }
        val data = request("query(\$date: String!) { $trips }", JSONObject().put("date", date))
        val found = ids.indices.mapNotNull { data.optJSONObject("t$it")?.getJSONArray("stoptimesForDate")?.objects() }
        val json = found.firstOrNull { times -> times.any { it.optBoolean("realtime") } } ?: found.firstOrNull()
            ?: return null
        return json.map {
            StopTime(
                it.getJSONObject("stop").optNullableString("code"),
                it.getInt("scheduledDeparture"),
                it.getInt("realtimeDeparture"),
                it.optBoolean("realtime"),
            )
        }
    }

    private fun request(query: String, variables: JSONObject): JSONObject {
        val field = FIELD.find(query)?.groupValues?.get(1) ?: "query"
        return Analytics.timed("http.client", "POST ridango.cloud $field") { graphQL(ENDPOINT, "Ridango", query, variables) }
    }

    companion object {
        private const val ENDPOINT = "https://wmb-otp-peutk.eu-prod.ridango.cloud/otp/routers/1/index/graphql"

        /** Ridango's id of the national feed, where peatus.ee has "estonia". */
        private const val FEED = "1"

        /** A query's top field after its variables: "stop" for "s0: stop(…)", "trip". */
        private val FIELD = Regex("""\{\s*(?:\w+:\s*)?(\w+)\(""")

        private const val DEPARTURES =
            "stoptimesWithoutPatterns(startTime: \$start, timeRange: \$range, numberOfDepartures: \$n)" +
                " { serviceDay realtimeDeparture realtime trip { gtfsId } }"

        private const val TRIP_STOP = "scheduledDeparture realtimeDeparture realtime stop { code }"

        /** The number in front of the trip ids live times are on: "74_" in "74_ATL_…". */
        private val LIVE_PREFIX = Regex("""^\d+_""")

        /**
         * The numbers in front of trip ids its stops have listed so far, for [trip]: "74_" for Harjumaa's, the only
         * one seen yet.
         */
        @Volatile
        private var prefixes = setOf("74_")

        private const val MARGIN_S = 30 * 60

        /** Plenty for a busy stop's couple of hours of county buses. */
        private const val MAX_DEPARTURES = 200
    }
}
