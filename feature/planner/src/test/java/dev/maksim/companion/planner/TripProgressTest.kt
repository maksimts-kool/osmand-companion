package dev.maksim.companion.planner

import dev.maksim.companion.planner.TripProgress.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripProgressTest {

    private val minute = 60_000L
    private val t0 = 1_790_000_000_000L

    private fun call(name: String, at: Long, expected: Long = at, live: Boolean = false) =
        Call(name, 59.4, 24.7, at, expected, live, code = name)

    private fun walk(from: String, to: String, start: Long, minutes: Int) =
        Leg(null, call(from, start), call(to, start + minutes * minute), distance = minutes * 80.0)

    private fun ride(route: String, from: String, to: String, start: Long, minutes: Int, late: Int = 0, live: Boolean = late != 0) =
        Leg(
            Ride(route, "BUS", to),
            call(from, start, start + late * minute, live),
            call(to, start + minutes * minute, start + (minutes + late) * minute, live),
            stops = listOf(call("Middle", start + minutes * minute / 2, start + (minutes / 2 + late) * minute, live)),
        )

    /** Walk 5 min to A, bus 23 at t0 to B (10 min), walk 2 min, bus 5 at t0 + 15 to C (10 min), walk 3 min. */
    private fun itinerary(firstLate: Int = 0, secondLate: Int = 0) = Itinerary(
        listOf(
            walk("Home", "A", t0 - 5 * minute, 5),
            ride("23", "A", "B", t0, 10, firstLate),
            walk("B", "B2", t0 + 10 * minute, 2),
            ride("5", "B2", "C", t0 + 15 * minute, 10, secondLate),
            walk("C", "Work", t0 + 25 * minute, 3),
        ),
        Source.TALLINN,
    ).walksRetimed()

    @Test
    fun stepsAlongTheWay() {
        val itinerary = itinerary()
        assertEquals(TripProgress.Progress(Kind.LEAVE, 1, t0 - 5 * minute), TripProgress.at(itinerary, t0 - 8 * minute))
        assertEquals(Kind.TO_STOP, TripProgress.at(itinerary, t0 - 2 * minute).kind)
        TripProgress.at(itinerary, t0 + minute).run {
            assertEquals(Kind.RIDE, kind)
            assertEquals(1, leg)
            assertEquals(t0 + 10 * minute, until)
            // The one in the middle, and where it's got off.
            assertEquals(2, stopsLeft)
        }
        TripProgress.at(itinerary, t0 + 11 * minute).run {
            assertEquals(Kind.TO_STOP, kind)
            assertEquals(3, leg)
        }
        assertEquals(Kind.WALK_THERE, TripProgress.at(itinerary, t0 + 26 * minute).kind)
        assertEquals(Kind.ARRIVED, TripProgress.at(itinerary, t0 + 30 * minute).kind)
    }

    @Test
    fun aLateBusMovesWhenToLeave() {
        val itinerary = itinerary(firstLate = 4)
        assertEquals(t0 - minute, TripProgress.at(itinerary, t0 - 8 * minute).until)
    }

    @Test
    fun aConnectionTheFirstBusIsTooLateForIsMissed() {
        // 23 gets to B at t0 + 14, walk 2: the 5 at t0 + 15 is out of reach.
        val itinerary = itinerary(firstLate = 4)
        assertNull(TripProgress.missed(itinerary, t0 - minute))
        assertEquals(3, TripProgress.missed(itinerary, t0 + 5 * minute))
        // The 5 late too: caught after all.
        assertNull(TripProgress.missed(itinerary(firstLate = 4, secondLate = 2), t0 + 5 * minute))
    }

    @Test
    fun aBusThatLeftEarlyIsMissed() {
        val itinerary = itinerary()
        val legs = itinerary.legs.toMutableList()
        legs[1] = legs[1].copy(ride = legs[1].ride!!.copy(gone = true))
        assertEquals(1, TripProgress.missed(itinerary.copy(legs = legs), t0 - 2 * minute))
        // Once its time has come, it's the one being ridden.
        assertNull(TripProgress.missed(itinerary.copy(legs = legs), t0 + minute))
    }

    @Test
    fun alertsOnceEach() {
        var trip = ActiveTrip(itinerary(), "Home", Place("Work", 59.4, 24.7), t0 - 20 * minute)
        fun alertsAt(now: Long): List<TripProgress.Alert> {
            val (alerts, noted) = TripProgress.alerts(trip, TripProgress.at(trip.itinerary, now), now)
            trip = noted
            return alerts
        }
        assertTrue(alertsAt(t0 - 10 * minute).isEmpty())
        assertTrue(alertsAt(t0 - 6 * minute).single() is TripProgress.Alert.Leave)
        assertTrue(alertsAt(t0 - 5 * minute - 30_000).isEmpty())
        assertTrue(alertsAt(t0 + 8 * minute + 30_000).single() is TripProgress.Alert.GetOff)
        assertTrue(alertsAt(t0 + 9 * minute).isEmpty())
    }

    @Test
    fun delaysAreToldWhenTheyChange() {
        var trip = ActiveTrip(itinerary(firstLate = 1), "Home", Place("Work", 59.4, 24.7), t0 - 20 * minute)
        val now = t0 - 15 * minute
        fun alertsWith(late: Int): List<TripProgress.Alert> {
            trip = trip.copy(itinerary = itinerary(firstLate = late))
            val (alerts, noted) = TripProgress.alerts(trip, TripProgress.at(trip.itinerary, now), now)
            trip = noted
            return alerts
        }
        // The first seen is only noted.
        assertTrue(alertsWith(1).isEmpty())
        assertTrue(alertsWith(2).isEmpty())
        assertEquals(4, (alertsWith(4).single() as TripProgress.Alert.Delay).minutes)
        assertTrue(alertsWith(5).isEmpty())
        assertEquals(1, (alertsWith(1).single() as TripProgress.Alert.Delay).minutes)
    }
}
