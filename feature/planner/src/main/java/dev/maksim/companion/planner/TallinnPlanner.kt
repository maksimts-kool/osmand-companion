package dev.maksim.companion.planner

import dev.maksim.companion.timetable.Estonia
import java.util.Locale

/**
 * Itineraries through Tallinn's city lines found on the phone ([TallinnRouter]), with the city's live times in the
 * search itself: search on the timetable, ask the live feed about the stops the ways found get on and off at, put
 * the vehicles' delays (and the trips that have already left) into the trips, and search again, until no new stop
 * comes up ([LIVE_ROUNDS] at most). A late bus that makes a way work is found this way; on the timetable, it had left.
 *
 * [router] is blocking (it may download the timetables); so is everything here.
 */
class TallinnPlanner(private val feeds: LiveFeeds, private val router: () -> TallinnRouter) {

    /**
     * Up to a few itineraries from [from] to [to], leaving at [time] (epoch ms), or arriving by it; none if either is
     * away from Tallinn's lines. [now] is when the live times are for.
     */
    fun plan(from: Place, to: Place, time: Long, arriveBy: Boolean, now: Long): List<Itinerary> {
        val router = router()
        val origin = router.near(from.lat, from.lon)
        val destination = router.near(to.lat, to.lon)
        if (origin.isEmpty() || destination.isEmpty()) return emptyList()
        val start = (if (arriveBy) time - ARRIVE_BY_WINDOW_MS else maxOf(time, now)) / 1000
        val runs = router.runs(start)
        val searches = if (arriveBy) ARRIVE_BY_SEARCHES else SEARCHES
        var journeys = router.journeys(origin, destination, start, runs, searches)
        // The feed only says something for the next hour or so.
        if (start * 1000 < now + LIVE_HORIZON_MS) {
            val asked = HashSet<Int>()
            repeat(LIVE_ROUNDS) {
                val stops = journeys.flatMap { journey ->
                    journey.rides.flatMap { ride ->
                        val pattern = router.network.patterns[ride.run.pattern]
                        listOf(pattern.stops[ride.board], pattern.stops[ride.alight])
                    }
                }.filter { it >= 0 && asked.add(it) }
                if (stops.isEmpty()) return@repeat
                val codes = stops.associateBy { router.network.stops[it].code }
                val listed = feeds.tallinn(codes.keys, now)
                for ((code, times) in listed) router.observe(runs, codes.getValue(code), times, now / 1000)
                journeys = router.journeys(origin, destination, start, runs, searches)
            }
        }
        return journeys
            .map { itinerary(router, it, from, to) }
            .filter { !arriveBy || it.end <= time }
    }

    private fun itinerary(router: TallinnRouter, journey: TallinnRouter.Journey, from: Place, to: Place): Itinerary {
        val network = router.network
        fun call(stop: Int, place: Place, scheduled: Long, expected: Long = scheduled, live: Boolean = false): Call =
            if (stop < 0) {
                Call(place.name, place.lat, place.lon, scheduled, expected, live)
            } else {
                val s = network.stops[stop]
                Call(s.name, s.lat, s.lon, scheduled, expected, live, code = s.code)
            }
        val legs = journey.parts.map { part ->
            when (part) {
                is TallinnRouter.Walk -> Leg(
                    ride = null,
                    from = call(part.from, from, part.start * 1000),
                    to = call(part.to, to, part.end * 1000),
                    distance = part.meters,
                )
                is TallinnRouter.RidePart -> {
                    val run = part.run
                    val pattern = network.patterns[run.pattern]
                    fun at(pos: Int) = call(pattern.stops[pos], from, run.scheduled(pos) * 1000, run.time(pos) * 1000, run.isLive)
                    val between = (part.board + 1 until part.alight).filter { pattern.stops[it] >= 0 && run.scheduled(it) >= 0 }
                    Leg(
                        ride = Ride(
                            route = pattern.route,
                            mode = TallinnRouter.modeOf(pattern.transport),
                            headsign = pattern.headsign,
                            longName = pattern.name,
                            serviceDate = Estonia.format("yyyyMMdd", run.base * 1000 + NOON_MS, Locale.ROOT),
                            tripStart = run.start * 1000,
                            feed = LiveFeed.TALLINN,
                        ),
                        from = at(part.board),
                        to = at(part.alight),
                        stops = between.map(::at),
                    )
                }
            }
        }
        return Itinerary(legs, Source.TALLINN).walksRetimed()
    }

    private companion object {
        /** Search, then twice more with what the feed says. */
        const val LIVE_ROUNDS = 3
        const val SEARCHES = 4

        /** For "arrive by": searches from this long before, enough of them to get there. */
        const val ARRIVE_BY_WINDOW_MS = 90 * 60 * 1000L
        const val ARRIVE_BY_SEARCHES = 8

        const val LIVE_HORIZON_MS = 60 * 60 * 1000L

        /** Noon is safely inside the service day, even on the days clocks change. */
        const val NOON_MS = 12 * 60 * 60 * 1000L
    }
}
