package dev.maksim.companion.timetable

import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Which of this app's screens came up for which stop, and when, so [TimetableFeature] can tell whether Android let it
 * open one over OsmAnd. It asks "has it come up since I asked", not "is it on screen now": a screen that opened and
 * was left again quickly (a time tapped in the Full day sheet, the screen turned off) did open.
 *
 * A screen that comes up after the feature gave up on it (a slow start) takes back the notification sent instead.
 */
internal object OpenedScreens {

    enum class Screen { STOP, DAY_SHEET, NEXT_SHEET }

    /** The notification that opens a screen Android didn't let us open directly; one at a time. */
    const val NOTIFICATION_ID = 2

    private val resumedAt = ConcurrentHashMap<Pair<Screen, String>, Long>()

    /** What [NOTIFICATION_ID] is showing now, if anything. */
    @Volatile
    private var notified: Pair<Screen, String>? = null

    /** For [resumedSince]. */
    fun now(): Long = SystemClock.elapsedRealtime()

    /** From the screen's onResume. */
    fun resumed(context: Context, screen: Screen, stopId: String) {
        val key = screen to stopId
        resumedAt[key] = now()
        if (notified == key) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            notified = null
        }
    }

    fun resumedSince(screen: Screen, stopId: String, time: Long): Boolean =
        (resumedAt[screen to stopId] ?: Long.MIN_VALUE) >= time

    /** The notification now stands in for this screen. */
    fun notified(screen: Screen, stopId: String) {
        notified = screen to stopId
    }
}
