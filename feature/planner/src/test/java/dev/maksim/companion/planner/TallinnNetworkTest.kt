package dev.maksim.companion.planner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TallinnNetworkTest {

    @Test
    fun decodesStartsValidityWeekdaysAndTravelTimes() {
        // Two trips at 5:00 and 5:10; valid from day 20717 with no end; weekdays then Saturday and Sunday.
        // Stop 2 is 7 min on for both; stop 3 is 5 min on for the first, 9 for the second.
        val trips = TallinnNetwork.decodeTimes("+300,+10,,20717,,0,,12345,1,67,,7,,5,1,9,,", 3)
        assertEquals(2, trips.size)
        assertArrayEquals(intArrayOf(300, 307, 312), trips[0].times)
        assertArrayEquals(intArrayOf(310, 317, 326), trips[1].times)
        assertEquals("12345", trips[0].weekdays)
        assertEquals("67", trips[1].weekdays)
        assertEquals(20717, trips[1].validFrom)
        assertEquals(0, trips[1].validTo)
    }

    @Test
    fun aStopSkippedIsFarNegative() {
        val trips = TallinnNetwork.decodeTimes("+600,,20000,,0,,1234567,,5,,-9995,,10010,,", 4)
        assertArrayEquals(intArrayOf(600, 605, -1, 620), trips[0].times)
    }

    @Test
    fun nightBusesPastMidnightAreADayEarlier() {
        val trips = TallinnNetwork.decodeTimes("+1500,+30,,20000,,0,,67,,4,,", 2)
        assertArrayEquals(intArrayOf(60, 64), trips[0].times)
        assertArrayEquals(intArrayOf(90, 94), trips[1].times)
    }

    @Test
    fun startsGoBackForAnotherDaysTrips() {
        // Weekdays' trips, then Saturday's from the morning again (the leading 0 marks a low-floor vehicle).
        val trips = TallinnNetwork.decodeTimes("+325,+1000,-01030,,20000,,0,,12345,2,6,,3,,", 2)
        assertEquals(listOf(325, 1325, 295), trips.map { it.times[0] })
        assertEquals(listOf(328, 1328, 298), trips.map { it.times[1] })
        assertEquals(listOf("12345", "12345", "6"), trips.map { it.weekdays })
    }

    private val stops = """
        ID;SiriID;Lat;Lng;Stops;Name;Info;Street;Area;City
        a1;;5943000;2474000;1-1,1-2;Area;0;0;Kesklinn;Tallinn
        1-1;101;5943000;2474000;;First;;Street
        1-2;102;5943100;2474000;;
        2-1;201;5944000;2475000;;Second
    """.trimIndent()

    private val routes = """
        RouteNum;Authority;City;Transport;Operator;ValidityPeriods;SpecialDates;RouteTag;RouteType;Commercial;RouteName;Weekdays;Streets;RouteStops;RouteStopsPlatforms
        1;SpecialDates;;;;20811,1;;;;;;;;
        ;;;;;20808,1,,;;;;;;1;;
        5;Tallinna TA;tallinna-linn;bus;TLT;;1,7;;a-b;A;First - Second;1234567z;;1-1,x2-1;
        +480,,20700,,0,,12345,1,7,,10,,
        ;;;;;;;;b-a;A;Second - First;1234567z;;e2-1,1-2;
        +500,,20700,,20800,,1234567,,10,,
    """.trimIndent()

    @Test
    fun parsesStopsPatternsAndHolidays() {
        val network = TallinnNetwork.parse(stops, routes)
        assertEquals(listOf("1-1", "1-2", "2-1"), network.stops.map { it.code })
        // An empty name is the one before's.
        assertEquals("First", network.stops[1].name)
        assertEquals(59.43, network.stops[0].lat, 1e-9)

        assertEquals(2, network.patterns.size)
        val there = network.patterns[0]
        assertEquals("5", there.route)
        assertEquals("bus", there.transport)
        assertEquals("Second", there.headsign)
        assertArrayEquals(intArrayOf(0, 2), there.stops)
        // "x": only to get off.
        assertFalse(there.canBoard[1])
        assertTrue(there.canAlight[1])
        // The second line of route 5 keeps its number and kind.
        val back = network.patterns[1]
        assertEquals("5", back.route)
        assertEquals("bus", back.transport)
        assertFalse(back.canAlight[0])

        // Day 20811 is a holiday (group 1): route 5 runs as on a Sunday then.
        val weekdayTrip = there.trips.single()
        assertTrue(network.runs(there, weekdayTrip, day = 20810, weekday = 3))
        assertFalse(network.runs(there, weekdayTrip, day = 20811, weekday = 4))
        // Valid to day 20800.
        assertTrue(network.runs(back, back.trips.single(), day = 20800, weekday = 1))
        assertFalse(network.runs(back, back.trips.single(), day = 20801, weekday = 2))
        // Days listed without a group (20808 to 20810) still run as themselves.
        assertFalse(network.runs(there, weekdayTrip, day = 20809, weekday = 7))
        // Not yet valid.
        assertFalse(network.runs(there, weekdayTrip, day = 20699, weekday = 1))
    }
}
