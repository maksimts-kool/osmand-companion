package dev.maksim.companion.timetable

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Where the sun is, for telling day from night the way OsmAnd's automatic day/night mode does. */
object Sun {

    /** True between sunset and sunrise at [lat], [lon] (degrees) at [time] (epoch ms). */
    fun isDown(lat: Double, lon: Double, time: Long): Boolean = elevation(lat, lon, time) < HORIZON

    /** The sun's height above the horizon in degrees; the usual low-precision formulas, within a minute or so. */
    private fun elevation(lat: Double, lon: Double, time: Long): Double {
        val d = time / 86_400_000.0 - 10_957.5 // days since 2000-01-01 12:00 UTC
        val meanAnomaly = Math.toRadians(357.529 + 0.98560028 * d)
        val meanLongitude = 280.459 + 0.98564736 * d
        val longitude = Math.toRadians(meanLongitude + 1.915 * sin(meanAnomaly) + 0.020 * sin(2 * meanAnomaly))
        val obliquity = Math.toRadians(23.439 - 0.00000036 * d)
        val rightAscension = Math.toDegrees(atan2(cos(obliquity) * sin(longitude), cos(longitude)))
        val declination = asin(sin(obliquity) * sin(longitude))
        val siderealDegrees = (18.697374558 + 24.06570982441908 * d) * 15
        val hourAngle = Math.toRadians(siderealDegrees + lon - rightAscension)
        val latitude = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(latitude) * sin(declination) + cos(latitude) * cos(declination) * cos(hourAngle)))
    }

    /** Sunrise and sunset as almanacs (and OsmAnd) count them: the sun's top edge on the horizon, with refraction. */
    private const val HORIZON = -0.833
}
