package dev.maksim.companion.planner

import dev.maksim.companion.timetable.LatLon
import dev.maksim.companion.timetable.distanceMeters
import kotlin.math.roundToInt

/**
 * A trip being taken, by where OsmAnd has you: how far along a walk it is ([walked]), and when it has got to the stop
 * it walks to, or the end ([update]), which [TripProgress] then goes by, whatever the time.
 */
object TripPosition {

    /** This close to a stop, or the end, is there: a GPS fix is off by about as much. */
    const val REACHED_M = 40.0

    /** Walks that are further than this as the crow flies are taken as the planner has them, not longer. */
    private const val MAX_DETOUR = 2.0

    /** How far along a walk: [fraction] 0 to 1, and [metersLeft]. */
    class Walked(val fraction: Float, val metersLeft: Int)

    /**
     * How far along [leg] (a walk) it is from [here]: by OsmAnd's [navigation] there if it's navigating (its distance
     * left is kept up in the background, where [here] isn't), else the way left as the crow flies, made as much longer
     * as the planner's walk is; null without either.
     */
    fun walked(leg: Leg, here: LatLon?, navigation: OsmAndTrip.Navigation?): Walked? {
        val straight = distanceMeters(leg.from.lat, leg.from.lon, leg.to.lat, leg.to.lon)
        val total = maxOf(leg.distance, straight, 1.0)
        val detour = (total / maxOf(straight, 1.0)).coerceIn(1.0, MAX_DETOUR)
        val left = navigation?.meters?.toDouble()
            ?: here?.let { distanceMeters(it.lat, it.lon, leg.to.lat, leg.to.lon) * detour }
            ?: return null
        return Walked((1 - left / total).coerceIn(0.0, 1.0).toFloat(), left.roundToInt())
    }

    /**
     * The walk being walked at [progress], by its index: to the next ride's stop (unless it's been [reached]), or to
     * the end. Null on a ride, at a stop, or there.
     */
    fun walkingLeg(itinerary: Itinerary, progress: TripProgress.Progress, reached: Map<String, Long>): Int? {
        val legs = itinerary.legs
        val leg = legs.getOrNull(progress.leg) ?: return null
        return when (progress.kind) {
            TripProgress.Kind.LEAVE, TripProgress.Kind.TO_STOP -> when {
                leg.isWalk -> progress.leg
                TripProgress.stopKey(leg.from) in reached -> null
                legs.getOrNull(progress.leg - 1)?.isWalk == true -> progress.leg - 1
                else -> null
            }
            TripProgress.Kind.WALK_THERE -> legs.lastIndex.takeIf { legs[it].isWalk }
            else -> null
        }
    }

    /**
     * How far along the walk at [progress] it is ([walkingLeg], [walked]), for [TripSteps.fraction]; at a stop got to
     * early, the walk there is done, whatever the clock says.
     */
    fun walking(trip: ActiveTrip, progress: TripProgress.Progress, here: LatLon?, navigation: OsmAndTrip.Navigation?): Pair<Int, Float>? {
        val index = walkingLeg(trip.itinerary, progress, trip.reached) ?: return walkedTo(trip, progress)
        return walked(trip.itinerary.legs[index], here, navigation)?.let { index to it.fraction }
    }

    /** At the stop of the next ride, got to on foot: the walk there, done. */
    fun walkedTo(trip: ActiveTrip, progress: TripProgress.Progress): Pair<Int, Float>? {
        val legs = trip.itinerary.legs
        val leg = legs.getOrNull(progress.leg) ?: return null
        if (progress.kind != TripProgress.Kind.TO_STOP || leg.isWalk || TripProgress.stopKey(leg.from) !in trip.reached) return null
        return (progress.leg - 1).takeIf { legs.getOrNull(it)?.isWalk == true }?.let { it to 1f }
    }

    /** OsmAnd's route there gone with no more than this left to go: it got you there, or as good as. */
    const val ROUTE_GONE_M = 200

    /**
     * Whether OsmAnd got you there between asking [before] (its navigation there then) and [now]: it was navigating
     * there, and now isn't, its route finished, or gone with little left to go. In the background OsmAnd clears its
     * destination on arriving as on being cancelled, and where it has you isn't kept up, so that's all there is to go by.
     */
    fun osmandArrived(before: OsmAndTrip.Navigation?, now: OsmAndTrip.Position?): Boolean =
        before != null && now != null && now.navigation == null && (now.routeDone || before.meters <= ROUTE_GONE_M)

    /**
     * [trip] with the place it walks to now (the next ride's stop, or the end) marked as reached at [now], if [here]
     * is within [REACHED_M] of it, OsmAnd's [navigation] there has no more than that to go, or OsmAnd [arrived] there
     * (it finished its route, which it was navigating a moment ago: [arrived]). [trip] itself if not.
     */
    fun update(trip: ActiveTrip, here: LatLon?, navigation: OsmAndTrip.Navigation?, now: Long, arrived: Boolean = false): ActiveTrip {
        val itinerary = trip.itinerary
        val target = OsmAndTrip.walkTarget(itinerary, TripProgress.at(itinerary, now, trip.reached)) ?: return trip
        val last = itinerary.legs.last()
        val key = if (last.isWalk && target === last.to) TripProgress.END else TripProgress.stopKey(target)
        if (key in trip.reached) return trip
        val there = arrived || (here != null && distanceMeters(here.lat, here.lon, target.lat, target.lon) <= REACHED_M) ||
            (navigation != null && navigation.meters <= REACHED_M)
        return if (there) trip.copy(reached = trip.reached + (key to now)) else trip
    }
}
