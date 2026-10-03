package dev.maksim.companion.planner

import dev.maksim.companion.planner.TripSteps.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class TripStepsTest {

    private val minute = 60_000L
    private val t0 = 1_790_000_000_000L

    private fun call(name: String, at: Long) = Call(name, 59.4, 24.7, at, code = name)
    private fun walk(from: String, to: String, start: Long, minutes: Int) = Leg(null, call(from, start), call(to, start + minutes * minute))
    private fun ride(route: String, from: String, to: String, start: Long, minutes: Int) =
        Leg(Ride(route, "BUS", to), call(from, start), call(to, start + minutes * minute))

    /** Walk 5 min to A, bus 23 at t0 to B (10 min), walk 2 min, bus 5 at t0 + 15 to C (10 min), walk 3 min. */
    private val itinerary = Itinerary(
        listOf(
            walk("Home", "A", t0 - 5 * minute, 5),
            ride("23", "A", "B", t0, 10),
            walk("B", "B2", t0 + 10 * minute, 2),
            ride("5", "B2", "C", t0 + 15 * minute, 10),
            walk("C", "Work", t0 + 25 * minute, 3),
        ),
        Source.PEATUS,
    )

    @Test
    fun aRideIsBoardingThenRiding() {
        assertEquals(
            listOf(Kind.WALK, Kind.BOARD, Kind.RIDE, Kind.WALK, Kind.BOARD, Kind.RIDE, Kind.WALK_THERE, Kind.ARRIVED),
            TripSteps.of(itinerary).map { it.kind },
        )
    }

    @Test
    fun followsTheTimes() {
        val steps = TripSteps.of(itinerary)
        fun at(offset: Long) = steps[TripSteps.current(itinerary, steps, t0 + offset)]
        assertEquals(TripSteps.Step(Kind.WALK, 0), at(-8 * minute))
        assertEquals(TripSteps.Step(Kind.WALK, 0), at(-2 * minute))
        assertEquals(TripSteps.Step(Kind.RIDE, 1), at(minute))
        // Walking to the second stop, then waiting there.
        assertEquals(TripSteps.Step(Kind.WALK, 2), at(11 * minute))
        assertEquals(TripSteps.Step(Kind.BOARD, 3), at(13 * minute))
        assertEquals(TripSteps.Step(Kind.WALK_THERE, 4), at(26 * minute))
        assertEquals(TripSteps.Step(Kind.ARRIVED, 4), at(30 * minute))
    }

    @Test
    fun waitingAtTheFirstStop() {
        val noWalk = Itinerary(itinerary.legs.drop(1), Source.PEATUS)
        val steps = TripSteps.of(noWalk)
        assertEquals(TripSteps.Step(Kind.BOARD, 0), steps[TripSteps.current(noWalk, steps, t0 - 3 * minute)])
    }
}
