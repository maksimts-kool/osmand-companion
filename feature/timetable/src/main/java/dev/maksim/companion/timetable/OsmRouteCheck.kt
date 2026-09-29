package dev.maksim.companion.timetable

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

/**
 * Checks whether OpenStreetMap, where OsmAnd's maps (and the transport routes it shows when you tap one of its
 * own stops) come from, still has a route the way today's timetable runs it. In Estonia many don't: lines were
 * renumbered (Elron's RE32 is now R32) or rerouted, and OSM hasn't caught up. OsmAnd's downloaded maps are older
 * still, so a route that's wrong in OSM today is wrong in OsmAnd too.
 *
 * Asks the Overpass API for OSM's route relations with that number near the trip and compares their stops with
 * the trip's by name. Blocking: call it off the main thread.
 */
class OsmRouteCheck {

    sealed interface Result {
        /** OSM has the route with the same stops. */
        data object Matches : Result

        /** OSM has no route with this number, or anything like it, around here. */
        data object Missing : Result

        /**
         * OSM's closest route differs: under another number ([osmRef] ≠ the trip's), and/or it lacks [missing] of
         * the trip's stops, and/or it calls at [extra] stops the trip doesn't.
         */
        data class Outdated(
            val osmRef: String,
            val osmName: String?,
            val missing: List<String>,
            val extra: List<String>,
        ) : Result
    }

    /** Throws [IOException] when no Overpass server answered. */
    fun check(trip: Trip): Result {
        val stops = trip.stops.map { it.stop }
        if (stops.size < 2) return Result.Matches
        val routeTypes = OSM_ROUTE_TYPES[Mode.of(trip.mode)] ?: return Result.Matches
        val south = stops.minOf { it.lat } - PAD
        val west = stops.minOf { it.lon } - PAD * 2
        val north = stops.maxOf { it.lat } + PAD
        val east = stops.maxOf { it.lon } + PAD * 2
        // "R32" also finds "RE32": the same line under an older name. Just the number otherwise.
        val number = Regex("^[A-Za-z]*(\\d[A-Za-z0-9]*)$").find(trip.route)?.groupValues?.get(1)
        val ref = if (number != null) "[ref~\"^[A-Za-z]*$number${'$'}\"]" else "[ref=\"${trip.route.replace("\"", "\\\"")}\"]"
        val query = """
            [out:json][timeout:25];
            rel[type=route][route~"^($routeTypes)${'$'}"]$ref($south,$west,$north,$east)->.routes;
            .routes out body;
            (node(r.routes); way(r.routes);)->.members;
            .members out tags;
        """.trimIndent()
        val elements = overpass(query).getJSONArray("elements").objects()

        val names = HashMap<String, String>()
        for (e in elements) {
            if (e.getString("type") == "relation") continue
            e.optJSONObject("tags")?.optString("name")?.takeIf { it.isNotEmpty() }?.let {
                names[e.getString("type") + e.getLong("id")] = it
            }
        }
        val tripNames = stops.map { it.name }
        val tripKeys = tripNames.mapTo(HashSet(), ::key)

        return elements.filter { it.getString("type") == "relation" }.map { relation ->
            // Stops and platforms in order, each stop once (it usually has a stop position and a platform).
            val osmNames = mutableListOf<String>()
            for (member in relation.getJSONArray("members").objects()) {
                if (!STOP_ROLE.matches(member.optString("role"))) continue
                val name = names[member.getString("type") + member.getLong("ref")] ?: continue
                if (osmNames.none { key(it) == key(name) }) osmNames += name
            }
            val osmKeys = osmNames.mapTo(HashSet(), ::key)
            val tags = relation.optJSONObject("tags")
            Result.Outdated(
                osmRef = tags?.optString("ref").orEmpty(),
                osmName = tags?.optString("name")?.takeIf { it.isNotEmpty() },
                missing = tripNames.filter { key(it) !in osmKeys }.distinctBy(::key),
                extra = osmNames.filter { key(it) !in tripKeys },
            )
        }.minWithOrNull(
            // The direction and variant closest to the trip, preferring the right number.
            compareBy<Result.Outdated>({ it.missing.size + it.extra.size }, { !it.osmRef.equals(trip.route, true) }),
        )?.let { closest ->
            if (closest.osmRef.equals(trip.route, true) && closest.missing.isEmpty() && closest.extra.isEmpty()) {
                Result.Matches
            } else {
                closest
            }
        } ?: Result.Missing
    }

    /** Asks every server at once and takes the first answer: the public ones are often busy, each at other times. */
    private fun overpass(query: String): JSONObject {
        val pool = Executors.newFixedThreadPool(SERVERS.size)
        try {
            val answers = ExecutorCompletionService<JSONObject>(pool)
            SERVERS.forEach { server -> answers.submit { post(server, query) } }
            var failure: Throwable? = null
            repeat(SERVERS.size) {
                try {
                    return answers.take().get()
                } catch (e: ExecutionException) {
                    failure = e.cause
                }
            }
            throw failure as? IOException ?: IOException("Overpass: no server answered", failure)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun post(server: String, query: String): JSONObject {
        val connection = URL(server).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            // Overpass turns away requests that don't say who they are.
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Overpass: HTTP $code")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return try {
                JSONObject(text)
            } catch (e: org.json.JSONException) {
                throw IOException("Overpass: unreadable answer", e)
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        val SERVERS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        )
        const val USER_AGENT = "OsmAndCompanion (github.com/maksimts-kool/osmand-maksimts)"

        /** Degrees around the trip's stops, so routes drawn a little off still count. */
        const val PAD = 0.01

        /** OSM's route=* values for each of our modes. */
        val OSM_ROUTE_TYPES = mapOf(
            Mode.BUS to "bus|trolleybus",
            Mode.TROLLEYBUS to "trolleybus|bus",
            Mode.REGIONAL to "bus|coach",
            Mode.TRAM to "tram",
            Mode.RAIL to "train|light_rail",
            Mode.FERRY to "ferry",
        )

        val STOP_ROLE = Regex("^(stop|platform)(_entry_only|_exit_only)?$")

        /** "Balti jaam (Tallinn)" and "balti jaam" are the same stop. */
        fun key(name: String) = name.substringBefore(" (").trim().lowercase(Locale.ROOT)

        fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    }
}
