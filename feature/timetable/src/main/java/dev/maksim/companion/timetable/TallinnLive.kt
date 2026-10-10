package dev.maksim.companion.timetable

import dev.maksim.companion.core.Analytics
import dev.maksim.companion.core.Parallel
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

/**
 * Live departure times of Tallinn's city buses, trolleybuses and trams, from the city's own feed, the one
 * transport.tallinn.ee shows. peatus.ee doesn't get these, so its Tallinn times are only the timetable.
 *
 * The feed is plain text, one stop at a time, by the city's own stop id ([feedId]):
 * ```
 * Transport,RouteNum,ExpectedTimeInSeconds,ScheduleTimeInSeconds,64206,version20201024
 * stop,1626
 * bus,41,64427,64065,Balti jaam,221,Z
 * ```
 * The header's number is the server's time; each departure has its kind, route, expected and timetabled times
 * (seconds since midnight in Tallinn), where it goes, seconds to go, and "Z" for a low-floor vehicle.
 *
 * Blocking: call it off the main thread.
 */
class TallinnLive {

    /** One departure the feed lists: [scheduled] and [expected] are epoch seconds. */
    class Time(val mode: String, val route: String, val destination: String, val scheduled: Long, val expected: Long) {
        /**
         * Whether this one alone shows a vehicle is out on it. The feed gives a vehicle one delay, to the stops
         * ahead of it all the same, so one right on time is expected just when it's due, like a trip that has no
         * vehicle yet. One exactly 10 s late is how the feed has a trip that hasn't set off (every trip of the
         * evening at the first stop of a line is +10 s), but a vehicle on its way can be +10 s at some stops too:
         * that's one if the trip has [started], by the timetable. One in nine departures in the feed is +10 s;
         * +9 or +11, none. See [Times.isLive] for the ones on time.
         */
        fun isPrediction(started: Boolean): Boolean = when (expected - scheduled) {
            0L -> false
            NOT_SET_OFF_S -> started
            else -> true
        }
    }

    /** The feed's departures from [stop], or none if it doesn't know the stop. */
    fun departures(stop: Stop): Times {
        val id = feedId(stop) ?: return Times(emptyList())
        val text = get("$ENDPOINT?stopid=$id")
        // Its seconds count from midnight in Tallinn; if the server's own time is past 24 h, from the one before.
        val lines = text.lines()
        val serverTime = lines.firstOrNull()?.split(',')?.getOrNull(4)?.trim()?.toLongOrNull() ?: 0L
        val midnight = Estonia.dayStart(if (serverTime >= DAY_S) -1 else 0) / 1000
        val times = lines.asSequence().drop(2).mapNotNull { line ->
            val fields = line.split(',')
            if (fields.size < 4) return@mapNotNull null
            val mode = KINDS[fields[0].trim()] ?: return@mapNotNull null
            val expected = fields[2].trim().toLongOrNull() ?: return@mapNotNull null
            val scheduled = fields[3].trim().toLongOrNull() ?: return@mapNotNull null
            val destination = fields.getOrNull(4)?.trim().orEmpty().map { BALTIC[it] ?: it }.joinToString("")
            Time(mode, fields[1].trim(), destination, midnight + scheduled, midnight + expected)
        }.toList()
        return Times(times)
    }

    /**
     * Several stops' departures at once, a few at a time, by [Stop.id]; a stop that couldn't be loaded is left out.
     * For a trip, whose stops each have their own list.
     */
    fun departures(stops: Collection<Stop>): Map<String, Times> {
        if (stops.isEmpty()) return emptyMap()
        // Before the stops go off on their own, so they don't all fetch the city's list of them.
        runCatching { feedIds() }
        val list = stops.toList()
        return Parallel.map(list, PARALLEL) { departures(it) }
            .mapIndexedNotNull { i, times -> times.getOrNull()?.let { list[i].id to it } }
            .toMap()
    }

    /**
     * The feed's id for [stop]. The city lists its stops by the code on the sign ([Stop.code]) with the feed's id
     * for each (transport.tallinn.ee/data/stops.txt: `00308-2;5877;…`). That's the stop's peatus.ee id for older
     * stops ("estonia:1626" is 1626), but not newer ones: Haabersti's "estonia:141510" is 5877. Without the list,
     * the peatus.ee id is the best guess.
     */
    private fun feedId(stop: Stop): String? {
        val ids = runCatching { feedIds() }.getOrNull()
        if (ids != null) return stop.code?.let { ids[it] }
        return stop.id.substringAfter(':').takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    /**
     * The city's list of stops as code → feed id, fetched once a day; a failed fetch is tried again later. Kept in
     * the cache ([cacheIn]) too, so a new process doesn't have to fetch it before its first stop.
     */
    private fun feedIds(): Map<String, String> = synchronized(Companion) {
        val now = System.currentTimeMillis()
        if (!savedRead) {
            savedRead = true
            readSaved()
        }
        stopIds?.takeIf { now - stopIdsAt < STOP_LIST_MS }?.let { return it }
        if (now < stopIdsRetryAt) stopIds?.let { return it } ?: throw IOException("transport.tallinn.ee: no stop list")
        try {
            // "ID;SiriID;Lat;Lng;…", then area rows without a feed id ("a13407-1;;…"), then the stops.
            val ids = get(STOPS).lineSequence().drop(1).mapNotNull { line ->
                val fields = line.split(';')
                val id = fields.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                id?.let { fields[0].trim() to it }
            }.toMap()
            if (ids.isEmpty()) throw IOException("transport.tallinn.ee: empty stop list")
            stopIds = ids
            stopIdsAt = now
            save(ids, now)
            return ids
        } catch (e: IOException) {
            stopIdsRetryAt = now + STOP_LIST_RETRY_MS
            stopIds?.let { return it }
            throw e
        }
    }

    /** The list as [save] left it, if it did. */
    private fun readSaved() {
        val file = stopListFile() ?: return
        runCatching {
            val lines = file.readLines()
            val at = lines.first().toLong()
            val ids = lines.drop(1).associate { it.substringBefore(';') to it.substringAfter(';') }
            if (ids.isNotEmpty()) {
                stopIds = ids
                stopIdsAt = at
            }
        }
    }

    private fun save(ids: Map<String, String>, at: Long) {
        val file = stopListFile() ?: return
        runCatching {
            val part = File(file.path + ".part")
            part.bufferedWriter().use { out ->
                out.write(at.toString())
                for ((code, id) in ids) out.append('\n').append(code).append(';').append(id)
            }
            part.renameTo(file)
        }
    }

    private fun stopListFile(): File? = cacheDir?.let { File(it, STOP_LIST_FILE) }

    private fun get(url: String): String = Analytics.timed("http.client", "GET transport.tallinn.ee") { download(url) }

    private fun download(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        val (code, text) = readResponse(connection)
        if (code !in 200..299) throw IOException("transport.tallinn.ee: HTTP $code")
        return text
    }

    /** A stop's live times, to look up the timetabled departures by. */
    class Times(private val times: List<Time>) {

        /**
         * The departures that surely have a vehicle out on them. The feed lists a route's departures (each way) in
         * order, those with vehicles first, so every one up to the last that has a delay (not 0 or 10 s) has one,
         * on time or not.
         */
        private val withVehicle: Set<Time> = times
            .groupBy { Triple(it.mode, it.route.lowercase(Locale.ROOT), placeName(it.destination)) }.values
            .flatMap { same ->
                val sorted = same.sortedBy { it.scheduled }
                sorted.take(sorted.indexOfLast { it.isPrediction(started = false) } + 1)
            }
            .toSet()

        /**
         * Whether [time], one of these, is the vehicle's time rather than the timetable's; [started] is whether its
         * trip has left its first stop by the timetable ([Time.isPrediction]).
         */
        fun isLive(time: Time, started: Boolean) = time in withVehicle || time.isPrediction(started)

        /**
         * The feed's departure of [route] ([mode]) towards [headsign], timetabled at [scheduled] (epoch seconds), or
         * null if it doesn't list it (any more: once the vehicle has left, it's gone). The feed has the timetable to
         * the second where peatus.ee rounds it down to the minute; so it's the feed's one of the same route closest
         * to it within a couple of minutes. It has to go the same way: at the end of a line, the vehicle arriving
         * and the one leaving back can be due at the same minute.
         */
        fun find(route: String, mode: String, headsign: String, scheduled: Long): Time? =
            times.filter { it.mode == mode && it.route.equals(route, ignoreCase = true) }
                .filter { it.scheduled - scheduled in -MATCH_BEFORE_S until MATCH_AFTER_S }
                .filter { sameWay(it.destination, headsign) }
                .minByOrNull { abs(it.scheduled - scheduled - ROUNDING_S) }

        /**
         * The feed's departure of [route] ([mode]) towards [headsign] that's due first by the timetable, or null if it
         * lists none. The feed drops a departure once its vehicle has left, so one due before that has gone.
         */
        fun firstListed(route: String, mode: String, headsign: String): Time? =
            times.filter { it.mode == mode && it.route.equals(route, ignoreCase = true) }
                .filter { sameWay(it.destination, headsign) }
                .minByOrNull { it.scheduled }

        /**
         * The feed's first departure of [route] ([mode]) towards [headsign] expected at or after [after] (epoch
         * seconds), or null: the next one of the line, for when the one planned on can't be caught.
         */
        fun next(route: String, mode: String, headsign: String, after: Long): Time? =
            times.filter { it.mode == mode && it.route.equals(route, ignoreCase = true) && it.expected >= after }
                .filter { sameWay(it.destination, headsign) }
                .minByOrNull { it.expected }

        /**
         * The feed's destinations are peatus.ee's headsigns, give or take a "(train station)" or the start of a
         * longer name ("Reisisadam" for "Reisisadam A-terminal"). Without either, there's nothing to go by.
         */
        private fun sameWay(destination: String, headsign: String): Boolean {
            val a = placeName(destination)
            val b = placeName(headsign)
            return a.isEmpty() || b.isEmpty() || a.startsWith(b) || b.startsWith(a)
        }

        private fun placeName(name: String) = name.replace(BRACKETS, "").trim().lowercase(Locale.ROOT)
    }

    companion object {
        private const val ENDPOINT = "https://transport.tallinn.ee/siri-stop-departures.php"
        private const val STOPS = "https://transport.tallinn.ee/data/stops.txt"

        /** Where [feedIds] keeps the city's list of stops; set once, when the app starts. */
        fun cacheIn(dir: File) {
            cacheDir = dir
        }

        @Volatile
        private var cacheDir: File? = null
        private var savedRead = false
        private const val STOP_LIST_FILE = "tallinn-stops.txt"

        /** For [feedIds]: kept for the app's lifetime, shared by every client. */
        private var stopIds: Map<String, String>? = null
        private var stopIdsAt = 0L
        private var stopIdsRetryAt = 0L
        private const val STOP_LIST_MS = 24 * 60 * 60 * 1000L
        private const val STOP_LIST_RETRY_MS = 5 * 60 * 1000L

        /**
         * The feed's kinds of vehicle, as peatus.ee's modes ([PeatusClient.modeOf]). The night buses (91–96) are
         * "nightbus" in the feed, but city buses on peatus.ee ("estonia:tallinna-lin_bus_94").
         */
        private val KINDS = mapOf(
            "bus" to "BUS", "nightbus" to "BUS", "trol" to PeatusClient.TROLLEYBUS, "tram" to "TRAM",
        )

        /**
         * The feed's text was Windows-1257 once, turned into UTF-8 as if it were Latin-1: õ, ä, ö and ü are the
         * same in both, but š and ž come out as ð and þ ("Maneeþi").
         */
        private val BALTIC = mapOf('ð' to 'š', 'Ð' to 'Š', 'þ' to 'ž', 'Þ' to 'Ž')

        /** How much later than the timetable the feed expects a trip that hasn't set off: see [Time.isPrediction]. */
        private const val NOT_SET_OFF_S = 10L

        private val BRACKETS = Regex("""\s*\(.*?\)""")

        /** Stops asked for at the same time, for [departures] of several. */
        private const val PARALLEL = 6

        /** peatus.ee's time is the feed's rounded down to the minute: 0..59 s before it. */
        private const val MATCH_BEFORE_S = 60L
        private const val MATCH_AFTER_S = 120L
        private const val ROUNDING_S = 30L
        private const val DAY_S = 24 * 60 * 60L

        /** Route ids (peatus.ee's) of the lines the feed covers: "estonia:tallinna-lin_bus_41-1", "…_tram_T4". */
        fun covers(routeId: String) = COVERED.any { it in routeId }

        private val COVERED = listOf("tallinna-lin_bus_", "tallinna-lin_trol_", "tallinna-lin_tram_")
    }
}
