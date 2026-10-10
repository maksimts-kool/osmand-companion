package dev.maksim.companion.planner

import java.io.Serializable

/**
 * Itineraries that go the same way at other times or on other lines: on and off at the same stops, so one is as good
 * as another but for when. Shown as one, "27 / 33 · in 4, 21, 39 min", and on its screen as departures to choose
 * from. [best] is the one the planner ranked highest; [options] are all of them, from the one that leaves first.
 */
class Way(val best: Itinerary, val options: List<Itinerary>) : Serializable {

    /** The lines of each ride, in the order they leave: e.g. ["27", "33"] for the first. */
    val lines: List<List<String>>
        get() = best.rides.indices.map { i -> options.mapNotNull { it.rides.getOrNull(i)?.ride?.route }.distinct() }

    /** When the first ride of each option leaves, from the first. */
    val departures: List<Long> get() = options.mapNotNull { it.rides.firstOrNull()?.departure }

    val isWalk: Boolean get() = best.rides.isEmpty()

    companion object {
        /**
         * [ranked] (best first) as ways, in the order of each's best; an itinerary with no rides is a way of its own.
         * The options of a way are the ones on and off at the same stops.
         */
        fun group(ranked: List<Itinerary>): List<Way> {
            val groups = LinkedHashMap<String, MutableList<Itinerary>>()
            for (itinerary in ranked) groups.getOrPut(key(itinerary)) { ArrayList() } += itinerary
            return groups.values.map { same -> Way(same.first(), same.sortedBy { it.rides.firstOrNull()?.departure ?: it.start }) }
        }

        /** Where each ride is got on and off. */
        fun key(itinerary: Itinerary): String =
            itinerary.rides.joinToString("|") { "${it.from.key}>${it.to.key}" }.ifEmpty { "walk" }
    }
}
