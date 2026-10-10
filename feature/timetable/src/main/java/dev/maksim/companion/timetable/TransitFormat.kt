package dev.maksim.companion.timetable

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sqrt

/** peatus.ee only covers Estonia; the feature does nothing while OsmAnd's map is elsewhere. */
object Estonia {
    /** Timetables are in local time; show them that way even on a phone set to another zone. */
    val timeZone: TimeZone = TimeZone.getTimeZone("Europe/Tallinn")

    // Bounding box from Ruhnu and Vilsandi in the south-west to Narva in the east.
    fun contains(lat: Double, lon: Double) = lat in 57.45..59.85 && lon in 21.45..28.25

    /** Service date for peatus.ee (yyyyMMdd), [daysFromToday] days from today in Estonia. */
    fun serviceDate(daysFromToday: Int = 0): String = format("yyyyMMdd", dayStart(daysFromToday))

    /** Midnight in Estonia, [daysFromToday] days from today, as epoch ms. */
    fun dayStart(daysFromToday: Int): Long = Calendar.getInstance(timeZone).run {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, daysFromToday)
        timeInMillis
    }

    fun format(pattern: String, time: Long, locale: Locale = Locale.getDefault()): String =
        formatter(pattern, locale).format(Date(time))

    /**
     * A format for [pattern] in [locale] and Estonia's time, made once per thread: making one is slow, and the
     * timetable screens format thousands of times. Not to be shared with another thread.
     */
    fun formatter(pattern: String, locale: Locale = Locale.getDefault()): SimpleDateFormat =
        formatters.get()!!.getOrPut(pattern to locale) {
            SimpleDateFormat(pattern, locale).apply { timeZone = this@Estonia.timeZone }
        }

    // Not ThreadLocal.withInitial, which is Android 8+.
    private val formatters = object : ThreadLocal<HashMap<Pair<String, Locale>, SimpleDateFormat>>() {
        override fun initialValue() = HashMap<Pair<String, Locale>, SimpleDateFormat>()
    }
}

/** Distance in meters; plenty accurate for "has the map moved a few hundred meters". */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dy = (lat2 - lat1) * 111_320.0
    val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
    return sqrt(dx * dx + dy * dy)
}

/** Kinds of vehicle as peatus.ee names them (OpenTripPlanner modes), with how we show them. */
enum class Mode(val color: Int, val icon: Int, val label: Int, val osmandIcon: String) {
    BUS(0xFF00897B.toInt(), R.drawable.tt_ic_bus, R.string.tt_mode_bus, "ic_action_bus_dark"),
    TROLLEYBUS(0xFF1E88E5.toInt(), R.drawable.tt_ic_trolleybus, R.string.tt_mode_trolleybus, "ic_action_transport_trolley"),
    REGIONAL(0xFF8E24AA.toInt(), R.drawable.tt_ic_bus, R.string.tt_mode_regional, "ic_action_bus_dark"),
    TRAM(0xFFE53935.toInt(), R.drawable.tt_ic_tram, R.string.tt_mode_tram, "ic_action_transport_tram"),
    RAIL(0xFFF57C00.toInt(), R.drawable.tt_ic_train, R.string.tt_mode_train, "ic_action_train"),
    FERRY(0xFF3949AB.toInt(), R.drawable.tt_ic_ferry, R.string.tt_mode_ferry, "ic_action_sail_boat_dark"),
    OTHER(0xFF757575.toInt(), R.drawable.tt_ic_bus, R.string.tt_mode_other, "ic_action_bus_dark"),
    ;

    companion object {
        fun of(mode: String?): Mode = when (mode) {
            "BUS" -> BUS
            PeatusClient.TROLLEYBUS -> TROLLEYBUS
            PeatusClient.REGIONAL, "COACH" -> REGIONAL
            "TRAM" -> TRAM
            "RAIL", "SUBWAY" -> RAIL
            "FERRY" -> FERRY
            else -> OTHER
        }
    }
}

object TransitFormat {

    fun clock(time: Long): String = Estonia.format("HH:mm", time)

    /** "now", "7 min", "1 h 5 min"; null beyond two hours, where the clock time says enough. */
    fun relative(context: Context, time: Long, now: Long): String? {
        val minutes = ((time - now) / 60_000).toInt()
        return when {
            minutes < 1 -> context.getString(R.string.tt_now)
            minutes < 60 -> context.getString(R.string.tt_in_min, minutes)
            minutes < 120 -> context.getString(R.string.tt_in_h_min, minutes / 60, minutes % 60)
            else -> null
        }
    }

    /** "20:14", or "Tue 06:15" when it's not today. */
    fun clockWithDay(time: Long, now: Long, locale: Locale = Locale.getDefault()): String =
        if (Estonia.format("yyyyMMdd", time) == Estonia.format("yyyyMMdd", now)) clock(time)
        else Estonia.format("EEE HH:mm", time, locale)

    /** "Bus stop · to Pelguranna, Väike-Õismäe": tells the two sides of a street apart. */
    fun stopType(context: Context, stop: Stop): String {
        val type = context.getString(R.string.tt_stop_type, context.getString(Mode.of(stop.mode).label))
        val towards = stop.departures.map { it.headsign }.filter { it.isNotEmpty() }.distinct().take(2)
        return if (towards.isEmpty()) type else context.getString(R.string.tt_stop_towards, type, towards.joinToString())
    }
}
