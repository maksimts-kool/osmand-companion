package dev.maksim.companion.planner

import org.junit.Assert.assertEquals
import org.junit.Test

class RankingTest {

    private val minute = 60_000L

    /** Walk from [start] for 5 minutes, ride [route] for [ride] minutes, walk 5. */
    private fun itinerary(route: String, start: Long, ride: Int, source: Source = Source.PEATUS, live: Boolean = false, rides: Int = 1): Itinerary {
        val legs = ArrayList<Leg>()
        var time = start
        legs += Leg(null, Call("Home", 0.0, 0.0, time), Call("Stop", 0.0, 0.0, time + 5 * minute, code = "A"), distance = 300.0)
        time += 5 * minute
        repeat(rides) { i ->
            val end = time + ride * minute / rides
            legs += Leg(
                Ride(route, "BUS", "There"),
                Call("Stop", 0.0, 0.0, time, isLive = live, code = "A$i"),
                Call("Stop 2", 0.0, 0.0, end, isLive = live, code = "B$i"),
            )
            time = end
        }
        legs += Leg(null, Call("Stop 2", 0.0, 0.0, time), Call("Work", 0.0, 0.0, time + 5 * minute), distance = 300.0)
        return Itinerary(legs, source)
    }

    @Test
    fun sameItineraryFoundTwiceIsShownOnceTheLiveOne() {
        val timetable = itinerary("23", 0, 20, Source.PEATUS)
        val live = itinerary("23", 0, 20, Source.TALLINN, live = true)
        val ranked = Ranking.rank(listOf(timetable, live))
        assertEquals(listOf(Source.TALLINN), ranked.map { it.source })
    }

    @Test
    fun soonestArrivalFirstAndNoWorseOnesLeft() {
        val fast = itinerary("5", 10 * minute, 15)
        val slow = itinerary("23", 10 * minute, 30)
        // Leaves earlier and gets there later than fast: no reason to take it.
        val worse = itinerary("8", 0, 40)
        // Gets there later, but leaves later too: a choice.
        val later = itinerary("5", 20 * minute, 15)
        val ranked = Ranking.rank(listOf(slow, worse, later, fast))
        assertEquals(listOf("5", "5"), ranked.map { it.rides.first().ride!!.route })
        assertEquals(listOf(fast, later), ranked)
    }

    @Test
    fun aChangeMoreIsWorthItOnlyIfSooner() {
        val direct = itinerary("5", 0, 20)
        val changing = itinerary("7", 0, 20, rides = 2)
        assertEquals(listOf(direct), Ranking.rank(listOf(changing, direct)))
    }

    @Test
    fun arriveByLeavesAsLateAsPossible() {
        val early = itinerary("5", 0, 15)
        val late = itinerary("5", 10 * minute, 15)
        val tooLate = itinerary("5", 30 * minute, 15)
        val ranked = Ranking.rank(listOf(early, late, tooLate), arriveBy = 40 * minute)
        // The earlier one gets there earlier: still a choice, after.
        assertEquals(listOf(late, early), ranked)
    }
}
