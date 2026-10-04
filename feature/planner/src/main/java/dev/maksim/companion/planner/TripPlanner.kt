package dev.maksim.companion.planner

import dev.maksim.companion.core.AppLog
import dev.maksim.companion.core.Parallel
import dev.maksim.companion.timetable.PeatusClient
import java.io.IOException
import java.io.Serializable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Plans a trip on live times, which is what Google Maps and the national planners don't: they go by the timetable.
 * One planner finds the ways, peatus.ee's (the national one, all of Estonia). Leaving now, it's also asked from
 * [LOOK_BACK_MS] ago, so a bus that's late enough to catch, which left already by the timetable, is among what it
 * finds. The itineraries then get their live times ([LiveRetimer]: Tallinn's city lines and Harjumaa's county buses),
 * any ride that can't be caught any more is swapped for the next of its line, and they go in one list ([Ranking]).
 *
 * Blocking: call it off the main thread.
 */
class TripPlanner {

    private val feeds = LiveFeeds()
    private val peatus = OtpPlanner(Source.PEATUS)
    private val retimer = LiveRetimer(feeds, PeatusClient())

    /** From [from] to [to], leaving at [time] (epoch ms; null: now), or arriving by it. */
    data class Request(val from: Place, val to: Place, val time: Long?, val arriveBy: Boolean) : Serializable

    /**
     * What [plan] found at [at]; [failed] are the planners that didn't answer. [plannedAt] is when the planners were
     * asked, which [again] doesn't do; [found] is what they said, before the live times, and [request] what for.
     */
    class Result(
        val itineraries: List<Itinerary>,
        val at: Long,
        val failed: Set<Source>,
        val plannedAt: Long = at,
        internal val found: List<Itinerary> = emptyList(),
        internal val request: Request? = null,
    )

    fun plan(request: Request): Result {
        val now = System.currentTimeMillis()
        val time = request.time ?: now
        val (from, to, _, arriveBy) = request
        val jobs = ArrayList<Pair<Source, Callable<List<Itinerary>>>>()
        jobs += peatus.source to Callable { peatus.plan(from, to, time, arriveBy, COUNT) }
        if (request.time == null) {
            jobs += peatus.source to Callable { peatus.plan(from, to, now - LOOK_BACK_MS, false, LOOK_BACK_COUNT) }
        }

        val found = ArrayList<Itinerary>()
        val answered = HashSet<Source>()
        var error: Throwable? = null
        val futures: List<Pair<Source, Future<List<Itinerary>>>> = jobs.map { (source, job) -> source to Parallel.submit { job.call() } }
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)
            for ((source, future) in futures) {
                try {
                    found += future.get(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                    answered += source
                } catch (e: ExecutionException) {
                    AppLog.log("Planner: $source failed: ${e.cause?.message ?: e.cause}")
                    error = error ?: e.cause
                } catch (e: TimeoutException) {
                    AppLog.log("Planner: $source took too long")
                    future.cancel(true)
                }
            }
        } finally {
            // Those still going (this thread was interrupted): nobody's waiting for them.
            futures.forEach { (_, future) -> future.cancel(true) }
        }
        val failed = jobs.map { it.first }.toSet() - answered
        if (answered.isEmpty()) {
            throw (error as? IOException) ?: IOException(error?.message ?: "no planner answered", error)
        }

        return Result(settle(found, request, now), now, failed, now, found, request)
    }

    /**
     * [previous] at the live times now, without asking the planners again: the same ways, with the rides that can't
     * be caught any more swapped for the next of their line, or left out. A lot less than [plan], for keeping what's
     * on screen fresh; but no new ways come up, so [plan] again now and then.
     */
    fun again(previous: Result): Result {
        val request = previous.request ?: return previous
        val now = System.currentTimeMillis()
        return Result(settle(previous.found, request, now), now, previous.failed, previous.plannedAt, previous.found, request)
    }

    /** [found] at the live times of [now], as they can be taken, best first. */
    private fun settle(found: List<Itinerary>, request: Request, now: Long): List<Itinerary> {
        val time = request.time ?: now
        val earliest = if (request.arriveBy) now else time
        val settled = retimer.retime(found, now).mapNotNull { retimer.settle(it, earliest, now) }
        return Ranking.rank(settled, if (request.arriveBy) time else null)
    }

    /**
     * [itinerary] at its vehicles' times now, for its screen while it's open. Nothing's swapped: the one on screen is
     * the one being taken, so a ride that can't be caught any more is shown as such.
     */
    fun refresh(itinerary: Itinerary): Itinerary = refresh(listOf(itinerary)).first()

    /** [itineraries] at their vehicles' times now, all in one go: an itinerary and the other departures it could take. */
    fun refresh(itineraries: List<Itinerary>): List<Itinerary> = retimer.retime(itineraries, System.currentTimeMillis())

    private companion object {
        /** Itineraries asked for; and from a while ago, for late buses. */
        const val COUNT = 8
        const val LOOK_BACK_COUNT = 3
        const val LOOK_BACK_MS = 15 * 60 * 1000L

        /** Both questions together. */
        const val TIMEOUT_MS = 30_000L
    }
}
