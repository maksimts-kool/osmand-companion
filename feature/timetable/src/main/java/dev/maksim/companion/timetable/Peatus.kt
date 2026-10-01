package dev.maksim.companion.timetable

import dev.maksim.companion.core.Analytics
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
    /** The routes serving it, in [RouteOrder]. */
    val lines: List<Line> = emptyList(),
    /** Upcoming departures, soonest first; only filled by queries that ask for them. */
    val departures: List<Departure> = emptyList(),
) {
    /** Short names of the routes serving it. */
    val routes: List<String> get() = lines.map { it.name }
}

/** A route as a stop lists it: its short name ("5", "R32") and mode (see [PeatusClient.modeOf]). */
data class Line(val name: String, val mode: String)

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
    /**
     * Whether [expected] comes from a vehicle's live position rather than the timetable: peatus.ee's, or for
     * Tallinn's city lines the city's own ([TallinnLive]).
     */
    val isRealtime: Boolean,
    /** When the trip leaves its first stop, seconds from [serviceDay]; null if peatus.ee doesn't say. */
    val tripStart: Int? = null,
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
    /** The route's GTFS id, e.g. "estonia:tallinna-lin_bus_41-1". */
    val routeId: String,
    val route: String,
    val mode: String,
    val headsign: String,
    val longName: String,
    val serviceDay: Long,
    val stops: List<TripStop>,
    /**
     * With live times ([PeatusClient.live]): the first of [stops] the vehicle hasn't left yet, as far as the
     * city's feed knows; -1 without, when only the times say where it is.
     */
    val liveFrom: Int = -1,
)

data class LatLon(val lat: Double, val lon: Double)

data class TripStop(val stop: Stop, val scheduled: Int, val expected: Int, val isRealtime: Boolean) {
    fun time(serviceDay: Long): Long = (serviceDay + expected) * 1000
}

/**
 * Client for peatus.ee, the Estonian Transport Administration's journey planner. It runs OpenTripPlanner
 * on the national GTFS feed (every bus, tram, train and ferry in Estonia), so one GraphQL endpoint answers
 * "which stops are here", "what leaves next" and "what's the whole timetable". Its live times are only where
 * operators send them; Tallinn's city buses, trolleybuses and trams get theirs from the city ([TallinnLive]) instead.
 *
 * Blocking: call it off the main thread.
 */
class PeatusClient {

    private val tallinn = TallinnLive()

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
            query(${'$'}id: String!, ${'$'}n: Int!, ${'$'}start: Long!, ${'$'}late: Int!) {
              stop(id: ${'$'}id) { $STOP $NEXT $LATE }
            }
        """
        val data = request(query, lateVariables().put("id", id).put("n", departures + EXTRA_FOR_ARRIVALS))
        return data.optJSONObject("stop")?.let { withLive(it, departures) }
    }

    /**
     * One stop with its departures in the next [seconds] (going by the timetable), the soonest [max] of them; null
     * if peatus.ee doesn't know it. By default every one, however many: a count alone would cut a busy stop's hour
     * short, as some have 40 an hour.
     */
    fun stopWithin(id: String, seconds: Int, max: Int = ALL_DEPARTURES): Stop? {
        val query = """
            query(${'$'}id: String!, ${'$'}range: Int!, ${'$'}n: Int!, ${'$'}start: Long!, ${'$'}late: Int!) {
              stop(id: ${'$'}id) { $STOP $WITHIN $LATE }
            }
        """
        val n = if (max >= ALL_DEPARTURES) ALL_DEPARTURES else max + EXTRA_FOR_ARRIVALS
        val data = request(query, lateVariables().put("id", id).put("range", seconds).put("n", n))
        return data.optJSONObject("stop")?.let { withLive(it, max) }
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
                  pattern { headsign route { $ROUTE longName } stops { gtfsId name } }
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
            val info = pattern.getJSONObject("pattern")
            val route = info.getJSONObject("route")
            val shortName = route.getString("shortName")
            val mode = modeOf(route)
            val stops = info.optJSONArray("stops")?.objects().orEmpty()
            val first = stops.firstOrNull()
            val last = stops.lastOrNull()
            // Where the pattern ends is only an arrival, unless it's a loop that also starts here.
            val endsHere = last?.getString("gtfsId") == stop.id
            val startsHere = first?.getString("gtfsId") == stop.id
            if (endsHere && !startsHere) continue
            // On a loop the feed lists this stop at both ends of each trip; the first one is the departure.
            val seenTrips = HashSet<String>()
            for (time in pattern.getJSONArray("stoptimes").objects().sortedBy { it.getInt("scheduledDeparture") }) {
                val tripId = time.getJSONObject("trip").getString("gtfsId")
                if (!seenTrips.add(tripId)) continue
                val headsign = destination(
                    time.optNullableString("headsign") ?: info.optNullableString("headsign"),
                    first?.getString("name"),
                    last?.getString("name"),
                )
                if (last == null && isArrival(stop.name, headsign)) continue
                // Several patterns (e.g. short turns) can share route and headsign; show them as one.
                val key = Triple(shortName, headsign, mode)
                byDirection.getOrPut(key) { mutableListOf() } += time.getInt("scheduledDeparture") to tripId
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
        val stopName = { time: JSONObject? -> time?.getJSONObject("stop")?.getString("name") }
        return Trip(
            id = json.getString("gtfsId"),
            routeId = route.getString("gtfsId"),
            route = route.getString("shortName"),
            mode = modeOf(route),
            headsign = destination(
                json.optNullableString("tripHeadsign"), stopName(times.firstOrNull()), stopName(times.lastOrNull()),
            ),
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

    /**
     * [trip] at the times its vehicle gives, for a Tallinn city line ([TallinnLive]); as it is for any other, or
     * when the city's feed doesn't answer. The feed is per stop, and forgets a stop once the vehicle has left it, so
     * the first stop it still lists the trip at is where the vehicle is headed ([Trip.liveFrom]): the stops before
     * are behind it. Until it has left the first, the trip isn't on its way, and whatever the feed says of it is
     * its vehicle's guess from the trip before: it's the timetable until then. The vehicle's delay is the same at
     * all the stops ahead of it, so if one stop says the trip has a vehicle ([TallinnLive.Times.isLive]), every
     * stop it lists the trip at has its live time, on time too.
     *
     * So it only asks about enough stops to find that first one ([findVehicle]), among those the vehicle may be at
     * or near: from [LATE_S] behind the timetable to [TRIP_AHEAD_S] ahead of it. The stops ahead it didn't ask
     * about, in that window, are live with the delay of the one before, as the feed would have them; those it says
     * nothing about, or further ahead, get that delay on their timetable.
     */
    fun live(trip: Trip): Trip {
        if (!TallinnLive.covers(trip.routeId)) return trip
        val now = System.currentTimeMillis() / 1000
        val near = trip.stops.indices.filter { trip.serviceDay + trip.stops[it].scheduled in now - LATE_S..now + TRIP_AHEAD_S }
        if (near.isEmpty()) return trip
        val feed = HashMap<String, TallinnLive.Times>()
        val asked = HashSet<Int>()
        fun ask(stops: IntRange) {
            asked += stops
            feed += tallinn.departures(stops.map { trip.stops[it].stop }.filter { it.id !in feed }.distinctBy { it.id })
        }
        fun listed(i: Int) = feed[trip.stops[i].stop.id]
            ?.find(trip.route, trip.mode, trip.headsign, trip.serviceDay + trip.stops[i].scheduled)
        fun hasVehicle() = asked.any { i ->
            listed(i)?.let { feed.getValue(trip.stops[i].stop.id).isLive(it, started = true) } == true
        }

        val first = findVehicle(near.first()..near.last(), trip.stops.indexOfFirst { trip.serviceDay + it.scheduled >= now }, ::ask) {
            listed(it) != null
        }
        // Still at its first stop, or not due to leave it yet.
        if (first == null || first <= 0 || trip.serviceDay + trip.stops.first().scheduled > now) return trip
        // A vehicle exactly on time only shows in the stops' lists as a whole: ask further ahead until one does.
        while (!hasVehicle()) {
            val next = (asked.max() + 1).takeIf { it <= near.last() } ?: return trip
            ask(next..minOf(next + TRIP_BATCH - 1, near.last()))
        }
        var delay = 0
        val stops = trip.stops.mapIndexed { i, stop ->
            val time = listed(i)
            when {
                i < first -> stop
                time != null -> {
                    val scheduled = (time.scheduled - trip.serviceDay).toInt()
                    val expected = (time.expected - trip.serviceDay).toInt()
                    delay = expected - scheduled
                    // The city's timetable is to the second, so how late it is comes out right.
                    stop.copy(scheduled = scheduled, expected = expected, isRealtime = true)
                }
                i !in asked && i in near.first()..near.last() -> stop.copy(expected = stop.scheduled + delay, isRealtime = true)
                else -> stop.copy(expected = stop.scheduled + delay)
            }
        }
        return trip.copy(stops = stops, liveFrom = first)
    }

    /**
     * The first stop in [range] that [listed] says the feed lists, where the stops before it aren't: the vehicle's
     * next stop. Has [fetch] ask about [TRIP_BATCH] stops at a time (the feed answers those at once), starting just before
     * [due], the stop it's due at by the timetable, and going back while the first it lists has one before it that
     * wasn't asked about, or on while it lists none. Null if it lists none from there on.
     */
    private fun findVehicle(range: IntRange, due: Int, fetch: (IntRange) -> Unit, listed: (Int) -> Boolean): Int? {
        val asked = sortedSetOf<Int>()
        fun ask(stops: IntRange) {
            fetch(stops)
            asked += stops
        }
        val start = (if (due < 0) range.last else due - 1).coerceIn(range)
        ask(start..minOf(start + TRIP_BATCH - 1, range.last))
        while (true) {
            val first = asked.firstOrNull(listed)
            when {
                first == null -> {
                    val next = asked.last() + 1
                    if (next > range.last) return null
                    ask(next..minOf(next + TRIP_BATCH - 1, range.last))
                }
                first == range.first || first - 1 in asked -> return first
                else -> ask(maxOf(range.first, first - TRIP_BATCH)..first - 1)
            }
        }
    }

    /**
     * The way a trip goes, as the operator drew it for the feed (GTFS shapes). Falls back to straight lines
     * between the stops when the feed has no shape for it.
     */
    fun tripShape(tripId: String): List<LatLon> {
        val query = """
            query(${'$'}id: String!) { trip(id: ${'$'}id) { tripGeometry { points } stops { lat lon } } }
        """
        val json = request(query, JSONObject().put("id", tripId)).optJSONObject("trip") ?: return emptyList()
        json.optJSONObject("tripGeometry")?.optNullableString("points")?.let { points ->
            Polyline.decode(points).takeIf { it.size >= 2 }?.let { return it }
        }
        return json.optJSONArray("stops")?.objects().orEmpty().map { LatLon(it.getDouble("lat"), it.getDouble("lon")) }
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
            lines = routes.map { Line(it.getString("shortName"), modeOf(it)) }.distinctBy { it.name }
                .sortedWith(compareBy(RouteOrder) { it.name }),
            departures = departuresOf(json, "stoptimesWithoutPatterns").take(departures),
        )
    }

    /** The departures in the stop's [field], but for the ends of lines. */
    private fun departuresOf(json: JSONObject, field: String): List<Departure> =
        json.optJSONArray(field)?.objects().orEmpty()
            .filterNot { isArrival(json.getString("gtfsId"), json.getString("name"), it) }
            .map(::departureOf)

    /**
     * The stop in [json] with its next [max] departures, those of Tallinn's city lines at the times their
     * vehicles give ([TallinnLive]), which peatus.ee doesn't have. It only knows the timetable, so one that's
     * running late has gone from its next departures by now: that's what the [LATE] ones are for. Without the
     * city's feed, the timetable it is.
     */
    private fun withLive(json: JSONObject, max: Int): Stop {
        val stop = stopOf(json, max)
        val routes = json.optJSONArray("routes")?.objects().orEmpty()
        if (routes.none { TallinnLive.covers(it.optString("gtfsId")) }) return stop
        val live = runCatching { tallinn.departures(stop) }.getOrNull() ?: return stop
        val now = System.currentTimeMillis()
        val departures = (departuresOf(json, "late") + departuresOf(json, "stoptimesWithoutPatterns"))
            .distinctBy { it.serviceDay to it.tripId }
            .map { departure ->
                if (departure.isRealtime) return@map departure
                val serviceDay = departure.serviceDay
                val time = live.find(departure.route, departure.mode, departure.headsign, serviceDay + departure.scheduled)
                    ?: return@map departure
                // Only once the trip is on its way: before, the feed's times for it are its vehicle's guess from the
                // trip before. It left its first stop when that was due, as late as the feed has it here.
                val delay = time.expected - time.scheduled
                val started = departure.tripStart?.let { serviceDay + it + delay <= now / 1000 } ?: true
                if (!started || !live.isLive(time, started = true)) return@map departure
                // The city's timetable is to the second, so how late it is comes out right.
                departure.copy(
                    scheduled = (time.scheduled - departure.serviceDay).toInt(),
                    expected = (time.expected - departure.serviceDay).toInt(),
                    isRealtime = true,
                )
            }
            .filter { it.time >= now - LIVE_GRACE_MS }
            .sortedBy { it.time }
        return stop.copy(departures = departures.take(max))
    }

    /** For [LATE]: from [LATE_S] ago until now. */
    private fun lateVariables() =
        JSONObject().put("start", System.currentTimeMillis() / 1000 - LATE_S).put("late", LATE_S)

    private fun departureOf(json: JSONObject): Departure {
        val trip = json.getJSONObject("trip")
        val route = trip.getJSONObject("route")
        return Departure(
            tripId = trip.getString("gtfsId"),
            route = route.getString("shortName"),
            mode = modeOf(route),
            headsign = destination(
                json.optNullableString("headsign") ?: trip.optNullableString("tripHeadsign"),
                trip.optJSONObject("departureStoptime")?.optJSONObject("stop")?.optNullableString("name"),
                trip.optJSONObject("arrivalStoptime")?.optJSONObject("stop")?.optNullableString("name"),
            ),
            serviceDay = json.getLong("serviceDay"),
            scheduled = json.getInt("scheduledDeparture"),
            expected = json.getInt("realtimeDeparture"),
            isRealtime = json.optBoolean("realtime"),
            tripStart = trip.optJSONObject("departureStoptime")?.takeIf { it.has("scheduledDeparture") }
                ?.getInt("scheduledDeparture"),
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
     * towards the stop itself ("Balti jaam" at Balti jaam). A trip ends here if its last stop is this one, and
     * it doesn't also start here (a loop), or this isn't its start time.
     */
    private fun isArrival(stopId: String, stopName: String, time: JSONObject): Boolean {
        val trip = time.getJSONObject("trip")
        val start = trip.optJSONObject("departureStoptime")
        val end = trip.optJSONObject("arrivalStoptime")?.optJSONObject("stop")
            ?: return isArrival(stopName, time.optNullableString("headsign") ?: trip.optNullableString("tripHeadsign") ?: "")
        if (end.optString("gtfsId") != stopId) return false
        return start?.optJSONObject("stop")?.optString("gtfsId") != stopId ||
            start.optInt("scheduledDeparture") != time.getInt("scheduledDeparture")
    }

    /** For when the feed doesn't say where the trip ends: a trip whose headsign is this stop ends here. */
    private fun isArrival(stopName: String, headsign: String) =
        headsign.isNotEmpty() && baseName(headsign) == baseName(stopName)

    /**
     * Where a trip goes. Usually its headsign, but some operators' feeds (Elron's) leave it out on some trips,
     * or give every trip of a line the same one, so trains from Tallinn to Tapa say "Tallinn". A headsign that's
     * missing, or names where the trip starts rather than where it ends, gives way to the trip's last stop.
     */
    private fun destination(headsign: String?, firstStop: String?, lastStop: String?): String {
        val sign = headsign?.trim().orEmpty()
        if (sign.isEmpty()) return lastStop.orEmpty()
        if (firstStop != null && lastStop != null &&
            baseName(sign) == baseName(firstStop) && baseName(sign) != baseName(lastStop)
        ) return lastStop
        return sign
    }

    private fun baseName(name: String) = name.substringBefore(" (").trim().lowercase(Locale.ROOT)

    /** Timed in Sentry's traces by the query's field (stop, stopsByRadius, …), never its variables. */
    private fun request(query: String, variables: JSONObject): JSONObject {
        val field = FIELD.find(query)?.groupValues?.get(1) ?: "query"
        return Analytics.timed("http.client", "POST api.peatus.ee $field") { post(query, variables) }
    }

    private fun post(query: String, variables: JSONObject): JSONObject {
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

        /** A query's top field: the first name after its opening brace. */
        private val FIELD = Regex("""\{\s*(\w+)""")

        /** Arrivals at the end of a line are dropped after the fact, so ask for a few more. */
        private const val EXTRA_FOR_ARRIVALS = 4

        /** peatus.ee's colors for city buses: municipal (Tallinn, Tartu, Narva...) and commercial ones. */
        private val CITY_BUS_COLORS = setOf("de2c42", "bd4819")

        /** What [modeOf] needs to know about a route. */
        private const val ROUTE = "gtfsId shortName mode color"
        private const val STOP = "gtfsId name code lat lon vehicleMode routes { $ROUTE }"
        private const val DEPARTURE = "scheduledDeparture realtimeDeparture realtime serviceDay headsign" +
            " trip { gtfsId tripHeadsign route { $ROUTE }" +
            " departureStoptime { scheduledDeparture stop { gtfsId name } } arrivalStoptime { stop { gtfsId name } } }"
        private const val NEXT = "stoptimesWithoutPatterns(numberOfDepartures: \$n, omitNonPickups: true) { $DEPARTURE }"

        /** At most \$n departures in the next \$range seconds; without a count, peatus.ee gives 5. */
        private const val WITHIN =
            "stoptimesWithoutPatterns(timeRange: \$range, numberOfDepartures: \$n, omitNonPickups: true) { $DEPARTURE }"

        /** Departures that were due in the last \$late seconds, from \$start: see [withLive]. */
        private const val LATE =
            "late: stoptimesWithoutPatterns(startTime: \$start, timeRange: \$late, numberOfDepartures: 100," +
                " omitNonPickups: true) { $DEPARTURE }"

        /** How late a bus can be and still show; later than that is rare, and it may just not be coming. */
        private const val LATE_S = 20 * 60

        /**
         * How far ahead of the timetable [live] asks about a trip's stops: the feed predicts up to about an hour
         * ahead, so this covers whatever it can say, late vehicles included. Beyond, the delay carries on.
         */
        private const val TRIP_AHEAD_S = 90 * 60

        /** Stops [live] asks the feed about at a time, while it looks for the vehicle. */
        private const val TRIP_BATCH = 6

        /** One that left a few seconds ago is probably still at the stop. */
        private const val LIVE_GRACE_MS = 30_000L

        /** For [stopWithin]: as many as there are. */
        const val ALL_DEPARTURES = 1000
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

/** Google's encoded polyline format, which OpenTripPlanner uses for geometry. */
internal object Polyline {
    fun decode(encoded: String): List<LatLon> {
        val points = ArrayList<LatLon>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            for (i in 0..1) {
                var shift = 0
                var result = 0
                var byte: Int
                do {
                    byte = encoded[index++].code - 63
                    result = result or (byte and 0x1F shl shift)
                    shift += 5
                } while (byte >= 0x20 && index < encoded.length)
                val delta = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                if (i == 0) lat += delta else lon += delta
            }
            points += LatLon(lat / 1e5, lon / 1e5)
        }
        return points
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
