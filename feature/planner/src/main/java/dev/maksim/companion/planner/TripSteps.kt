package dev.maksim.companion.planner

/**
 * A trip being taken as the live screen pages through it, one thing to do at a time: walk to a stop, wait for and
 * board a ride, ride it to where it's got off, walk to the end, be there. Each ride is two steps, as what's to look
 * at differs: before, when it comes and which to take; on it, the stops to go.
 */
object TripSteps {

    enum class Kind { WALK, BOARD, RIDE, WALK_THERE, ARRIVED }

    /** [kind] of step about leg [leg] (the last leg for [Kind.ARRIVED]). */
    data class Step(val kind: Kind, val leg: Int)

    fun of(itinerary: Itinerary): List<Step> {
        val legs = itinerary.legs
        val steps = ArrayList<Step>()
        for ((i, leg) in legs.withIndex()) {
            when {
                !leg.isWalk -> {
                    steps += Step(Kind.BOARD, i)
                    steps += Step(Kind.RIDE, i)
                }
                i == legs.lastIndex -> steps += Step(Kind.WALK_THERE, i)
                else -> steps += Step(Kind.WALK, i)
            }
        }
        steps += Step(Kind.ARRIVED, legs.lastIndex)
        return steps
    }

    /** Which of [steps] (of [itinerary]) it's at, at [now], going by the times as they're expected now. */
    fun current(itinerary: Itinerary, steps: List<Step>, now: Long): Int {
        val progress = TripProgress.at(itinerary, now)
        val legs = itinerary.legs
        val step = when (progress.kind) {
            TripProgress.Kind.LEAVE -> return 0
            TripProgress.Kind.TO_STOP -> {
                val before = legs.getOrNull(progress.leg - 1)
                if (before != null && before.isWalk && now < before.arrival) Step(Kind.WALK, progress.leg - 1)
                else Step(Kind.BOARD, progress.leg)
            }
            TripProgress.Kind.RIDE -> Step(Kind.RIDE, progress.leg)
            TripProgress.Kind.WALK_THERE -> Step(Kind.WALK_THERE, legs.lastIndex)
            TripProgress.Kind.ARRIVED -> Step(Kind.ARRIVED, legs.lastIndex)
        }
        return steps.indexOf(step).coerceAtLeast(0)
    }

    /** How far through the trip it is at [now], 0 to 1. */
    fun fraction(itinerary: Itinerary, now: Long): Float {
        val span = itinerary.end - itinerary.start
        if (span <= 0) return 1f
        return ((now - itinerary.start).toFloat() / span).coerceIn(0f, 1f)
    }
}
