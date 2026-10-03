package dev.maksim.companion.planner

import dev.maksim.companion.timetable.Estonia
import dev.maksim.companion.timetable.PeatusClient
import dev.maksim.companion.timetable.TallinnLive
import dev.maksim.companion.timetable.distanceMeters
import java.util.Calendar
import java.util.TimeZone
import java.util.TreeMap
import kotlin.math.floor

/**
 * Finds ways through Tallinn's city lines ([TallinnNetwork]) on the phone, with RAPTOR (Delling, Pajor and Werneck,
 * "Round-Based Public Transit Routing", 2012): round k finds the earliest arrival at every stop with k rides, so a
 * search gives the quickest way for each number of changes.
 *
 * What it rides are [Run]s, trips on the days they run, at the times the city's live feed gives their vehicles
 * ([observe]): a bus that's 8 minutes late is taken as leaving 8 minutes late, and one that has already left isn't
 * taken. So it finds ways that only work because of a late bus, which no planner on the timetable can.
 *
 * Times are epoch seconds. Not thread-safe per [Runs]; the router itself can be shared.
 */
class TallinnRouter(val network: TallinnNetwork) {

    /** For each stop, the patterns calling there and where: pattern, position, pattern, position… */
    private val servedBy: Array<IntArray>

    /** For each stop, the stops a short walk away and how far: stop, meters, stop, meters… */
    private val footpaths: Array<IntArray>

    /** The stops some pattern calls at, by a grid of [CELL_DEG] cells: the only ones to walk to. */
    private val grid: Map<Long, List<Int>>

    init {
        val n = network.stops.size
        val served = Array(n) { ArrayList<Int>() }
        network.patterns.forEachIndexed { p, pattern ->
            pattern.stops.forEachIndexed { pos, stop -> if (stop >= 0) served[stop].apply { add(p); add(pos) } }
        }
        servedBy = Array(n) { served[it].toIntArray() }
        val cells = HashMap<Long, MutableList<Int>>()
        for (s in 0 until n) {
            if (servedBy[s].isNotEmpty()) cells.getOrPut(cell(network.stops[s].lat, network.stops[s].lon)) { ArrayList() } += s
        }
        grid = cells
        footpaths = Array(n) { s ->
            if (servedBy[s].isEmpty()) return@Array IntArray(0)
            val stop = network.stops[s]
            val paths = ArrayList<Int>()
            nearby(grid, stop.lat, stop.lon) { other ->
                if (other == s) return@nearby
                val meters = distanceMeters(stop.lat, stop.lon, network.stops[other].lat, network.stops[other].lon)
                if (meters <= TRANSFER_M) paths.apply { add(other); add(meters.toInt()) }
            }
            paths.toIntArray()
        }
    }

    /** A stop to walk to from a place, or from it to one. */
    class Access(val stop: Int, val meters: Double)

    /**
     * The stops served by some line near [lat], [lon]: those within [ACCESS_M], or else the few nearest within
     * [FAR_ACCESS_M]. Empty if the place is nowhere near Tallinn's lines.
     */
    fun near(lat: Double, lon: Double): List<Access> {
        val all = ArrayList<Access>()
        nearby(grid, lat, lon, rings = FAR_ACCESS_RINGS) { s ->
            val meters = distanceMeters(lat, lon, network.stops[s].lat, network.stops[s].lon)
            if (meters <= FAR_ACCESS_M) all += Access(s, meters)
        }
        val close = all.filter { it.meters <= ACCESS_M }
        return close.ifEmpty { all.sortedBy { it.meters }.take(FAR_ACCESS_STOPS) }
    }

    /**
     * A trip on a day it runs: its timetable from [base] (the service day's midnight), and what the city's live feed
     * says of its vehicle ([observe]).
     */
    class Run(val pattern: Int, val trip: TallinnNetwork.Trip, val base: Long) {
        /** How late (seconds) the feed has it at the stops it was seen at, by position. */
        val observed = TreeMap<Int, Int>()

        /** It has left the stop at this position, and so all before: it can't be got on there. */
        var goneThrough = -1

        val isLive: Boolean get() = observed.isNotEmpty()

        fun scheduled(pos: Int): Long = trip.times[pos].let { if (it < 0) -1 else base + it * 60L }

        /** When it's at [pos] as it looks now; -1 if it doesn't call there. */
        fun time(pos: Int): Long {
            val scheduled = scheduled(pos)
            return if (scheduled < 0) -1 else scheduled + delay(pos)
        }

        /** The delay last seen at or before [pos]: a vehicle keeps its delay; before the first seen, that one's. */
        fun delay(pos: Int): Int = (observed.floorEntry(pos) ?: observed.firstEntry())?.value ?: 0

        /** When it leaves its first stop by the timetable. */
        val start: Long get() = trip.times.firstOrNull { it >= 0 }?.let { base + it * 60L } ?: base
    }

    /** The runs around a time, by pattern, as [runs] makes them. */
    class Runs(val byPattern: Array<List<Run>>)

    /**
     * The trips running from [BEFORE_S] before [time] to [AFTER_S] after it, on the days they're timetabled: the day
     * of [time], and the one before for its runs past midnight, and the one after.
     */
    fun runs(time: Long): Runs {
        val days = serviceDays(time * 1000)
        val byPattern = Array(network.patterns.size) { p ->
            val pattern = network.patterns[p]
            val runs = ArrayList<Run>()
            for (day in days) for (trip in pattern.trips) {
                val first = trip.times.firstOrNull { it >= 0 } ?: continue
                val last = trip.times.lastOrNull { it >= 0 } ?: continue
                if (day.midnight + last * 60L < time - BEFORE_S || day.midnight + first * 60L > time + AFTER_S) continue
                if (!network.runs(pattern, trip, day.day, day.weekday)) continue
                runs += Run(p, trip, day.midnight)
            }
            runs.sortedBy { it.start }
        }
        return Runs(byPattern)
    }

    /**
     * Puts what the city's live feed lists at [stop] ([times]) into [runs], at [now]: a run on its way that it lists
     * with a vehicle on it is as late as the feed says from there on ([TallinnLive.Times.isLive]); one it doesn't list while it lists
     * a later one of the same line and direction has been and gone. Those it lists from the timetable stay as they are.
     */
    fun observe(runs: Runs, stop: Int, times: TallinnLive.Times, now: Long) {
        val served = servedBy[stop]
        for (i in served.indices step 2) {
            val p = served[i]
            val pos = served[i + 1]
            val pattern = network.patterns[p]
            val mode = modeOf(pattern.transport)
            val firstListed = times.firstListed(pattern.route, mode, pattern.headsign)?.scheduled
            for (run in runs.byPattern[p]) {
                val scheduled = run.scheduled(pos)
                if (scheduled < 0 || scheduled < now - LATE_S || scheduled > now + LIVE_AHEAD_S) continue
                val time = times.find(pattern.route, mode, pattern.headsign, scheduled)
                if (time != null) {
                    val delay = (time.expected - time.scheduled).toInt()
                    // Before it has set off, the feed's time for it is its vehicle's guess from the trip before: it's
                    // the timetable until then, as on the app's other screens.
                    val started = run.start + delay <= now
                    if (started && times.isLive(time, started = true)) run.observed[pos] = delay
                } else if (firstListed != null && scheduled < firstListed - GONE_MARGIN_S && scheduled <= now + GONE_AHEAD_S) {
                    run.goneThrough = maxOf(run.goneThrough, pos)
                }
            }
        }
    }

    /** A step of a [Journey]: a walk between stops (-1: the place it starts or ends at) or a ride. */
    sealed interface Part
    class Walk(val from: Int, val to: Int, val meters: Double, val start: Long, val end: Long) : Part
    class RidePart(val run: Run, val board: Int, val alight: Int) : Part

    class Journey(val parts: List<Part>) {
        val rides: List<RidePart> get() = parts.filterIsInstance<RidePart>()
        val arrival: Long get() = (parts.last() as? Walk)?.end ?: 0

        /** The latest it can be set off on: the first ride's time, less the walk to it. */
        val leaveBy: Long
            get() {
                val first = rides.firstOrNull() ?: return (parts.first() as Walk).start
                return first.run.time(first.board) - walkSeconds((parts.first() as? Walk)?.meters ?: 0.0)
            }

        val signature: String
            get() = rides.joinToString("|") { "${it.run.pattern}:${it.run.base}:${it.run.trip.hashCode()}:${it.board}:${it.alight}" }
    }

    private sealed interface Parent
    private class FromAccess(val meters: Double) : Parent
    private class FromRide(val run: Run, val board: Int, val alight: Int) : Parent
    private class FromWalk(val stop: Int, val meters: Double) : Parent

    /**
     * The quickest ways from [origin] to [destination] setting off at [departAt]: one for each number of rides that
     * gets there sooner than with fewer, up to [MAX_RIDES].
     */
    fun search(origin: List<Access>, destination: List<Access>, departAt: Long, runs: Runs): List<Journey> {
        val n = network.stops.size
        val labels = Array(MAX_RIDES + 1) { LongArray(n) { INF } }
        val parents = Array(MAX_RIDES + 1) { arrayOfNulls<Parent>(n) }
        val best = LongArray(n) { INF }
        var marked = BooleanArray(n)
        for (access in origin) {
            val time = departAt + walkSeconds(access.meters)
            if (time < labels[0][access.stop]) {
                labels[0][access.stop] = time
                best[access.stop] = time
                parents[0][access.stop] = FromAccess(access.meters)
                marked[access.stop] = true
            }
        }
        val egress = HashMap<Int, Double>()
        for (access in destination) egress[access.stop] = minOf(egress[access.stop] ?: Double.MAX_VALUE, access.meters)
        var bestArrival = INF
        val journeys = ArrayList<Journey>()

        for (k in 1..MAX_RIDES) {
            // Each pattern from the first of its stops that was reached in the last round.
            val scanFrom = IntArray(network.patterns.size) { Int.MAX_VALUE }
            var any = false
            for (s in 0 until n) {
                if (!marked[s]) continue
                val served = servedBy[s]
                for (i in served.indices step 2) {
                    if (served[i + 1] < scanFrom[served[i]]) scanFrom[served[i]] = served[i + 1]
                    any = true
                }
            }
            if (!any) break
            val previous = labels[k - 1]
            val current = labels[k]
            val next = BooleanArray(n)
            for (p in network.patterns.indices) {
                if (scanFrom[p] == Int.MAX_VALUE) continue
                val pattern = network.patterns[p]
                val patternRuns = runs.byPattern[p]
                if (patternRuns.isEmpty()) continue
                var run: Run? = null
                var boardedAt = -1
                for (pos in scanFrom[p] until pattern.stops.size) {
                    val s = pattern.stops[pos]
                    if (s < 0) continue
                    if (run != null && pattern.canAlight[pos]) {
                        val arrival = run.time(pos)
                        if (arrival >= 0 && arrival < best[s] && arrival < bestArrival) {
                            current[s] = arrival
                            best[s] = arrival
                            parents[k][s] = FromRide(run, boardedAt, pos)
                            next[s] = true
                        }
                    }
                    if (previous[s] < INF && pattern.canBoard[pos]) {
                        val ready = previous[s] + if (k == 1) BOARD_S else CHANGE_S
                        val onBoard = run?.time(pos)?.takeIf { it >= 0 } ?: INF
                        if (ready <= onBoard) {
                            val earlier = earliest(patternRuns, pos, ready)
                            if (earlier != null && earlier.time(pos) < onBoard) {
                                run = earlier
                                boardedAt = pos
                            }
                        }
                    }
                }
            }
            // Walks to stops nearby, from those a ride got to.
            for (s in 0 until n) {
                if (!next[s] || parents[k][s] !is FromRide) continue
                val paths = footpaths[s]
                for (i in paths.indices step 2) {
                    val other = paths[i]
                    val meters = paths[i + 1].toDouble()
                    val time = current[s] + walkSeconds(meters)
                    if (time < best[other] && time < bestArrival && time < current[other]) {
                        current[other] = time
                        best[other] = time
                        parents[k][other] = FromWalk(s, meters)
                        next[other] = true
                    }
                }
            }
            var reachedBy = -1
            for ((s, meters) in egress) {
                if (current[s] >= INF) continue
                val time = current[s] + walkSeconds(meters)
                if (time < bestArrival) {
                    bestArrival = time
                    reachedBy = s
                }
            }
            if (reachedBy >= 0) journeys += journey(k, reachedBy, egress.getValue(reachedBy), labels, parents)
            marked = next
        }
        return journeys
    }

    /**
     * Several ways from [origin] to [destination] from [departAt] on: the [search]'s, then again setting off just
     * after the first of them leaves, [RANGE_RUNS] times, for the next ones.
     */
    fun journeys(origin: List<Access>, destination: List<Access>, departAt: Long, runs: Runs, searches: Int = RANGE_RUNS): List<Journey> {
        val all = LinkedHashMap<String, Journey>()
        var time = departAt
        repeat(searches) {
            val found = search(origin, destination, time, runs)
            if (found.isEmpty()) return all.values.toList()
            for (journey in found) all.putIfAbsent(journey.signature, journey)
            time = found.minOf { it.leaveBy } + NEXT_S
        }
        return all.values.toList()
    }

    /** The run of [runs] that's at [pos] first at or after [ready], and that can be got on there. */
    private fun earliest(runs: List<Run>, pos: Int, ready: Long): Run? {
        var best: Run? = null
        var bestTime = INF
        for (run in runs) {
            if (pos <= run.goneThrough) continue
            val time = run.time(pos)
            if (time in ready until bestTime) {
                best = run
                bestTime = time
            }
        }
        return best
    }

    private fun journey(k: Int, last: Int, egressMeters: Double, labels: Array<LongArray>, parents: Array<Array<Parent?>>): Journey {
        val parts = ArrayList<Part>()
        val arrived = labels[k][last]
        parts += Walk(last, -1, egressMeters, arrived, arrived + walkSeconds(egressMeters))
        var round = k
        var stop = last
        while (true) {
            when (val parent = parents[round][stop]) {
                is FromWalk -> {
                    parts += Walk(parent.stop, stop, parent.meters, labels[round][parent.stop], labels[round][stop])
                    stop = parent.stop
                }
                is FromRide -> {
                    parts += RidePart(parent.run, parent.board, parent.alight)
                    stop = network.patterns[parent.run.pattern].stops[parent.board]
                    round--
                }
                is FromAccess -> {
                    val reached = labels[0][stop]
                    parts += Walk(-1, stop, parent.meters, reached - walkSeconds(parent.meters), reached)
                    break
                }
                null -> error("no way back from stop $stop in round $round")
            }
        }
        return Journey(parts.asReversed().toList())
    }

    private fun cell(lat: Double, lon: Double): Long =
        floor(lat / CELL_DEG).toLong() * 100_000 + floor(lon / (CELL_DEG * 2)).toLong()

    /** Calls [block] for the stops in the cells around [lat], [lon]: [rings] cells each way. */
    private inline fun nearby(grid: Map<Long, List<Int>>, lat: Double, lon: Double, rings: Int = 1, block: (Int) -> Unit) {
        val row = floor(lat / CELL_DEG).toLong()
        val column = floor(lon / (CELL_DEG * 2)).toLong()
        for (dr in -rings..rings) for (dc in -rings..rings) grid[(row + dr) * 100_000 + column + dc]?.forEach(block)
    }

    /** One day's service: its [midnight] (epoch s), its [day] since 1970 and its [weekday] (1 is Monday). */
    class ServiceDay(val midnight: Long, val day: Int, val weekday: Int)

    companion object {
        /** Up to 3 changes. */
        const val MAX_RIDES = 4

        /** Walks between stops for a change: across a street or a square. */
        private const val TRANSFER_M = 350.0

        /** Stops walked to from a place, or from them to it. */
        private const val ACCESS_M = 900.0
        private const val FAR_ACCESS_M = 2_000.0
        private const val FAR_ACCESS_STOPS = 3

        /** Cells of the stop grid, about 450 m (lat) by 450 m (lon at Tallinn's latitude). */
        private const val CELL_DEG = 0.004
        private const val FAR_ACCESS_RINGS = 5

        /**
         * Walking, in a straight line: 1.3 m/s along streets that are a quarter longer than the line, like
         * OpenTripPlanner's walk speed.
         */
        private const val WALK_DETOUR = 1.25
        private const val WALK_SPEED = 1.3
        fun walkSeconds(meters: Double): Long = (meters * WALK_DETOUR / WALK_SPEED).toLong()

        /** Time to get on after walking to a stop, and to change at one (transport.tallinn.ee's planner leaves 3 min). */
        const val BOARD_S = 30L
        const val CHANGE_S = 120L

        /** Searches for the next ways, each starting a minute after the first way of the one before leaves. */
        private const val RANGE_RUNS = 4
        private const val NEXT_S = 60L

        private const val BEFORE_S = 2 * 60 * 60L
        private const val AFTER_S = 6 * 60 * 60L

        /** The runs [observe] looks at: due from 20 minutes ago (late ones) to as far as the feed predicts. */
        private const val LATE_S = 20 * 60L
        private const val LIVE_AHEAD_S = 90 * 60L

        /** A run due this long before the first the feed still lists of its line has gone, if it's due soon. */
        private const val GONE_MARGIN_S = 2 * 60L
        private const val GONE_AHEAD_S = 30 * 60L

        private const val INF = Long.MAX_VALUE / 4

        /** The feed's and peatus.ee's mode for a pattern's kind of vehicle. */
        fun modeOf(transport: String): String = when (transport) {
            "trol" -> PeatusClient.TROLLEYBUS
            "tram" -> "TRAM"
            else -> "BUS"
        }

        /** The service days around [time] (epoch ms) in Estonia: the one before, its own, and the one after. */
        fun serviceDays(time: Long, zone: TimeZone = Estonia.timeZone): List<ServiceDay> = (-1..1).map { offset ->
            val local = Calendar.getInstance(zone).apply {
                timeInMillis = time
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, offset)
            }
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
            }
            ServiceDay(
                midnight = local.timeInMillis / 1000,
                day = (utc.timeInMillis / 86_400_000L).toInt(),
                // Calendar's week starts on Sunday (1).
                weekday = (local.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1,
            )
        }
    }
}
