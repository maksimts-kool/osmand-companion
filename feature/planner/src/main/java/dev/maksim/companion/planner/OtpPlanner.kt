package dev.maksim.companion.planner

import dev.maksim.companion.core.Analytics
import dev.maksim.companion.timetable.Estonia
import dev.maksim.companion.timetable.PeatusClient
import dev.maksim.companion.timetable.Polyline
import dev.maksim.companion.timetable.TallinnLive
import dev.maksim.companion.timetable.graphQL
import dev.maksim.companion.timetable.objects
import dev.maksim.companion.timetable.optNullableString
import org.json.JSONObject
import java.util.Locale

/**
 * Asks an OpenTripPlanner for itineraries: Ridango's (the planner behind iil.pilet.ee, OpenTripPlanner 2, with
 * Harjumaa's county buses at their live times) or peatus.ee's (OpenTripPlanner 1). Both have Estonia's national
 * feed and answer the same `plan` query. Neither has Tallinn's city lines live: [LiveRetimer] does that after.
 *
 * Ridango has its own ids for what peatus.ee calls "estonia:…" ([peatusId]); everything here is in peatus.ee's, so
 * the rest of the app knows the stops and trips.
 *
 * Blocking: call it off the main thread.
 */
class OtpPlanner(val source: Source) {

    private val endpoint = when (source) {
        Source.RIDANGO -> "https://wmb-otp-peutk.eu-prod.ridango.cloud/otp/routers/1/index/graphql"
        else -> "https://api.peatus.ee/routing/v1/routers/estonia/index/graphql"
    }
    private val host = if (source == Source.RIDANGO) "ridango.cloud" else "api.peatus.ee"

    /** Up to [count] itineraries from [from] to [to], leaving at [time] (epoch ms), or arriving by it. */
    fun plan(from: Place, to: Place, time: Long, arriveBy: Boolean, count: Int): List<Itinerary> {
        val variables = JSONObject()
            .put("fromLat", from.lat).put("fromLon", from.lon)
            .put("toLat", to.lat).put("toLon", to.lon)
            .put("date", Estonia.format("yyyy-MM-dd", time, Locale.ROOT))
            .put("time", Estonia.format("HH:mm:ss", time, Locale.ROOT))
            .put("n", count)
            .put("arriveBy", arriveBy)
        val data = Analytics.timed("http.client", "POST $host plan") {
            graphQL(endpoint, if (source == Source.RIDANGO) "Ridango" else "peatus.ee", QUERY, variables)
        }
        return data.getJSONObject("plan").optJSONArray("itineraries")?.objects().orEmpty()
            .mapNotNull { runCatching { itinerary(it, from, to) }.getOrNull() }
    }

    private fun itinerary(json: JSONObject, from: Place, to: Place): Itinerary? {
        val legs = json.getJSONArray("legs").objects().map { leg(it, from, to) }
        return legs.takeIf { it.isNotEmpty() }?.let { Itinerary(it, source).walksJoined() }
    }

    private fun leg(json: JSONObject, origin: Place, destination: Place): Leg {
        val start = json.getLong("startTime")
        val end = json.getLong("endTime")
        val shape = json.optJSONObject("legGeometry")?.optNullableString("points")?.let { Polyline.decode(it) }.orEmpty()
        val distance = json.optDouble("distance", 0.0)
        val from = json.getJSONObject("from")
        val to = json.getJSONObject("to")
        if (json.optString("mode") == "WALK") {
            return Leg(null, call(from, start, 0, false, origin, destination), call(to, end, 0, false, origin, destination), shape = shape, distance = distance)
        }
        val live = json.optBoolean("realTime")
        val departureDelay = if (live) json.optInt("departureDelay") else 0
        val arrivalDelay = if (live) json.optInt("arrivalDelay") else 0
        val route = json.getJSONObject("route")
        val trip = json.optJSONObject("trip")
        val routeId = peatusId(route.getString("gtfsId"))
        val mode = PeatusClient.modeOf(routeId, route.optString("mode"), route.optNullableString("color"))
        val serviceDate = json.optNullableString("serviceDate")?.replace("-", "")
        val tripStart = trip?.optJSONObject("departureStoptime")?.takeIf { it.has("scheduledDeparture") }
            ?.getInt("scheduledDeparture")?.let { seconds -> serviceDate?.let { midnight(it) + seconds * 1000L } }
        val stops = json.optJSONArray("intermediatePlaces")?.objects().orEmpty().mapNotNull { place ->
            place.optLong("arrivalTime").takeIf { it > 0 }?.let { call(place, it, arrivalDelay, live, origin, destination) }
        }
        val longName = route.optNullableString("longName")
        val ride = Ride(
            route = route.optNullableString("shortName") ?: longName.orEmpty(),
            mode = mode,
            headsign = json.optNullableString("headsign") ?: trip?.optNullableString("tripHeadsign")
                ?: longName?.substringAfterLast(" - ")?.trim() ?: stopName(to.optString("name")),
            longName = longName,
            routeId = routeId,
            tripId = trip?.optNullableString("gtfsId")?.let(::peatusTripId),
            serviceDate = serviceDate,
            tripStart = tripStart,
            feed = when {
                live -> LiveFeed.NONE
                TallinnLive.covers(routeId) -> LiveFeed.TALLINN
                mode == PeatusClient.REGIONAL -> LiveFeed.COUNTY
                else -> LiveFeed.NONE
            },
        )
        return Leg(
            ride,
            call(from, start, departureDelay, live, origin, destination),
            call(to, end, arrivalDelay, live, origin, destination),
            stops, shape, distance,
        )
    }

    /** A leg's end, there at [expected] epoch ms, [delay] seconds off the timetable. */
    private fun call(json: JSONObject, expected: Long, delay: Int, live: Boolean, origin: Place, destination: Place): Call {
        val stop = json.optJSONObject("stop")
        val name = json.optString("name")
        return Call(
            name = when (name) {
                "Origin" -> origin.name
                "Destination" -> destination.name
                else -> stopName(name)
            },
            lat = json.getDouble("lat"),
            lon = json.getDouble("lon"),
            scheduled = expected - delay * 1000L,
            expected = expected,
            isLive = live,
            stopId = stop?.optNullableString("gtfsId")?.let(::peatusStopId),
            code = stop?.optNullableString("code"),
        )
    }

    companion object {
        private val QUERY = """
            query(${'$'}fromLat: Float!, ${'$'}fromLon: Float!, ${'$'}toLat: Float!, ${'$'}toLon: Float!, ${'$'}date: String!,
                  ${'$'}time: String!, ${'$'}n: Int!, ${'$'}arriveBy: Boolean!) {
              plan(from: {lat: ${'$'}fromLat, lon: ${'$'}fromLon}, to: {lat: ${'$'}toLat, lon: ${'$'}toLon}, date: ${'$'}date,
                   time: ${'$'}time, numItineraries: ${'$'}n, arriveBy: ${'$'}arriveBy) {
                itineraries {
                  legs {
                    mode startTime endTime realTime departureDelay arrivalDelay distance serviceDate
                    route { gtfsId shortName longName mode color }
                    trip { gtfsId tripHeadsign departureStoptime { scheduledDeparture } }
                    from { name lat lon stop { gtfsId code } }
                    to { name lat lon stop { gtfsId code } }
                    intermediatePlaces { name lat lon arrivalTime stop { gtfsId code } }
                    legGeometry { points }
                  }
                }
              }
            }
        """

        /** Ridango's feed is "1", peatus.ee's "estonia". */
        private const val RIDANGO_FEED = "1:"
        private const val PEATUS_FEED = "estonia:"

        /** "estonia:tallinna-lin_bus_23-1" for Ridango's "1:tallinna-lin_bus_23-1"; peatus.ee's as it is. */
        fun peatusId(id: String): String = if (id.startsWith(RIDANGO_FEED)) PEATUS_FEED + id.removePrefix(RIDANGO_FEED) else id

        /**
         * peatus.ee's trip for Ridango's: "1:estonia-27136" is "estonia:27136"; "1:74_ATL_…" (the copy with live times)
         * and "1:ATL_…" are "estonia:ATL_…".
         */
        fun peatusTripId(id: String): String {
            if (!id.startsWith(RIDANGO_FEED)) return id
            val bare = id.removePrefix(RIDANGO_FEED)
            return PEATUS_FEED + when {
                bare.startsWith("estonia-") -> bare.removePrefix("estonia-")
                else -> bare.replaceFirst(LIVE_COPY, "")
            }
        }

        /**
         * peatus.ee's stop for Ridango's, where it can tell: Ridango has some stops by peatus.ee's number ("1:138215"),
         * others by the code on the sign ("1:21207-1"), which is only the [Call.code].
         */
        fun peatusStopId(id: String): String? {
            if (!id.startsWith(RIDANGO_FEED)) return id
            val bare = id.removePrefix(RIDANGO_FEED)
            return if (bare.isNotEmpty() && bare.all(Char::isDigit)) PEATUS_FEED + bare else null
        }

        /** Ridango names its stops after the zone too: "Pirni (Harju1)". */
        fun stopName(name: String): String = name.replace(ZONE, "")

        /** A zone's name and number in brackets at the end; no stop's own name ends like that. */
        private val ZONE = Regex("""\s*\(\p{L}[\p{L}-]*\d+\)$""")
        private val LIVE_COPY = Regex("""^\d+_""")

        /** Midnight in Estonia starting service date [date] (yyyyMMdd), epoch ms. */
        fun midnight(date: String): Long = Estonia.formatter("yyyyMMdd", Locale.ROOT).parse(date)!!.time
    }
}
