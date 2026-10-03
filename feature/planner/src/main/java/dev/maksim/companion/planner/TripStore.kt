package dev.maksim.companion.planner

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.maksim.companion.core.Analytics
import dev.maksim.companion.core.AppLog
import dev.maksim.companion.core.CompanionService
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.concurrent.CopyOnWriteArraySet

/**
 * A trip being taken: the [itinerary] followed (swapped for a new one when it can't be caught any more), from
 * [origin] to [destination], and what [TripFeature] has already told about it, so it says each thing once.
 */
data class ActiveTrip(
    val itinerary: Itinerary,
    val origin: String,
    val destination: Place,
    val startedAt: Long,
    /** The alerts given, by [TripProgress.Alert.key]. */
    val alerted: Set<String> = emptySet(),
    /** Each ride's delay when it was last told, in minutes, by [TripProgress.rideKey]. */
    val delays: Map<String, Int> = emptyMap(),
    /** When it was last planned again; 0: never. */
    val replannedAt: Long = 0,
    /** Goes up each time the itinerary is swapped. */
    val version: Int = 0,
) : Serializable

/**
 * The trip being taken, if any ([ActiveTrip]), kept in the app's files so it carries on if Android kills the process
 * on the way. Starting and stopping it ([start], [stop]) starts and stops [TripFeature] through the service.
 */
object TripStore {

    private const val FILE = "trip.ser"

    @Volatile
    private var cached: ActiveTrip? = null

    @Volatile
    private var loaded = false

    private val listeners = CopyOnWriteArraySet<Runnable>()
    private val main = Handler(Looper.getMainLooper())

    fun current(context: Context): ActiveTrip? {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                cached = read(context)
                loaded = true
            }
        }
        return cached
    }

    /** Called on the main thread whenever the trip changes, starts or ends. */
    fun addListener(listener: Runnable) = listeners.add(listener)
    fun removeListener(listener: Runnable) = listeners.remove(listener)

    /** Follows [itinerary] from [origin] to [destination], in place of any trip before. Call from the foreground. */
    fun start(context: Context, itinerary: Itinerary, origin: String, destination: Place) {
        save(context, ActiveTrip(itinerary, origin, destination, System.currentTimeMillis()))
        Analytics.signal("Trip.started", mapOf("rides" to itinerary.rides.size.toString(), "live" to itinerary.isLive.toString()))
        AppLog.log("Trip: following the way to ${destination.name}")
        CompanionService.update(context)
    }

    /** Ends the trip; [arrived] if it's because it's done. Any thread. */
    fun stop(context: Context, arrived: Boolean) {
        if (current(context) == null) return
        synchronized(this) {
            cached = null
            loaded = true
            file(context).delete()
        }
        Analytics.signal("Trip.ended", mapOf("arrived" to arrived.toString()))
        AppLog.log(if (arrived) "Trip: arrived" else "Trip: stopped")
        notifyListeners()
        try {
            CompanionService.update(context)
        } catch (e: IllegalStateException) {
            // Android 12+ won't start the service from the background; only stopping it was needed here anyway.
            AppLog.log("Trip: couldn't update the service: ${e.message}")
        }
    }

    /** Keeps [trip] as the one being taken. Any thread. */
    fun save(context: Context, trip: ActiveTrip) {
        synchronized(this) {
            cached = trip
            loaded = true
            try {
                val file = file(context)
                val part = File(file.parentFile, "$FILE.part")
                ObjectOutputStream(part.outputStream().buffered()).use { it.writeObject(trip) }
                part.renameTo(file)
            } catch (e: java.io.IOException) {
                AppLog.log("Trip: couldn't save it: ${e.message}")
            }
        }
        notifyListeners()
    }

    private fun read(context: Context): ActiveTrip? {
        val file = file(context)
        if (!file.isFile) return null
        // One saved by another version of the app may not read; it's only lost.
        return runCatching { ObjectInputStream(file.inputStream().buffered()).use { it.readObject() as ActiveTrip } }
            .onFailure { file.delete() }.getOrNull()
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun notifyListeners() = main.post { listeners.forEach { it.run() } }
}
