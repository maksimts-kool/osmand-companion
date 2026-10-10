package dev.maksim.companion.planner

import dev.maksim.companion.planner.TripSteps.Kind
import dev.maksim.companion.timetable.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TripPositionTest {

    private val minute = 60_000L
    private val t0 = 1_790_000_000_000L

    /** About 111 m a step north, per 0.001 of latitude. */
    private fun call(name: String, north: Double, at: Long) = Call(name, 59.4 + north, 24.7, at, code = name)

    /** Walk 800 m from Home to A (10 min), bus at t0 to B (10 min), walk 220 m to Work (3 min). */
    private val itinerary = Itinerary(
        listOf(
            Leg(null, call("Home", 0.0, t0 - 10 * minute), call("A", 0.0072, t0), distance = 800.0),
            Leg(Ride("23", "BUS", "B"), call("A", 0.0072, t0), call("B", 0.03, t0 + 10 * minute)),
            Leg(null, call("B", 0.03, t0 + 10 * minute), call("Work", 0.032, t0 + 13 * minute), distance = 220.0),
        ),
        Source.PEATUS,
    )
    private val trip = ActiveTrip(itinerary, "Home", Place("Work", 59.432, 24.7), startedAt = t0 - 20 * minute)

    private fun at(north: Double) = LatLon(59.4 + north, 24.7)

    @Test
    fun walkedByWhereYouAre() {
        val walk = itinerary.legs[0]
        assertEquals(0f, TripPosition.walked(walk, at(0.0), null)!!.fraction, 0.01f)
        TripPosition.walked(walk, at(0.0036), null)!!.run {
            assertEquals(0.5f, fraction, 0.02f)
            assertEquals(400.0, metersLeft.toDouble(), 10.0)
        }
        // OsmAnd's own distance left, when it's navigating there.
        assertEquals(0.75f, TripPosition.walked(walk, at(0.0), OsmAndTrip.Navigation(120_000, 200, t0))!!.fraction, 0.01f)
        assertNull(TripPosition.walked(walk, null, null))
    }

    @Test
    fun atTheStopItsBoardingHoweverEarly() {
        val now = t0 - 15 * minute
        // Still at home: nothing reached.
        assertSame(trip, TripPosition.update(trip, at(0.0), null, now))
        val there = TripPosition.update(trip, at(0.0071), null, now)
        assertTrue(TripProgress.stopKey(itinerary.legs[1].from) in there.reached)
        // Before leaving time by the clock, waiting at the stop now.
        assertEquals(TripProgress.Kind.TO_STOP, TripProgress.at(itinerary, now, there.reached).kind)
        val steps = TripSteps.of(itinerary)
        assertEquals(TripSteps.Step(Kind.BOARD, 1), steps[TripSteps.current(itinerary, steps, now, there.reached)])
        // No walk to show any more.
        assertNull(TripPosition.walkingLeg(itinerary, TripProgress.at(itinerary, now, there.reached), there.reached))
    }

    @Test
    fun stillWalkingWhenLateIfOsmAndKnowsWhere() {
        // The walk there ends 3 min before the bus, as one between rides does.
        val early = itinerary.copy(
            legs = listOf(
                Leg(null, call("Home", 0.0, t0 - 13 * minute), call("A", 0.0072, t0 - 3 * minute), distance = 800.0),
            ) + itinerary.legs.drop(1),
        )
        val steps = TripSteps.of(early)
        val late = t0 - minute
        // By the clock, the walk's done; by where you are, it isn't.
        assertEquals(TripSteps.Step(Kind.BOARD, 1), steps[TripSteps.current(early, steps, late)])
        assertEquals(TripSteps.Step(Kind.WALK, 0), steps[TripSteps.current(early, steps, late, located = true)])
        assertEquals(0, TripPosition.walkingLeg(early, TripProgress.at(early, late), emptyMap()))
    }

    @Test
    fun thereEarlyIsThere() {
        val now = t0 + 11 * minute
        val walking = TripPosition.update(trip, at(0.031), null, now)
        assertSame(trip, walking)
        val there = TripPosition.update(trip, at(0.0319), null, now)
        assertTrue(TripProgress.END in there.reached)
        assertEquals(TripProgress.Kind.ARRIVED, TripProgress.at(itinerary, now, there.reached).kind)
    }

    @Test
    fun progressWhileWalkingGoesByTheWalk() {
        // Half the first walk, whatever the clock says: 5 of the way's 23 min.
        assertEquals(5f / 23, TripSteps.fraction(itinerary, t0 - 9 * minute, 0 to 0.5f), 0.001f)
    }

    @Test
    fun osmandFinishingItsRouteIsGettingThere() {
        val now = t0 - 15 * minute
        // Where OsmAnd has you is from before (it's in the background), but it says it got you there.
        val there = TripPosition.update(trip, at(0.0), null, now, arrived = true)
        assertTrue(TripProgress.stopKey(itinerary.legs[1].from) in there.reached)
    }

    @Test
    fun osmandsRouteGoneCloseByIsArriving() {
        val near = OsmAndTrip.Navigation(30_000, 25, t0)
        val far = OsmAndTrip.Navigation(400_000, 600, t0)
        val gone = OsmAndTrip.Position(at(0.0), null)
        assertTrue(TripPosition.osmandArrived(near, gone))
        // Cancelled on the way, or never navigating.
        assertTrue(!TripPosition.osmandArrived(far, gone))
        assertTrue(!TripPosition.osmandArrived(null, gone))
        // Still navigating, or OsmAnd didn't answer.
        assertTrue(!TripPosition.osmandArrived(near, OsmAndTrip.Position(at(0.0), near)))
        assertTrue(!TripPosition.osmandArrived(near, null))
        // Its route finished with its map up, from further.
        assertTrue(TripPosition.osmandArrived(far, OsmAndTrip.Position(at(0.0), null, routeDone = true)))
    }
}
