package dev.maksim.companion.planner

/**
 * Tallinn's city lines (buses, trolleybuses and trams) with every trip's times, as the city publishes them for
 * transport.tallinn.ee: `data/stops.txt` and `data/routes.txt`, half a megabyte for the whole city. Its own planner
 * runs in the browser on these; [TallinnRouter] does the same here, with the city's live times put in.
 *
 * stops.txt is `ID;SiriID;Lat;Lng;Stops;Name;…`: the code on the stop's sign, the live feed's id for it, and its
 * place in 1/100000 degrees; a name left empty is the one before's.
 *
 * routes.txt has two lines per route and direction (a pattern): what it is (number, kind, name, which days, its
 * stops) and its times, encoded ([decodeTimes]). Fields left empty are the line before's. Lines of the authority
 * "SpecialDates" are groups of dates instead (holidays), which a pattern can run differently on: "1,7" in its
 * SpecialDates is "on group 1's dates, as on a Sunday", "1,0" is "not on group 1's dates". Some of those lines have
 * no group number and a weekday, which the format would read as "these dates run as that weekday"; but the file has
 * every Friday to Sunday as "1" (Monday) that way, while the city's buses run their weekend timetable then, and
 * transport.tallinn.ee itself leaves those out. So do we.
 */
class TallinnNetwork(
    val stops: List<Stop>,
    val patterns: List<Pattern>,
    private val specialDates: Map<String, Set<Int>>,
) {

    class Stop(val code: String, val siriId: String?, val name: String, val lat: Double, val lon: Double)

    /** One route in one direction (or a variant of it), and its trips. */
    class Pattern(
        /** The number on the vehicle: "23", "T4". */
        val route: String,
        /** bus, trol or tram. */
        val transport: String,
        /** "Vana-Pääsküla - Viru - Viimsi". */
        val name: String,
        /** Indices into [TallinnNetwork.stops]; -1 for a stop stops.txt doesn't have. */
        val stops: IntArray,
        val canBoard: BooleanArray,
        val canAlight: BooleanArray,
        val trips: List<Trip>,
        /** Date group and the weekday the pattern runs as on its dates ('0': it doesn't, '*': as usual). */
        val specialDates: List<Pair<String, Char>>,
    ) {
        /** Where it goes: the end of its name, as the city's feed has it. */
        val headsign: String get() = name.substringAfterLast(" - ").trim()
    }

    /**
     * A trip's [times] at each of its pattern's stops, minutes from the start of its service day (past 24 h after
     * midnight), -1 where it doesn't call. It runs from day [validFrom] to [validTo] (days since 1970; 0: no end) on
     * the [weekdays] ("12345", "67"; 1 is Monday).
     */
    class Trip(val times: IntArray, val validFrom: Int, val validTo: Int, val weekdays: String)

    /** Whether [trip] of [pattern] runs on [day] (days since 1970) whose day of the week is [weekday] (1 is Monday). */
    fun runs(pattern: Pattern, trip: Trip, day: Int, weekday: Int): Boolean {
        if (day < trip.validFrom || (trip.validTo != 0 && day > trip.validTo)) return false
        var as_ = '0' + weekday
        for ((group, runsAs) in pattern.specialDates) {
            if (specialDates[group]?.contains(day) != true) continue
            if (runsAs == '0') return false
            if (runsAs != '*') as_ = runsAs
            break
        }
        return as_ in trip.weekdays || trip.weekdays.startsWith('0')
    }

    companion object {

        fun parse(stopsText: String, routesText: String): TallinnNetwork {
            val stops = parseStops(stopsText)
            val index = HashMap<String, Int>(stops.size * 2)
            stops.forEachIndexed { i, stop -> index[stop.code] = i }
            val patterns = ArrayList<Pattern>()
            val specialDates = HashMap<String, Set<Int>>()

            val lines = routesText.removePrefix(BOM).lines()
            val header = lines.firstOrNull()?.uppercase()?.split(';').orEmpty()
            fun column(name: String) = header.indexOf(name)
            val num = 0
            val authorityAt = column("AUTHORITY")
            val transportAt = column("TRANSPORT")
            val validityAt = column("VALIDITYPERIODS")
            val specialAt = column("SPECIALDATES")
            val nameAt = column("ROUTENAME")
            val stopsAt = column("ROUTESTOPS")
            val hasTripIds = column("TRIPIDS") >= 0

            var route = ""
            var authority = ""
            var transport = ""
            var name = ""
            var special = ""
            var i = 1
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("#") || line.length <= 1) {
                    i++
                    continue
                }
                val parts = line.split(';')
                fun field(at: Int) = if (at >= 0) parts.getOrNull(at).orEmpty() else ""
                field(authorityAt).takeIf { it.isNotEmpty() }?.let { authority = if (it == "0") "" else it }

                if (authority == SPECIAL_DATES) {
                    // Without a group: see the class's comment.
                    field(num).takeIf { it.isNotEmpty() }?.let { specialDates[it] = dates(field(validityAt)) }
                    i++
                    continue
                }

                field(num).takeIf { it.isNotEmpty() }?.let { route = if (it == "-") "" else it }
                field(transportAt).takeIf { it.isNotEmpty() }?.let { transport = if (it == "0") "" else it }
                field(nameAt).takeIf { it.isNotEmpty() }?.let { name = it }
                field(specialAt).takeIf { it.isNotEmpty() }?.let { special = if (it == "0") "" else it }
                val timesLine = lines.getOrNull(i + 1).orEmpty()
                i += 2
                // A route under construction ("разв").
                if (route.contains("разв")) continue

                val codes = field(stopsAt).split(',').filter { it.isNotEmpty() }
                if (codes.isEmpty()) continue
                val canBoard = BooleanArray(codes.size) { true }
                val canAlight = BooleanArray(codes.size) { true }
                val stopIndices = IntArray(codes.size) { at ->
                    var code = codes[at]
                    // "e": only to get on; "x": only to get off.
                    when (code.firstOrNull()) {
                        'e' -> { canAlight[at] = false; code = code.substring(1) }
                        'x' -> { canBoard[at] = false; code = code.substring(1) }
                    }
                    index[code] ?: -1
                }
                val trips = runCatching { decodeTimes(timesLine, codes.size, hasTripIds) }.getOrNull() ?: continue
                patterns += Pattern(route, transport, name, stopIndices, canBoard, canAlight, trips, pairs(special))
            }
            return TallinnNetwork(stops, patterns, specialDates)
        }

        private fun parseStops(text: String): List<Stop> {
            val lines = text.removePrefix(BOM).lines()
            val header = lines.firstOrNull()?.uppercase()?.split(';').orEmpty()
            val siriAt = header.indexOf("SIRIID")
            val latAt = header.indexOf("LAT")
            val lonAt = header.indexOf("LNG")
            val nameAt = header.indexOf("NAME")
            var name = ""
            val stops = ArrayList<Stop>()
            for (line in lines.drop(1)) {
                if (line.length <= 1) continue
                val parts = line.split(';')
                parts.getOrNull(nameAt)?.takeIf { it.isNotEmpty() }?.let { name = if (it == "0") "" else it }
                val code = parts[0].trim()
                // Areas ("a21302-1"), which group stops for the city's search.
                if (code.isEmpty() || code.startsWith("a")) continue
                val lat = parts.getOrNull(latAt)?.toIntOrNull() ?: continue
                val lon = parts.getOrNull(lonAt)?.toIntOrNull() ?: continue
                stops += Stop(code, parts.getOrNull(siriAt)?.takeIf { it.isNotEmpty() }, name, lat / 1e5, lon / 1e5)
            }
            return stops
        }

        /**
         * Dates as they're listed: each the one before plus a number of days, and where that's left out, plus as
         * many as last time. "20811,1,,6" is days 20811, 20812, 20813 and 20819.
         */
        private fun dates(list: String): Set<Int> {
            var day = 0
            var step = 0
            val dates = HashSet<Int>()
            for (item in list.split(',')) {
                if (item.isNotEmpty()) step = item.toIntOrNull() ?: continue
                day += step
                dates += day
            }
            return dates
        }

        /** "1,7,2,0" → (1, '7'), (2, '0'). */
        private fun pairs(list: String): List<Pair<String, Char>> =
            list.split(',').chunked(2).mapNotNull { pair ->
                val group = pair.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                group to (pair.getOrNull(1)?.firstOrNull() ?: '*')
            }

        /**
         * A pattern's trips from its times line: comma-separated sections, each ending in an empty item.
         *
         *  1. When each trip leaves its first stop, in minutes: each the one before plus this ("+325,+14,-01030"; a
         *     leading 0 marks a low-floor vehicle). If the first is past 24 h, the night bus's, all are a day earlier.
         *  2. The day each trip is valid from, as "day,how many trips" pairs, the last one's count left out: it's for
         *     the rest. Then the day each is valid to (0: no end), the same way, then their weekdays.
         *  3. With trip ids (not Tallinn's), two lists of them.
         *  4. How long each trip takes from one stop to the next, a stop at a time: "minutes,how many trips" pairs, the
         *     minutes plus 5 (so they're never negative), each pair's added to the one before's in the same row. A
         *     trip that skips stops is a long way negative there.
         */
        fun decodeTimes(line: String, stopCount: Int, hasTripIds: Boolean = false): List<Trip> {
            val items = line.split(',')
            var at = 0
            val starts = ArrayList<Int>()
            var minutes = 0
            while (at < items.size && items[at].isNotEmpty()) {
                minutes += items[at].toInt()
                if (starts.isEmpty() && minutes >= DAY_MINUTES) minutes -= DAY_MINUTES
                starts += minutes
                at++
            }
            at++
            val count = starts.size
            if (count == 0) return emptyList()

            fun perTrip(): List<String> {
                val values = ArrayList<String>(count)
                while (at < items.size) {
                    val value = items[at]
                    val repeat = items.getOrNull(at + 1).orEmpty()
                    at += 2
                    val n = if (repeat.isEmpty()) count - values.size else repeat.toInt()
                    repeat(n) { if (values.size < count) values += value }
                    if (repeat.isEmpty()) break
                }
                while (values.size < count) values += values.lastOrNull().orEmpty()
                return values
            }
            val validFrom = perTrip().map { it.toIntOrNull() ?: 0 }
            val validTo = perTrip().map { it.toIntOrNull() ?: 0 }
            val weekdays = perTrip()
            if (hasTripIds) repeat(2) {
                while (at < items.size && items[at].isNotEmpty()) at++
                at++
            }

            // Stop by stop, each trip's time: the first stop's are the starts.
            val times = IntArray(stopCount * count) { -1 }
            for (trip in 0 until count) times[trip] = starts[trip]
            var cell = count
            var left = count
            var step = 5
            while (at < items.size && cell < times.size) {
                step += (items[at].toIntOrNull() ?: 0) - 5
                val repeat = items.getOrNull(at + 1).orEmpty()
                at += 2
                val n = if (repeat.isEmpty()) left.also { left = 0 } else minOf(repeat.toInt(), left).also { left -= it }
                repeat(n) {
                    if (cell < times.size) times[cell] = step + times[cell - count]
                    cell++
                }
                if (left <= 0) {
                    left = count
                    step = 5
                }
            }
            return (0 until count).map { trip ->
                Trip(
                    IntArray(stopCount) { stop -> times[stop * count + trip].takeIf { stop * count + trip < cell && it >= 0 } ?: -1 },
                    validFrom[trip], validTo[trip], weekdays[trip],
                )
            }
        }

        private const val BOM = "\uFEFF"
        private const val SPECIAL_DATES = "SpecialDates"
        private const val DAY_MINUTES = 24 * 60
    }
}
