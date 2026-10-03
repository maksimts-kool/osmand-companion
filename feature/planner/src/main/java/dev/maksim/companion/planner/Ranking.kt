package dev.maksim.companion.planner

/**
 * Puts the itineraries from every planner in one list: the same one found twice is shown once (the one with more of
 * its rides live), one that's no better than another is left out, and the rest go from the one that gets there
 * first; for "arrive by", from the one that leaves last. A change counts as [CHANGE_COST_MS] more on the way, so a
 * third bus that gets there two minutes sooner isn't worth showing.
 */
object Ranking {

    const val MAX = 8

    fun rank(itineraries: List<Itinerary>, arriveBy: Long? = null, max: Int = MAX): List<Itinerary> {
        val unique = itineraries.groupBy { it.signature }.values.map { same ->
            same.maxWith(compareBy<Itinerary> { it.rides.count { ride -> ride.from.isLive } }.thenBy { -it.source.ordinal })
        }
        val fitting = if (arriveBy == null) unique else unique.filter { it.end <= arriveBy + ARRIVE_BY_SLACK_MS }
        val order = if (arriveBy == null) {
            compareBy<Itinerary> { it.end }.thenBy { it.transfers }.thenByDescending { it.start }.thenBy { it.walkMeters }
        } else {
            compareByDescending<Itinerary> { it.start }.thenBy { it.transfers }.thenBy { it.end }.thenBy { it.walkMeters }
        }
        return fitting.filter { b -> fitting.none { a -> a !== b && dominates(a, b) } }.sortedWith(order).take(max)
    }

    /**
     * [a] leaves no sooner and gets there no later, its changes counted in, without much more walking; and it's
     * better at one of those.
     */
    private fun dominates(a: Itinerary, b: Itinerary): Boolean {
        val scoreA = a.end + a.transfers * CHANGE_COST_MS
        val scoreB = b.end + b.transfers * CHANGE_COST_MS
        return a.start >= b.start && scoreA <= scoreB && a.walkMeters <= b.walkMeters + WALK_SLACK_M &&
            (a.start > b.start || scoreA < scoreB || a.walkMeters < b.walkMeters)
    }

    private const val ARRIVE_BY_SLACK_MS = 60_000L
    private const val WALK_SLACK_M = 150.0
    private const val CHANGE_COST_MS = 4 * 60_000L
}
