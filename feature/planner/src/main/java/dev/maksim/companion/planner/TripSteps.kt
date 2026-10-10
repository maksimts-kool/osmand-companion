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

    /**
     * Which of [steps] (of [itinerary]) it's at, at [now], going by the times as they're expected now and the places
     * [reached] on foot: at the stop, it's boarding, however early. [located]: OsmAnd knows where you are, so until
     * the stop's reached it's walking there, however late; without, the clock says when the walk's done.
     */
    fun current(itinerary: Itinerary, steps: List<Step>, now: Long, reached: Map<String, Long> = emptyMap(), located: Boolean = false): Int {
        val progress = TripProgress.at(itinerary, now, reached)
        val legs = itinerary.legs
        val step = when (progress.kind) {
            TripProgress.Kind.LEAVE -> return 0
            TripProgress.Kind.TO_STOP -> {
                val before = legs.getOrNull(progress.leg - 1)
                val atStop = TripProgress.stopKey(legs[progress.leg].from) in reached
                if (before != null && before.isWalk && !atStop && (located || now < before.arrival)) Step(Kind.WALK, progress.leg - 1)
                else Step(Kind.BOARD, progress.leg)
            }
            TripProgress.Kind.RIDE -> Step(Kind.RIDE, progress.leg)
            TripProgress.Kind.WALK_THERE -> Step(Kind.WALK_THERE, legs.lastIndex)
            TripProgress.Kind.ARRIVED -> Step(Kind.ARRIVED, legs.lastIndex)
        }
        return steps.indexOf(step).coerceAtLeast(0)
    }

    /**
     * How far through the trip it is at [now], 0 to 1. While walking leg [walking] (its index and how far along it
     * is, from where OsmAnd has you), by that instead of the clock: walking early or late is as far as it's got.
     */
    fun fraction(itinerary: Itinerary, now: Long, walking: Pair<Int, Float>? = null): Float {
        val span = itinerary.end - itinerary.start
        if (span <= 0) return 1f
        if (walking != null) {
            val leg = itinerary.legs[walking.first]
            val done = leg.departure - itinerary.start + walking.second * leg.duration
            return (done.toFloat() / span).coerceIn(0f, 1f)
        }
        return ((now - itinerary.start).toFloat() / span).coerceIn(0f, 1f)
    }
}
