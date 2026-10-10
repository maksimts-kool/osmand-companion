package dev.maksim.companion.planner

/** What a search found: a [place] to plan from or to, and what kind of place it is. */
data class Found(
    val place: Place,
    val kind: Kind,
    /** A stop's vehicle, as [dev.maksim.companion.timetable.Mode.of] takes it. */
    val mode: String? = null,
    /** A stop's routes, in [dev.maksim.companion.timetable.RouteOrder]. */
    val lines: List<String> = emptyList(),
) {
    /** The search's sections, in their order, as in Moovit: cities first, then stops, then addresses. */
    enum class Section { TOWNS, STOPS, ADDRESSES }

    enum class Kind(val section: Section) {
        CITY(Section.TOWNS),
        TOWN(Section.TOWNS),
        VILLAGE(Section.TOWNS),

        /** A city's district or quarter (Lasnamäe, Kalamaja); its [Place.detail] is the city. */
        DISTRICT(Section.TOWNS),
        STOP(Section.STOPS),
        ADDRESS(Section.ADDRESSES),
    }
}

/** About the same point, within [meters]. */
internal fun Place.isNear(other: Place, meters: Double): Boolean =
    dev.maksim.companion.timetable.distanceMeters(lat, lon, other.lat, other.lon) < meters
