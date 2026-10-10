package dev.maksim.companion.planner

import android.content.Context
import dev.maksim.companion.core.Analytics
import dev.maksim.companion.timetable.Estonia
import dev.maksim.companion.timetable.RouteOrder
import dev.maksim.companion.timetable.distanceMeters
import dev.maksim.companion.timetable.graphQL
import dev.maksim.companion.timetable.objects
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Every stop in Estonia that something still serves, from peatus.ee, searched on the phone: peatus.ee's own stop
 * search only finds names containing exactly what's typed, so "kristine" or "parnu" find nothing. Fetched once a
 * week (about 800 kB) and kept in the cache.
 *
 * A stop's sides of the street, and the bays of a station ("Balti jaam 1" to "6"), are one [Group].
 */
class StopIndex(val groups: List<Group>) {

    data class Stop(val name: String, val lat: Double, val lon: Double, val mode: String?, val routes: List<String>)

    class Group(val name: String, val lat: Double, val lon: Double, val mode: String?, val lines: List<String>, val town: String?) {
        val match = Fuzzy.Name(name)
    }

    /** Those matching [query], best first: the better match, then the nearer to [near]. */
    fun search(query: Fuzzy.Query, near: Place?, max: Int): List<Found> = groups.asSequence()
        .map { it to Fuzzy.score(query, it.match) }
        .filter { it.second >= Fuzzy.MATCH }
        .sortedWith(
            compareByDescending<Pair<Group, Int>> { it.second / Fuzzy.RANK }
                .thenBy { (group) -> near?.let { distanceMeters(it.lat, it.lon, group.lat, group.lon) } ?: 0.0 },
        )
        .take(max)
        .map { (group) -> found(group) }
        .toList()

    companion object {
        /** How far apart stops of the same name can be and still be one. */
        private const val GROUP_M = 500.0
        private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
        private const val RETRY_MS = 10 * 60 * 1000L
        private const val FILE = "planner-stops.tsv"
        private const val ENDPOINT = "https://api.peatus.ee/routing/v1/routers/estonia/index/graphql"

        /** A station's bay ("Balti jaam 4 (train station)"), which is still the station. */
        private val BAY = Regex("""\s+\d{1,2}(?=\s*\(|$)""")

        @Volatile
        private var index: StopIndex? = null
        private var indexAt = 0L
        private var retryAt = 0L

        /** The index if it's been read already; null until [load] has. Doesn't wait for one being loaded. */
        fun loaded(): StopIndex? = index

        /**
         * The index: as read before, from the cache, or fetched. One a week old or older is fetched again, but kept
         * if that fails. Blocking: call it off the main thread.
         */
        @Synchronized
        fun load(context: Context): StopIndex {
            val now = System.currentTimeMillis()
            val towns = Towns.of(context)
            val file = File(context.cacheDir, FILE)
            if (index == null) readSaved(file)?.let { (at, stops) ->
                index = StopIndex(group(stops, towns))
                indexAt = at
            }
            val current = index
            if (current != null && (now - indexAt < MAX_AGE_MS || now < retryAt)) return current
            return try {
                val stops = download()
                save(file, stops, now)
                StopIndex(group(stops, towns)).also {
                    index = it
                    indexAt = now
                }
            } catch (e: IOException) {
                retryAt = now + RETRY_MS
                current ?: throw e
            } catch (e: org.json.JSONException) {
                retryAt = now + RETRY_MS
                current ?: throw IOException("peatus.ee: unreadable stop list", e)
            }
        }

        /** [stops] as search results, a [Group] each: for peatus.ee's own search, while there's no index yet. */
        fun found(stops: List<Stop>, towns: Towns): List<Found> = group(stops, towns).map(::found)

        private fun found(group: Group) = Found(
            Place(group.name, group.lat, group.lon, group.town),
            Found.Kind.STOP,
            group.mode,
            group.lines,
        )

        /**
         * Same-named stops ([BAY]s aside) within [GROUP_M] of the one with the most routes, as one: placed at that
         * one, named after it, with all their routes.
         */
        fun group(stops: List<Stop>, towns: Towns?): List<Group> {
            val groups = ArrayList<Group>()
            for (same in stops.groupBy { Fuzzy.fold(it.name.replace(BAY, "")) }.values) {
                val left = same.sortedByDescending { it.routes.size }.toMutableList()
                while (left.isNotEmpty()) {
                    val anchor = left.first()
                    val members = left.filter { distanceMeters(anchor.lat, anchor.lon, it.lat, it.lon) < GROUP_M }
                    left.removeAll(members)
                    val name = if (members.all { it.name == anchor.name }) anchor.name else anchor.name.replace(BAY, "")
                    val mode = members.mapNotNull { it.mode }.minByOrNull { MODE_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }
                    val lines = members.flatMap { it.routes }.distinct().sortedWith(RouteOrder)
                    groups += Group(name, anchor.lat, anchor.lon, mode, lines, towns?.around(anchor.lat, anchor.lon))
                }
            }
            return groups
        }

        /** A group's icon: its biggest kind of vehicle. */
        private val MODE_ORDER = listOf("RAIL", "TRAM", "FERRY", "BUS")

        private fun download(): List<Stop> = Analytics.timed("http.client", "POST api.peatus.ee stops (all)") {
            val data = graphQL(ENDPOINT, "peatus.ee", "{ stops { name lat lon vehicleMode routes { shortName } } }", JSONObject())
            parse(data)
        }

        /** peatus.ee's answer as stops: those still served, in Estonia. */
        fun parse(data: JSONObject): List<Stop> = data.getJSONArray("stops").objects().mapNotNull { json ->
            val routes = json.optJSONArray("routes")?.objects().orEmpty().map { it.optString("shortName") }.filter { it.isNotEmpty() }
            val lat = json.optDouble("lat")
            val lon = json.optDouble("lon")
            val name = json.optString("name").trim()
            if (routes.isEmpty() || name.isEmpty() || !Estonia.contains(lat, lon)) return@mapNotNull null
            Stop(name, lat, lon, json.optString("vehicleMode").ifEmpty { null }, routes.distinct())
        }

        /** "name, lat, lon, mode, routes" a line, tab-separated, after the time it was fetched. */
        private fun save(file: File, stops: List<Stop>, at: Long) {
            runCatching {
                val part = File(file.path + ".part")
                part.bufferedWriter().use { out ->
                    out.write(at.toString())
                    for (s in stops) {
                        out.append('\n').append(s.name).append('\t').append(s.lat.toString()).append('\t')
                            .append(s.lon.toString()).append('\t').append(s.mode.orEmpty()).append('\t')
                            .append(s.routes.joinToString(","))
                    }
                }
                part.renameTo(file)
            }
        }

        private fun readSaved(file: File): Pair<Long, List<Stop>>? = runCatching {
            file.bufferedReader().useLines { lines ->
                val iterator = lines.iterator()
                val at = iterator.next().toLong()
                val stops = iterator.asSequence().mapNotNull { line ->
                    val f = line.split('\t')
                    if (f.size < 5) return@mapNotNull null
                    Stop(f[0], f[1].toDouble(), f[2].toDouble(), f[3].ifEmpty { null }, f[4].split(',').filter { it.isNotEmpty() })
                }.toList()
                at to stops
            }
        }.getOrNull()?.takeIf { it.second.isNotEmpty() }
    }
}
