package dev.maksim.companion.planner

import dev.maksim.companion.timetable.TallinnLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class TallinnRouterTest {

    /**
     * Line 1 goes A → B → C, a stop every 5 minutes; line 2 goes C' (across the street from C) → D in 10. Both every
     * 10 minutes from 8:00 on a Monday.
     */
    private fun network(): TallinnNetwork {
        val stops = listOf(
            TallinnNetwork.Stop("A", "1", "A", 59.400, 24.700),
            TallinnNetwork.Stop("B", "2", "B", 59.410, 24.700),
            TallinnNetwork.Stop("C", "3", "C", 59.420, 24.700),
            TallinnNetwork.Stop("C2", "4", "C'", 59.4205, 24.7005),
            TallinnNetwork.Stop("D", "5", "D", 59.430, 24.730),
        )
        fun trips(first: Int, gaps: List<Int>) = (0 until 6).map { i ->
            var time = first + i * 10
            TallinnNetwork.Trip(intArrayOf(time) + gaps.map { time += it; time }, 20000, 0, "12345")
        }
        fun pattern(route: String, stops: IntArray, trips: List<TallinnNetwork.Trip>, name: String) = TallinnNetwork.Pattern(
            route, "bus", name, stops, BooleanArray(stops.size) { true }, BooleanArray(stops.size) { true }, trips, emptyList(),
        )
        return TallinnNetwork(
            stops,
            listOf(
                pattern("1", intArrayOf(0, 1, 2), trips(480, listOf(5, 5)), "A - C"),
                pattern("2", intArrayOf(3, 4), trips(485, listOf(10)), "C - D"),
            ),
            emptyMap(),
        )
    }

    private val utc = TimeZone.getTimeZone("UTC")

    /** Monday 2024-09-02 (day 19968), at [minutes] past midnight UTC, in epoch seconds. */
    private fun at(minutes: Int) = 19968 * 86_400L + minutes * 60L

    private fun runs(router: TallinnRouter, time: Long): TallinnRouter.Runs {
        val day = TallinnRouter.serviceDays(time * 1000, utc)[1]
        assertEquals(19968, day.day)
        assertEquals(1, day.weekday)
        return TallinnRouter.Runs(Array(router.network.patterns.size) { p ->
            router.network.patterns[p].trips.map { TallinnRouter.Run(p, it, day.midnight) }
        })
    }

    @Test
    fun ridesAndChangesAcrossTheStreet() {
        val router = TallinnRouter(network())
        val runs = runs(router, at(478))
        val journeys = router.search(
            listOf(TallinnRouter.Access(0, 0.0)), listOf(TallinnRouter.Access(4, 0.0)), at(478), runs,
        )
        val journey = journeys.single()
        assertEquals(2, journey.rides.size)
        // Line 1 at 8:00 gets to C at 8:10, too late for line 2's 8:05; its 8:15 gets to D at 8:25.
        assertEquals(at(480), journey.rides[0].run.time(journey.rides[0].board))
        assertEquals(at(495), journey.rides[1].run.time(journey.rides[1].board))
        assertEquals(at(505), journey.arrival)
    }

    @Test
    fun aLateBusIsTakenAsLate() {
        val router = TallinnRouter(network())
        val runs = runs(router, at(487))
        // At B at 8:07, the 8:05 has gone by the timetable; but its vehicle is 5 minutes late.
        val feed = TallinnLive.Times(
            listOf(TallinnLive.Time("BUS", "1", "C", at(485), at(490)), TallinnLive.Time("BUS", "1", "C", at(495), at(495))),
        )
        val origin = listOf(TallinnRouter.Access(1, 0.0))
        val destination = listOf(TallinnRouter.Access(2, 0.0))
        assertEquals(at(500), router.search(origin, destination, at(487), runs).single().arrival)
        router.observe(runs, 1, feed, now = at(487))
        val ride = router.search(origin, destination, at(487), runs).single().rides.single()
        assertTrue(ride.run.isLive)
        assertEquals(at(490), ride.run.time(ride.board))
        assertEquals(at(495), ride.run.time(2))
    }

    @Test
    fun aDelayBeforeSettingOffIsNotTrusted() {
        val router = TallinnRouter(network())
        val runs = runs(router, at(482))
        // The 8:00 from A, its vehicle still on the trip before: the feed's +5 is a guess.
        val feed = TallinnLive.Times(listOf(TallinnLive.Time("BUS", "1", "C", at(480), at(485))))
        router.observe(runs, 0, feed, now = at(479))
        assertTrue(runs.byPattern[0].none { it.isLive })
    }

    @Test
    fun aBusTheFeedNoLongerListsHasGone() {
        val router = TallinnRouter(network())
        val runs = runs(router, at(489))
        // At 8:09 the feed lists the 8:20 first: the 8:10 left early.
        val feed = TallinnLive.Times(listOf(TallinnLive.Time("BUS", "1", "C", at(500), at(500))))
        router.observe(runs, 0, feed, now = at(489))
        val journey = router.search(listOf(TallinnRouter.Access(0, 0.0)), listOf(TallinnRouter.Access(1, 0.0)), at(489), runs).single()
        assertEquals(at(500), journey.rides.single().run.time(0))
    }

    @Test
    fun serviceDaysKnowTheWeekday() {
        val days = TallinnRouter.serviceDays(at(600) * 1000, utc)
        assertEquals(listOf(19967, 19968, 19969), days.map { it.day })
        assertEquals(listOf(7, 1, 2), days.map { it.weekday })
    }
}
