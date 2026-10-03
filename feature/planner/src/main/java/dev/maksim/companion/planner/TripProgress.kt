package dev.maksim.companion.planner

import kotlin.math.abs

/**
 * Where a trip being taken is, going by its times as they're expected now: what to do next and until when ([at]);
 * which ride can't be caught any more ([missed]); and what's worth an alert ([alerts]). Nothing here knows where the
 * phone is: a ride whose time has come is taken as being ridden.
 */
object TripProgress {

    enum class Kind {
        /** Not yet left: [Progress.until] is when to. */
        LEAVE,

        /** Walking to, or waiting at, the stop of the next ride ([Progress.leg]), which leaves at [Progress.until]. */
        TO_STOP,

        /** On a ride, which gets to where it's got off at [Progress.until]. */
        RIDE,

        /** The last walk, there by [Progress.until]. */
        WALK_THERE,

        ARRIVED,
    }

    /** [kind] of step, the leg it's about (the next ride, the one ridden, or the last walk), and until when. */
    data class Progress(val kind: Kind, val leg: Int, val until: Long, val stopsLeft: Int = 0)

    fun at(itinerary: Itinerary, now: Long): Progress {
        val legs = itinerary.legs
        if (now < itinerary.start) return Progress(Kind.LEAVE, legs.indexOfFirst { !it.isWalk }.coerceAtLeast(0), itinerary.start)
        for ((i, leg) in legs.withIndex()) {
            if (!leg.isWalk && leg.departure <= now && now < leg.arrival) {
                return Progress(Kind.RIDE, i, leg.arrival, leg.stops.count { it.expected > now } + 1)
            }
        }
        legs.indices.firstOrNull { !legs[it].isWalk && legs[it].departure > now }?.let {
            return Progress(Kind.TO_STOP, it, legs[it].departure)
        }
        return Progress(if (now < itinerary.end) Kind.WALK_THERE else Kind.ARRIVED, legs.lastIndex, itinerary.end)
    }

    /**
     * The leg of the next ride if it can't be caught any more: the live feed says it has already left, or the ride
     * before (being ridden) gets in too late for it, the walk between counted. Null if it can.
     */
    fun missed(itinerary: Itinerary, now: Long): Int? {
        val legs = itinerary.legs
        var previous = -1
        for ((i, leg) in legs.withIndex()) {
            if (leg.isWalk) continue
            if (leg.departure <= now) {
                previous = i
                continue
            }
            if (leg.ride?.gone == true) return i
            if (previous >= 0) {
                val walk = legs.subList(previous + 1, i).sumOf { it.duration }
                if (legs[previous].arrival + walk > leg.departure) return i
            }
            return null
        }
        return null
    }

    /** Something to tell straight away, about the leg [leg]. */
    sealed class Alert(val leg: Int) {
        class Leave(leg: Int) : Alert(leg)
        class GetOff(leg: Int) : Alert(leg)

        /** The next ride's delay is now [minutes] (less than 0: early). */
        class Delay(leg: Int, val minutes: Int) : Alert(leg)
    }

    /**
     * The alerts due for [trip] at [progress] at [now], and the trip noting them, so each is given once: leave when
     * it's [LEAVE_SOON_MS] to leaving; get off [GET_OFF_MS] before getting there; and the next ride's delay each time
     * it has changed by [DELAY_STEP_MIN] minutes from what was told last (the first seen is only noted).
     */
    fun alerts(trip: ActiveTrip, progress: Progress, now: Long): Pair<List<Alert>, ActiveTrip> {
        val alerts = ArrayList<Alert>()
        var alerted = trip.alerted
        var delays = trip.delays
        val legs = trip.itinerary.legs
        val leg = legs.getOrNull(progress.leg) ?: return alerts to trip
        when (progress.kind) {
            Kind.LEAVE -> if (progress.until - now <= LEAVE_SOON_MS && LEAVE !in alerted) {
                alerts += Alert.Leave(progress.leg)
                alerted = alerted + LEAVE
            }
            Kind.RIDE -> {
                val key = GET_OFF + rideKey(leg)
                if (progress.until - now <= GET_OFF_MS && key !in alerted) {
                    alerts += Alert.GetOff(progress.leg)
                    alerted = alerted + key
                }
            }
            else -> {}
        }
        if ((progress.kind == Kind.LEAVE || progress.kind == Kind.TO_STOP) && !leg.isWalk && leg.from.isLive) {
            val key = rideKey(leg)
            val minutes = leg.from.delayMinutes
            val told = delays[key]
            if (told == null) {
                delays = delays + (key to minutes)
            } else if (abs(minutes - told) >= DELAY_STEP_MIN) {
                alerts += Alert.Delay(progress.leg, minutes)
                delays = delays + (key to minutes)
            }
        }
        return alerts to trip.copy(alerted = alerted, delays = delays)
    }

    /** A ride, the same however its times change: its line, where it's got on, and its timetabled minute there. */
    fun rideKey(leg: Leg): String = "${leg.ride?.route}@${leg.from.code ?: leg.from.name}@${leg.from.scheduled / 60_000}"

    /** For [alerts]: the key of the leave alert, which a new itinerary gives again. */
    const val LEAVE = "leave"
    private const val GET_OFF = "off:"

    /** Ticks are 20 s apart: early enough that one falls before it's time. */
    const val LEAVE_SOON_MS = 90_000L
    const val GET_OFF_MS = 2 * 60_000L
    const val DELAY_STEP_MIN = 2
}
