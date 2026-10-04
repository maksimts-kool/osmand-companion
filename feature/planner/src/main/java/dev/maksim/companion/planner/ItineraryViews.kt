package dev.maksim.companion.planner

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import dev.maksim.companion.planner.databinding.PlItemWayBinding
import dev.maksim.companion.timetable.Arrows
import dev.maksim.companion.timetable.Mode
import dev.maksim.companion.timetable.Rows
import dev.maksim.companion.timetable.TransitFormat
import kotlin.math.abs

/** How itineraries read on screen: the Trips tab's rows, and the words for their steps. */
object ItineraryViews {

    /**
     * A row for [way] at the end of [rows], at [now], as Citymapper lists them: the walks and lines ("27 / 33") with
     * how long it takes, and when its departures leave ("in 4, 21, 39 min from Koidu"; for a time picked ahead,
     * [planned], at what times). [onClick] opens it.
     */
    fun way(rows: ViewGroup, way: Way, now: Long, planned: Boolean, onClick: () -> Unit) {
        val context = rows.context
        val row = PlItemWayBinding.inflate(LayoutInflater.from(context), rows, true)
        val live = context.getColor(dev.maksim.companion.timetable.R.color.tt_live)
        val variant = MaterialColors.getColor(row.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val best = way.best
        val minutes = minutes(best.end - best.start)
        row.duration.text = if (minutes < 60) minutes.toString() else duration(context, best.end - best.start)
        row.unit.isVisible = minutes < 60
        Arrows.set(row.arrive, "→ ${TransitFormat.clock(best.end)}")
        row.root.contentDescription = listOf(summary(context, best), departures(context, way, now, planned)).joinToString(", ")
        if (way.isWalk) {
            chain(row.chain, best, null, walkLabel = context.getString(R.string.pl_walk_tile))
            row.departures.text = context.getString(R.string.pl_walk_distance, roundMeters(best.walkMeters))
            Rows.liveMark(row.departuresLive, false, live)
        } else {
            chain(row.chain, best, way.lines)
            val leavesLive = way.options.first().rides.first().from.isLive
            Rows.liveMark(row.departuresLive, leavesLive, live)
            row.departures.text = departures(context, way, now, planned)
            row.departures.setTextColor(if (leavesLive) live else variant)
        }
        val warning = warning(context, best, now)
        row.warning.isVisible = warning != null
        row.warning.text = warning
        row.root.setOnClickListener { onClick() }
    }

    /** "in 4, 21, 39 min from Koidu"; "at 18:52, 19:07 from Koidu" for a time picked ahead or when it's far off. */
    fun departures(context: Context, way: Way, now: Long, planned: Boolean): String {
        val first = way.options.first().rides.firstOrNull() ?: return context.getString(R.string.pl_walk_only)
        val times = way.departures.take(MAX_DEPARTURES)
        return if (!planned && times.last() - now < SOON_MS) {
            context.getString(R.string.pl_way_in, times.joinToString(", ") { countdown(it - now).toString() }, first.from.name)
        } else {
            context.getString(R.string.pl_way_at, times.joinToString(", ") { TransitFormat.clock(it) }, first.from.name)
        }
    }

    /** Whole minutes, to the nearest; none less than 0. */
    fun minutes(ms: Long): Int = ((ms + 30_000) / 60_000).toInt().coerceAtLeast(0)

    /** Whole minutes to go, as TransitFormat.relative counts them, so every countdown on screen agrees. */
    fun countdown(ms: Long): Int = (ms / 60_000).toInt().coerceAtLeast(0)

    /** "Leave in 4 min", "Leave now", "Leave at 17:05". */
    fun leave(context: Context, start: Long, now: Long): String = when {
        start - now < LEAVE_NOW_MS -> context.getString(R.string.pl_leave_now_short)
        else -> TransitFormat.relative(context, start, now)?.let { context.getString(R.string.pl_leave_in, it) }
            ?: context.getString(R.string.pl_leave_at, TransitFormat.clockWithDay(start, now))
    }

    /** "37 min · 1 change · 650 m on foot". */
    fun summary(context: Context, itinerary: Itinerary): String = listOfNotNull(
        duration(context, itinerary.end - itinerary.start),
        changes(context, itinerary),
        walk(context, itinerary),
    ).joinToString(" · ")

    /** "direct", "1 change"; null without rides. */
    fun changes(context: Context, itinerary: Itinerary): String? = when {
        itinerary.rides.isEmpty() -> null
        itinerary.transfers == 0 -> context.getString(R.string.pl_direct)
        else -> context.resources.getQuantityString(R.plurals.pl_changes, itinerary.transfers, itinerary.transfers)
    }

    /** "650 m on foot"; null if it's next to nothing. */
    fun walk(context: Context, itinerary: Itinerary): String? =
        itinerary.walkMeters.takeIf { it >= MIN_WALK_M }?.let { context.getString(R.string.pl_walk_m, roundMeters(it)) }

    /** How long [walk] takes: "1 min" at least, as "0 min" reads like there's nothing to walk. */
    fun walk(context: Context, walk: Leg): String = duration(context, maxOf(walk.duration, 60_000L))

    /** "37 min", "1 h 5 min". */
    fun duration(context: Context, ms: Long): String {
        val minutes = ((ms + 30_000) / 60_000).toInt().coerceAtLeast(0)
        return if (minutes < 60) context.getString(dev.maksim.companion.timetable.R.string.tt_in_min, minutes)
        else context.getString(dev.maksim.companion.timetable.R.string.tt_in_h_min, minutes / 60, minutes % 60)
    }

    /** "Bus 23 from Vabaduse väljak at 15:07". */
    fun ride(context: Context, leg: Leg): String {
        val ride = leg.ride ?: return ""
        return context.getString(
            R.string.pl_ride_from, context.getString(Mode.of(ride.mode).label), ride.route, leg.from.name,
            TransitFormat.clock(leg.departure),
        )
    }

    /** "Bus 23". */
    fun vehicle(context: Context, ride: Ride): String = "${context.getString(Mode.of(ride.mode).label)} ${ride.route}"

    /** The one thing to look out for, if there's one: a ride gone or swapped, a connection missed or tight. */
    fun warning(context: Context, itinerary: Itinerary, now: Long): String? {
        itinerary.rides.firstOrNull { it.ride?.gone == true }?.let {
            return context.getString(R.string.pl_gone, vehicle(context, it.ride!!), it.from.name)
        }
        itinerary.rides.firstOrNull { it.ride?.replaced == true }?.let {
            return context.getString(R.string.pl_replaced, vehicle(context, it.ride!!))
        }
        val changes = itinerary.changes
        changes.firstOrNull { it.margin < 0 }?.let { return context.getString(R.string.pl_change_missed, it.at.name) }
        changes.firstOrNull { it.margin < TIGHT_MS }?.let {
            return context.getString(R.string.pl_change_tight, duration(context, it.margin), it.at.name)
        }
        val firstRide = itinerary.rides.firstOrNull() ?: return null
        if (itinerary.start < now - LEAVE_NOW_MS) return context.getString(R.string.pl_late_start, vehicle(context, firstRide.ride!!))
        return null
    }

    /**
     * Walks and rides one after the other into [group]: the walk's minutes, the vehicle's number in its color; with
     * [lines], each ride's lines as one ("27 / 33"). A walk with [walkLabel] says that instead of its minutes.
     */
    fun chain(group: ViewGroup, itinerary: Itinerary, lines: List<List<String>>? = null, walkLabel: String? = null) {
        val inflater = LayoutInflater.from(group.context)
        group.removeAllViews()
        // Not the few steps to a stop across the street.
        val legs = itinerary.legs.filter { !it.isWalk || it.duration >= MIN_WALK_MS || it.distance >= MIN_WALK_M * 2 }
            .ifEmpty { itinerary.legs }
        var ride = 0
        for ((i, leg) in legs.withIndex()) {
            if (i > 0) (inflater.inflate(R.layout.pl_item_chain_walk, group, false) as TextView).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.pl_ic_chevron, 0, 0, 0)
                group.addView(this)
            }
            val vehicle = leg.ride
            if (vehicle == null) {
                (inflater.inflate(R.layout.pl_item_chain_walk, group, false) as TextView).apply {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.pl_ic_walk, 0, 0, 0)
                    text = walkLabel ?: maxOf(1L, (leg.duration + 30_000) / 60_000).toString()
                    contentDescription = context.getString(R.string.pl_walk, walk(context, leg))
                    group.addView(this)
                }
            } else {
                val mode = Mode.of(vehicle.mode)
                val names = lines?.getOrNull(ride)?.takeIf { it.isNotEmpty() } ?: listOf(vehicle.route)
                ride++
                (inflater.inflate(R.layout.pl_item_chain_ride, group, false) as TextView).apply {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(mode.icon, 0, 0, 0)
                    text = names.joinToString(" / ")
                    backgroundTintList = ColorStateList.valueOf(mode.color)
                    contentDescription = "${context.getString(mode.label)} ${names.joinToString(", ")}"
                    group.addView(this)
                }
            }
        }
    }

    /** "+2" or "−1", for how late or early; null when on time. */
    fun delay(call: Call): String? {
        val minutes = call.delayMinutes
        return when {
            !call.isLive || minutes == 0 -> null
            minutes > 0 -> "+$minutes"
            else -> "−${abs(minutes)}"
        }
    }

    /** "450 m", or "1.7 km" from a kilometer on. */
    fun distance(context: Context, meters: Double): String =
        if (meters < 1000) context.getString(R.string.pl_walk_distance, roundMeters(meters))
        else context.getString(R.string.pl_distance_km, String.format(java.util.Locale.getDefault(), "%.1f", meters / 1000))

    /** To the nearest 10 m: walking distances are a guess. */
    fun roundMeters(meters: Double): Int = ((meters + 5) / 10).toInt() * 10

    /** Less than this from now, it's "Leave now". */
    private const val LEAVE_NOW_MS = 60_000L

    /** A change with less time than this is worth a warning. */
    const val TIGHT_MS = 2 * 60_000L

    /** Walks shorter than this aren't worth mentioning in the summary, or (twice as far) in the chain. */
    private const val MIN_WALK_M = 50.0
    private const val MIN_WALK_MS = 60_000L

    /** Departures listed for a way. */
    private const val MAX_DEPARTURES = 3

    /** Departures further off than this are given as clock times. */
    private const val SOON_MS = 90 * 60_000L
}
