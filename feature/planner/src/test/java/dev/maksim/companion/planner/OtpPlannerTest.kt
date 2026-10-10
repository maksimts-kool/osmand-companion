package dev.maksim.companion.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpPlannerTest {

    @Test
    fun ridangoIdsAsPeatusOnes() {
        assertEquals("estonia:tallinna-lin_bus_23-1", OtpPlanner.peatusId("1:tallinna-lin_bus_23-1"))
        assertEquals("estonia:27136", OtpPlanner.peatusTripId("1:estonia-27136"))
        assertEquals("estonia:ATL_al_21.09.26_x", OtpPlanner.peatusTripId("1:74_ATL_al_21.09.26_x"))
        assertEquals("estonia:ATL_x", OtpPlanner.peatusTripId("1:ATL_x"))
        assertEquals("estonia:1323", OtpPlanner.peatusStopId("1:1323"))
        // By the code on the sign: only the code says which.
        assertNull(OtpPlanner.peatusStopId("1:12403-1"))
        // peatus.ee's own stay.
        assertEquals("estonia:161983", OtpPlanner.peatusStopId("estonia:161983"))
    }

    @Test
    fun ridangosZonesLeaveStopNames() {
        assertEquals("Pirni", OtpPlanner.stopName("Pirni (Harju1)"))
        assertEquals("Balti jaam (train station)", OtpPlanner.stopName("Balti jaam (train station) (Harju1)"))
        assertEquals("Balti jaam (train station)", OtpPlanner.stopName("Balti jaam (train station)"))
    }
}
