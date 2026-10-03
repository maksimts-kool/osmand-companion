package dev.maksim.companion.planner

import dev.maksim.companion.timetable.RidangoLive
import dev.maksim.companion.timetable.TallinnLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetimeTest {

    private val minute = 60_000L
    private val t0 = 1_790_000_000_000L

    private fun call(name: String, at: Long, code: String) = Call(name, 59.4, 24.7, at, code = code)

    private fun ride(feed: LiveFeed = LiveFeed.TALLINN) =
        Ride(route = "23", mode = "BUS", headsign = "Raja", tripStart = t0 - 10 * minute, feed = feed)

    /** Bus 23 from Vabaduse väljak at t0 to Raja at t0 + 20 min, past Kaubamaja at t0 + 5. */
    private fun leg(feed: LiveFeed = LiveFeed.TALLINN) = Leg(
        ride(feed),
        call("Vabaduse väljak", t0, "V"),
        call("Raja", t0 + 20 * minute, "R"),
        stops = listOf(call("Kaubamaja", t0 + 5 * minute, "K")),
    )

    private fun time(scheduled: Long, expected: Long, route: String = "23", to: String = "Raja") =
        TallinnLive.Time("BUS", route, to, scheduled / 1000, expected / 1000)

    @Test
    fun tallinnLegGetsItsVehiclesTimes() {
        val city = mapOf(
            "V" to TallinnLive.Times(listOf(time(t0, t0 + 3 * minute))),
            "R" to TallinnLive.Times(listOf(time(t0 + 20 * minute, t0 + 24 * minute))),
        )
        val leg = Retime.tallinn(leg(), ride(), now = t0, city = city)
        assertEquals(t0 + 3 * minute, leg.departure)
        assertTrue(leg.from.isLive)
        assertEquals(t0 + 24 * minute, leg.arrival)
        // In between, the delay where it's got on.
        assertEquals(t0 + 8 * minute, leg.stops.single().expected)
        assertFalse(leg.ride!!.gone)
    }

    @Test
    fun tallinnArrivalCarriesTheDelayWhereTheFeedSaysNothing() {
        val city = mapOf("V" to TallinnLive.Times(listOf(time(t0, t0 + 2 * minute))))
        val leg = Retime.tallinn(leg(), ride(), now = t0, city = city)
        assertEquals(t0 + 22 * minute, leg.arrival)
        assertTrue(leg.to.isLive)
    }

    @Test
    fun tallinnRunTheFeedNoLongerListsHasGone() {
        val city = mapOf("V" to TallinnLive.Times(listOf(time(t0 + 10 * minute, t0 + 10 * minute))))
        val leg = Retime.tallinn(leg(), ride(), now = t0 - minute, city = city)
        assertTrue(leg.ride!!.gone)
    }

    @Test
    fun nextOfTheLineReplacesAMissedRun() {
        val city = mapOf(
            "V" to TallinnLive.Times(listOf(time(t0, t0), time(t0 + 10 * minute, t0 + 11 * minute))),
        )
        val next = Retime.nextTallinn(leg(), ride(), after = t0 + minute, city = city)!!
        assertEquals(t0 + 11 * minute, next.departure)
        // Its timetable is 10 minutes on, and it's a minute late.
        assertEquals(t0 + 31 * minute, next.arrival)
        assertTrue(next.ride!!.replaced)
        assertNull(next.ride!!.tripId)
    }

    @Test
    fun countyLegGetsRidangosTimes() {
        val midnight = t0 - 10 * 60 * minute
        fun seconds(at: Long) = ((at - midnight) / 1000).toInt()
        val times = listOf(
            RidangoLive.StopTime("V", seconds(t0), seconds(t0 + 4 * minute), true),
            RidangoLive.StopTime("K", seconds(t0 + 5 * minute), seconds(t0 + 9 * minute), true),
            RidangoLive.StopTime("R", seconds(t0 + 20 * minute), seconds(t0 + 20 * minute), false),
        )
        val leg = Retime.county(leg(LiveFeed.COUNTY), times, midnight)
        assertEquals(t0 + 4 * minute, leg.departure)
        assertEquals(t0 + 9 * minute, leg.stops.single().expected)
        // Not live there: the delay carries on.
        assertEquals(t0 + 24 * minute, leg.arrival)
    }

    @Test
    fun walksFollowTheRides() {
        val walk = Leg(null, call("Home", t0 - 10 * minute, "-"), call("Vabaduse väljak", t0 - 5 * minute, "V"), distance = 400.0)
        val ride = leg().copy(from = leg().from.copy(expected = t0 + 3 * minute))
        val end = Leg(null, call("Raja", t0 + 20 * minute, "R"), call("Work", t0 + 23 * minute, "-"), distance = 200.0)
        val itinerary = Itinerary(listOf(walk, ride, end), Source.PEATUS).walksRetimed()
        // Leave 5 minutes before the bus as it's expected now, not as timetabled.
        assertEquals(t0 - 2 * minute, itinerary.start)
        assertEquals(t0 + 23 * minute, itinerary.end)
    }

    @Test
    fun walksOneAfterTheOtherAreOne() {
        val a = Leg(null, call("Home", t0, "-"), call("Corner", t0 + 2 * minute, "-"), distance = 150.0)
        val b = Leg(null, call("Corner", t0 + 2 * minute, "-"), call("Vabaduse väljak", t0 + 4 * minute, "V"), distance = 100.0)
        val joined = Itinerary(listOf(a, b, leg()), Source.PEATUS).walksJoined()
        assertEquals(2, joined.legs.size)
        assertEquals("Home", joined.legs[0].from.name)
        assertEquals("Vabaduse väljak", joined.legs[0].to.name)
        assertEquals(250.0, joined.legs[0].distance, 0.1)
    }

    @Test
    fun headsignsGiveOrTakeBrackets() {
        assertTrue(Retime.sameWay("Balti jaam (train station)", "Balti jaam"))
        assertTrue(Retime.sameWay("Reisisadam", "Reisisadam A-terminal"))
        assertFalse(Retime.sameWay("Kopli", "Kadriorg"))
    }
}
