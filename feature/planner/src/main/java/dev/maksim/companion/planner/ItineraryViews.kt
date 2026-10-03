package dev.maksim.companion.planner

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import dev.maksim.companion.planner.databinding.PlItemItineraryBinding
import dev.maksim.companion.timetable.Mode
import dev.maksim.companion.timetable.Rows
import dev.maksim.companion.timetable.TransitFormat
import kotlin.math.abs

/** How itineraries read on screen: the Trips tab's cards, and the words for their steps. */
object ItineraryViews {

    /** A card for [itinerary] at the end of [parent], at [now]; [onClick] opens it. */
    fun card(parent: ViewGroup, itinerary: Itinerary, now: Long, onClick: () -> Unit) {
        val context = parent.context
        val card = PlItemItineraryBinding.inflate(LayoutInflater.from(context), parent, true)
        val live = context.getColor(dev.maksim.companion.timetable.R.color.tt_live)
        val firstRide = itinerary.rides.firstOrNull()
        card.leave.text = leave(context, itinerary.start, now)
        val leavesLive = firstRide?.from?.isLive == true
        card.leave.setTextColor(if (leavesLive) live else MaterialColors.getColor(card.leave, com.google.android.material.R.attr.colorOnSurface))
        Rows.liveMark(card.live, leavesLive, live)
        card.times.text = "${TransitFormat.clock(itinerary.start)} – ${TransitFormat.clock(itinerary.end)}"
        card.summary.text = summary(context, itinerary)
        chain(card.chain, itinerary)
        card.detail.text = firstRide?.let { ride(context, it) } ?: context.getString(R.string.pl_walk_only)
        card.detail.setTextColor(
            if (leavesLive) live else MaterialColors.getColor(card.detail, com.google.android.material.R.attr.colorOnSurfaceVariant),
        )
        val warning = warning(context, itinerary, now)
        card.warning.isVisible = warning != null
        card.warning.text = warning
        card.root.setOnClickListener { onClick() }
    }

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

    /** Walks and rides one after the other: the walk's minutes, the vehicle's number in its color. */
    private fun chain(group: ViewGroup, itinerary: Itinerary) {
        val inflater = LayoutInflater.from(group.context)
        group.removeAllViews()
        // Not the few steps to a stop across the street.
        val legs = itinerary.legs.filter { !it.isWalk || it.duration >= MIN_WALK_MS || it.distance >= MIN_WALK_M * 2 }
            .ifEmpty { itinerary.legs }
        for ((i, leg) in legs.withIndex()) {
            if (i > 0) (inflater.inflate(R.layout.pl_item_chain_walk, group, false) as TextView).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.pl_ic_chevron, 0, 0, 0)
                group.addView(this)
            }
            val ride = leg.ride
            if (ride == null) {
                (inflater.inflate(R.layout.pl_item_chain_walk, group, false) as TextView).apply {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.pl_ic_walk, 0, 0, 0)
                    text = maxOf(1L, (leg.duration + 30_000) / 60_000).toString()
                    contentDescription = context.getString(R.string.pl_walk, walk(context, leg))
                    group.addView(this)
                }
            } else {
                val mode = Mode.of(ride.mode)
                (inflater.inflate(R.layout.pl_item_chain_ride, group, false) as TextView).apply {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(mode.icon, 0, 0, 0)
                    text = ride.route
                    backgroundTintList = ColorStateList.valueOf(mode.color)
                    contentDescription = vehicle(context, ride)
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

    /** To the nearest 10 m: walking distances are a guess. */
    fun roundMeters(meters: Double): Int = ((meters + 5) / 10).toInt() * 10

    /** Less than this from now, it's "Leave now". */
    private const val LEAVE_NOW_MS = 60_000L

    /** A change with less time than this is worth a warning. */
    const val TIGHT_MS = 2 * 60_000L

    /** Walks shorter than this aren't worth mentioning in the summary, or (twice as far) in the chain. */
    private const val MIN_WALK_M = 50.0
    private const val MIN_WALK_MS = 60_000L
}
