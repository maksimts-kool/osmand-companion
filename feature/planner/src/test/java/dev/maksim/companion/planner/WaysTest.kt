package dev.maksim.companion.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaysTest {

    private val minute = 60_000L
    private val t0 = 1_790_000_000_000L

    private fun call(name: String, at: Long) = Call(name, 59.4, 24.7, at, code = name)

    private fun itinerary(route: String, from: String, to: String, start: Long) = Itinerary(
        listOf(
            Leg(null, call("Home", start - 5 * minute), call(from, start)),
            Leg(Ride(route, "BUS", to), call(from, start), call(to, start + 10 * minute)),
        ),
        Source.PEATUS,
    )

    @Test
    fun sameStopsAreOneWay() {
        val ranked = listOf(
            itinerary("33", "Vilde", "Nõmme", t0 + 20 * minute),
            itinerary("36", "Vilde", "Pääsküla", t0 + 7 * minute),
            itinerary("27", "Vilde", "Nõmme", t0 + 4 * minute),
            itinerary("33", "Vilde", "Nõmme", t0 + 39 * minute),
        )
        val ways = Way.group(ranked)
        assertEquals(2, ways.size)
        // In the order of each's best; the best is the one ranked first.
        assertEquals("33", ways[0].best.rides.first().ride!!.route)
        // Its departures from the first.
        assertEquals(listOf(t0 + 4 * minute, t0 + 20 * minute, t0 + 39 * minute), ways[0].departures)
        assertEquals(listOf(listOf("27", "33")), ways[0].lines)
        assertEquals(listOf(listOf("36")), ways[1].lines)
    }

    @Test
    fun walkingIsAWayOfItsOwn() {
        val walk = Itinerary(listOf(Leg(null, call("Home", t0), call("Work", t0 + 30 * minute))), Source.PEATUS)
        val ways = Way.group(listOf(itinerary("23", "A", "B", t0), walk))
        assertEquals(2, ways.size)
        assertTrue(ways[1].isWalk)
    }
}
