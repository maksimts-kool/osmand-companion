package dev.maksim.companion.planner

import dev.maksim.companion.core.Parallel
import dev.maksim.companion.timetable.LatLon
import dev.maksim.companion.timetable.Polyline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class SavedTripTest {

    private val t0 = 1_790_000_000_000L

    /** Points on the 1e-5° grid, as the planners' polylines have them, across a sign change and with big steps. */
    private val shape = listOf(
        LatLon(59.43696, 24.75353), LatLon(59.43701, 24.75402), LatLon(59.42, 24.7),
        LatLon(-0.00001, -179.99999), LatLon(0.0, 0.0), LatLon(59.43696, 24.75353),
    )

    @Test
    fun polylineEncodesWhatItDecodes() {
        assertEquals(shape, Polyline.decode(Polyline.encode(shape)))
        assertEquals(emptyList<LatLon>(), Polyline.decode(Polyline.encode(emptyList())))
    }

    @Test
    fun polylineMatchesGooglesExample() {
        // The example in Google's description of the format.
        val points = listOf(LatLon(38.5, -120.2), LatLon(40.7, -120.95), LatLon(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Polyline.encode(points))
    }

    @Test
    fun tripReadsBackAsItWas() {
        val ride = Ride(route = "23", mode = "BUS", headsign = "Raja", tripId = "estonia:1", feed = LiveFeed.TALLINN)
        val walk = Leg(null, Call("Home", 59.4, 24.7, t0), Call("Stop", 59.41, 24.71, t0 + 60_000), shape = shape, distance = 120.0)
        val bus = Leg(
            ride, Call("Stop", 59.41, 24.71, t0 + 60_000, code = "S"), Call("Raja", 59.39, 24.68, t0 + 600_000, code = "R"),
            stops = listOf(Call("Kaubamaja", 59.42, 24.75, t0 + 300_000)), shape = emptyList(),
        )
        val itinerary = Itinerary(listOf(walk, bus), Source.PEATUS)
        val trip = ActiveTrip(itinerary, "Home", Place("Raja", 59.39, 24.68), t0, options = listOf(itinerary))

        val bytes = ByteArrayOutputStream().also { out -> ObjectOutputStream(out).use { it.writeObject(trip) } }.toByteArray()
        val read = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as ActiveTrip }

        assertEquals(trip, read)
    }

    @Test
    fun parallelMapKeepsOrderAndFailures() {
        val results = Parallel.map((1..20).toList(), 4) { if (it == 7) throw IllegalStateException("seven") else it * 2 }
        assertEquals(20, results.size)
        assertEquals(12, results[5].getOrNull())
        assertTrue(results[6].exceptionOrNull() is IllegalStateException)
        assertEquals(40, results[19].getOrNull())
    }
}
