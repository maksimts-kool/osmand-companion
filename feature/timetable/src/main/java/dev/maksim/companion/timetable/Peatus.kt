package dev.maksim.companion.timetable

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** A stop, or one side of the street of it: most stops come in pairs with the same name. */
data class Stop(
    /** GTFS id with the feed prefix, e.g. "estonia:1626". */
    val id: String,
    val name: String,
    /** The code printed on the stop sign, e.g. "21105-7". */
    val code: String?,
    val lat: Double,
    val lon: Double,
    /**
     * BUS, TRAM, RAIL, FERRY...; null when nothing serves the stop anymore. A bus stop only trolleybuses or only
     * regional buses serve says so instead: [PeatusClient.TROLLEYBUS], [PeatusClient.REGIONAL].
     */
    val mode: String?,
    /** Short names of the routes serving it. */
    val routes: List<String> = emptyList(),
    /** Upcoming departures, soonest first; only filled by queries that ask for them. */
    val departures: List<Departure> = emptyList(),
)

data class Departure(
    val tripId: String,
    /** Route short name, e.g. "5" or "T4". */
    val route: String,
    val mode: String,
    val headsign: String,
    /** Start of the service day, epoch seconds; the times below count from it and can pass 24 h. */
    val serviceDay: Long,
    val scheduled: Int,
    val expected: Int,
    /** Whether [expected] comes from a vehicle's live position rather than the timetable. */
    val isRealtime: Boolean,
) {
    val time: Long get() = (serviceDay + expected) * 1000
}

/** When one route, in one direction, leaves a stop on one day. */
data class RouteDay(
    val route: String,
    val mode: String,
    val headsign: String,
    val longName: String,
    val serviceDay: Long,
    /** Seconds from [serviceDay], ascending, with the trip each one belongs to. */
    val times: List<Pair<Int, String>>,
)

data class Trip(
    val id: String,
    val route: String,
    val mode: String,
    val headsign: String,
    val longName: String,
    val serviceDay: Long,
    val stops: List<TripStop>,
)

data class TripStop(val stop: Stop, val scheduled: Int, val expected: Int, val isRealtime: Boolean) {
    fun time(serviceDay: Long): Long = (serviceDay + expected) * 1000
}

/**
 * Client for peatus.ee, the Estonian Transport Administration's journey planner. It runs OpenTripPlanner
 * on the national GTFS feed (every bus, tram, train and ferry in Estonia), so one GraphQL endpoint answers
 * "which stops are here", "what leaves next" (with live times where the operator provides them, e.g. Tallinn)
 * and "what's the whole timetable".
 *
 * Blocking: call it off the main thread.
 */
class PeatusClient {

    /** Stops within [radius] m, nearest first, each with its next [departures] departures. */
    fun nearbyStops(lat: Double, lon: Double, radius: Int, max: Int, departures: Int): List<Stop> {
        val query = """
            query(${'$'}lat: Float!, ${'$'}lon: Float!, ${'$'}radius: Int!, ${'$'}first: Int!, ${'$'}n: Int!) {
              stopsByRadius(lat: ${'$'}lat, lon: ${'$'}lon, radius: ${'$'}radius, first: ${'$'}first) {
                edges { node { stop { $STOP $NEXT } } }
              }
            }
        """
        val data = request(
            query,
            JSONObject().put("lat", lat).put("lon", lon).put("radius", radius).put("first", max)
                .put("n", departures + EXTRA_FOR_ARRIVALS),
        )
        val edges = data.getJSONObject("stopsByRadius").getJSONArray("edges")
        return edges.objects()
            .map { stopOf(it.getJSONObject("node").getJSONObject("stop"), departures) }
            .filter { it.mode != null }
            .distinctBy { it.id }
    }

    /** One stop with its next [departures] departures, or null if peatus.ee doesn't know it. */
    fun stop(id: String, departures: Int): Stop? {
        val query = """
            query(${'$'}id: String!, ${'$'}n: Int!) { stop(id: ${'$'}id) { $STOP $NEXT } }
        """
        val data = request(query, JSONObject().put("id", id).put("n", departures + EXTRA_FOR_ARRIVALS))
        return data.optJSONObject("stop")?.let { stopOf(it, departures) }
    }

    /** Stops whose name contains [name], in Estonia and still served. */
    fun searchStops(name: String, max: Int): List<Stop> {
        val query = """
            query(${'$'}name: String!, ${'$'}max: Int!) {
              stops(name: ${'$'}name, maxResults: ${'$'}max) { $STOP }
            }
        """
        val data = request(query, JSONObject().put("name", name).put("max", max))
        return data.getJSONArray("stops").objects()
            .map { stopOf(it, 0) }
            .filter { it.mode != null && Estonia.contains(it.lat, it.lon) }
    }

    /** Everything that leaves [stopId] on [date] (yyyyMMdd), one entry per route and direction. */
    fun timetable(stopId: String, date: String): Pair<Stop, List<RouteDay>>? {
        val query = """
            query(${'$'}id: String!, ${'$'}date: String!) {
              stop(id: ${'$'}id) {
                $STOP
                stoptimesForServiceDate(date: ${'$'}date, omitNonPickups: true) {
                  pattern { headsign route { $ROUTE longName } }
                  stoptimes { scheduledDeparture serviceDay headsign trip { gtfsId } }
                }
              }
            }
        """
        val json = request(query, JSONObject().put("id", stopId).put("date", date)).optJSONObject("stop")
            ?: return null
        val stop = stopOf(json, 0)
        val byDirection = LinkedHashMap<Triple<String, String, String>, MutableList<Pair<Int, String>>>()
        val details = HashMap<Triple<String, String, String>, Pair<String, Long>>()
        for (pattern in json.getJSONArray("stoptimesForServiceDate").objects()) {
            val route = pattern.getJSONObject("pattern").getJSONObject("route")
            val shortName = route.getString("shortName")
            val mode = modeOf(route)
            for (time in pattern.getJSONArray("stoptimes").objects()) {
                val headsign = time.optNullableString("headsign")
                    ?: pattern.getJSONObject("pattern").optNullableString("headsign")
                    ?: ""
                if (isArrival(stop.name, headsign)) continue
                // Several patterns (e.g. short turns) can share route and headsign; show them as one.
                val key = Triple(shortName, headsign, mode)
                byDirection.getOrPut(key) { mutableListOf() } +=
                    time.getInt("scheduledDeparture") to time.getJSONObject("trip").getString("gtfsId")
                details[key] = route.optString("longName") to time.getLong("serviceDay")
            }
        }
        val days = byDirection.map { (key, times) ->
            val (longName, serviceDay) = details.getValue(key)
            RouteDay(key.first, key.third, key.second, longName, serviceDay, times.sortedBy { it.first })
        }.sortedWith(compareBy(RouteOrder) { it.route })
        return stop to days
    }

    /** One run of a route on [date] (yyyyMMdd): every stop it calls at, with times. */
    fun trip(tripId: String, date: String): Trip? {
        val query = """
            query(${'$'}id: String!, ${'$'}date: String!) {
              trip(id: ${'$'}id) {
                gtfsId tripHeadsign route { $ROUTE longName }
                stoptimesForDate(serviceDate: ${'$'}date) {
                  scheduledDeparture realtimeDeparture realtime serviceDay stop { $STOP }
                }
              }
            }
        """
        val json = request(query, JSONObject().put("id", tripId).put("date", date)).optJSONObject("trip")
            ?: return null
        val route = json.getJSONObject("route")
        val times = json.getJSONArray("stoptimesForDate").objects()
        return Trip(
            id = json.getString("gtfsId"),
            route = route.getString("shortName"),
            mode = modeOf(route),
            headsign = json.optNullableString("tripHeadsign") ?: "",
            longName = route.optString("longName"),
            serviceDay = times.firstOrNull()?.getLong("serviceDay") ?: 0L,
            stops = times.map {
                TripStop(
                    stopOf(it.getJSONObject("stop"), 0),
                    it.getInt("scheduledDeparture"),
                    it.getInt("realtimeDeparture"),
                    it.optBoolean("realtime"),
                )
            },
        )
    }

    private fun stopOf(json: JSONObject, departures: Int): Stop {
        val name = json.getString("name")
        val routes = json.optJSONArray("routes")?.objects().orEmpty()
        val vehicleMode = json.optNullableString("vehicleMode")
        // peatus.ee calls every road vehicle a bus; a stop gets the finer kind only if all its routes share it.
        val kind = routes.map(::modeOf).distinct().singleOrNull()
        return Stop(
            id = json.getString("gtfsId"),
            name = name,
            code = json.optNullableString("code"),
            lat = json.getDouble("lat"),
            lon = json.getDouble("lon"),
            mode = kind?.takeIf { vehicleMode == "BUS" && (it == TROLLEYBUS || it == REGIONAL) } ?: vehicleMode,
            routes = routes.map { it.getString("shortName") }.distinct().sortedWith(RouteOrder),
            departures = json.optJSONArray("stoptimesWithoutPatterns")?.objects().orEmpty()
                .map(::departureOf)
                .filterNot { isArrival(name, it.headsign) }
                .take(departures),
        )
    }

    private fun departureOf(json: JSONObject): Departure {
        val trip = json.getJSONObject("trip")
        val route = trip.getJSONObject("route")
        return Departure(
            tripId = trip.getString("gtfsId"),
            route = route.getString("shortName"),
            mode = modeOf(route),
            headsign = json.optNullableString("headsign") ?: trip.optNullableString("tripHeadsign") ?: "",
            serviceDay = json.getLong("serviceDay"),
            scheduled = json.getInt("scheduledDeparture"),
            expected = json.getInt("realtimeDeparture"),
            isRealtime = json.optBoolean("realtime"),
        )
    }

    /**
     * The route's mode, except that buses are split further. The feed marks every trolleybus and every bus as BUS;
     * trolleybuses are told apart by their id (Tallinn's "tallinna-lin_trol_72"), regional buses by peatus.ee's
     * route color, which is red for city buses and something else for county and long-distance lines.
     */
    private fun modeOf(route: JSONObject): String {
        val mode = route.optString("mode")
        if (mode != "BUS") return mode
        return when {
            "_trol_" in route.optString("gtfsId") -> TROLLEYBUS
            route.optNullableString("color")?.lowercase(Locale.ROOT)?.let { it !in CITY_BUS_COLORS } == true -> REGIONAL
            else -> mode
        }
    }

    /**
     * The feed lets you "board" at a trip's last stop, so the end of every line would show up as a departure
     * towards the stop itself ("Balti jaam" at Balti jaam). A trip whose headsign is this stop ends here.
     */
    private fun isArrival(stopName: String, headsign: String) =
        headsign.isNotEmpty() && baseName(headsign) == baseName(stopName)

    private fun baseName(name: String) = name.substringBefore(" (").trim().lowercase(Locale.ROOT)

    private fun request(query: String, variables: JSONObject): JSONObject {
        val body = JSONObject().put("query", query.trimIndent()).put("variables", variables).toString()
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray()) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("peatus.ee: HTTP $code")
            val json = try {
                JSONObject(text)
            } catch (e: org.json.JSONException) {
                throw IOException("peatus.ee: unreadable answer", e)
            }
            json.optJSONArray("errors")?.let { errors ->
                throw IOException("peatus.ee: " + errors.objects().joinToString { it.optString("message") })
            }
            return json.getJSONObject("data")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** Our own modes, alongside OpenTripPlanner's: see [modeOf]. */
        const val TROLLEYBUS = "TROLLEYBUS"
        const val REGIONAL = "REGIONAL"

        private const val ENDPOINT = "https://api.peatus.ee/routing/v1/routers/estonia/index/graphql"

        /** Arrivals at the end of a line are dropped after the fact, so ask for a few more. */
        private const val EXTRA_FOR_ARRIVALS = 4

        /** peatus.ee's colors for city buses: municipal (Tallinn, Tartu, Narva...) and commercial ones. */
        private val CITY_BUS_COLORS = setOf("de2c42", "bd4819")

        /** What [modeOf] needs to know about a route. */
        private const val ROUTE = "gtfsId shortName mode color"
        private const val STOP = "gtfsId name code lat lon vehicleMode routes { $ROUTE }"
        private const val NEXT = "stoptimesWithoutPatterns(numberOfDepartures: \$n, omitNonPickups: true) {" +
            " scheduledDeparture realtimeDeparture realtime serviceDay headsign" +
            " trip { gtfsId tripHeadsign route { $ROUTE } } }"
    }
}

/** Route names in the order people expect: 2, 5, 17, 17A, 40, T3... */
object RouteOrder : Comparator<String> {
    private val number = Regex("^(\\D*)(\\d+)(.*)$")

    override fun compare(a: String, b: String): Int {
        val ma = number.find(a)
        val mb = number.find(b)
        if (ma == null || mb == null) return a.compareTo(b)
        val (pa, na, sa) = ma.destructured
        val (pb, nb, sb) = mb.destructured
        return compareValuesBy(pa to na, pb to nb, { it.first }, { it.second.toLong() })
            .takeIf { it != 0 } ?: sa.compareTo(sb)
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
