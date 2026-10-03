package dev.maksim.companion.planner

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.maksim.companion.planner.TripProgress.Kind
import dev.maksim.companion.timetable.Mode
import dev.maksim.companion.timetable.TransitFormat
import kotlin.math.abs
import dev.maksim.companion.timetable.R as TtR

/**
 * What a trip being taken says, and where: an ongoing notification with the next step and a countdown to it, an
 * alert for what needs doing now, and the short form for OsmAnd's widget ([Texts.widget]).
 */
object TripNotifications {

    private const val CHANNEL = "trip"
    private const val ALERTS_CHANNEL = "trip_alerts"

    /** Next to the service's (1) and the timetables' "open" one (2). */
    private const val ONGOING_ID = 3
    private const val ALERT_ID = 4
    private const val ALERT_TIMEOUT_MS = 10 * 60_000L
    private const val PROGRESS_MAX = 100

    /** The next step: [title] and [text] for the notification and the Trips tab, [widget] and [where] for OsmAnd's. */
    class Texts(val title: String, val text: String, val widget: String, val where: String)

    /** With [walk], OsmAnd's ETA where it's walking you now ([OsmAndTrip.walkTarget]) is in it. */
    fun texts(context: Context, trip: ActiveTrip, progress: TripProgress.Progress, now: Long, walk: OsmAndTrip.Navigation? = null): Texts {
        val legs = trip.itinerary.legs
        val leg = legs[progress.leg]
        val soon = TransitFormat.relative(context, progress.until, now) ?: TransitFormat.clockWithDay(progress.until, now)
        val osmand = walk?.let { osmandText(context, it, leg.takeIf { !it.isWalk }, now) }
        val destination = trip.destination.name
        return when (progress.kind) {
            Kind.LEAVE -> {
                val title = if (progress.until - now < 60_000) context.getString(R.string.pl_leave_now_short)
                else context.getString(R.string.pl_leave_in, soon)
                val ride = leg.ride
                val text = if (ride == null) {
                    context.getString(R.string.pl_step_walk, destination)
                } else {
                    val walk = legs.subList(0, progress.leg).sumOf { it.duration }
                    val catch = context.getString(R.string.pl_step_catch, ItineraryViews.vehicle(context, ride), TransitFormat.clock(leg.departure))
                    if (walk > 0) context.getString(R.string.pl_step_walk_to_catch, ItineraryViews.duration(context, walk), leg.from.name, catch)
                    else context.getString(R.string.pl_step_catch_at, catch, leg.from.name)
                }
                Texts(title, listOfNotNull(text, osmand).joinToString(" · "), context.getString(R.string.pl_widget_leave, soon), if (ride == null) destination else leg.from.name)
            }
            Kind.TO_STOP -> {
                val ride = leg.ride!!
                val live = if (leg.from.isLive) liveText(context, leg.from) else null
                Texts(
                    context.getString(R.string.pl_step_wait, ItineraryViews.vehicle(context, ride), ride.headsign, TransitFormat.clock(leg.departure)),
                    listOfNotNull(context.getString(R.string.pl_step_wait_text, leg.from.name, soon), live, osmand).joinToString(" · "),
                    context.getString(R.string.pl_widget_ride, ride.route, soon),
                    leg.from.name,
                )
            }
            Kind.RIDE -> {
                val next = legs.drop(progress.leg + 1)
                val nextRide = next.firstOrNull { !it.isWalk }
                val then = when {
                    nextRide != null -> context.getString(
                        R.string.pl_step_then_ride, ItineraryViews.vehicle(context, nextRide.ride!!), nextRide.from.name,
                        TransitFormat.clock(nextRide.departure),
                    )
                    next.isNotEmpty() -> context.getString(R.string.pl_step_then_walk, ItineraryViews.walk(context, next.first()))
                    else -> null
                }
                val stops = context.resources.getQuantityString(R.plurals.pl_stops_left, progress.stopsLeft, progress.stopsLeft)
                Texts(
                    context.getString(R.string.pl_step_ride, leg.to.name, TransitFormat.clock(leg.arrival)),
                    listOfNotNull(stops, then).joinToString(" · "),
                    context.getString(R.string.pl_widget_off, soon),
                    leg.to.name,
                )
            }
            Kind.WALK_THERE -> {
                // OsmAnd's ETA, if it's walking you there.
                val there = walk?.arrival ?: progress.until
                Texts(
                    context.getString(R.string.pl_step_walk, destination),
                    listOfNotNull(context.getString(R.string.pl_arrive, TransitFormat.clock(there)).replaceFirstChar { it.titlecase() }, osmand)
                        .joinToString(" · "),
                    context.getString(R.string.pl_widget_walk, TransitFormat.relative(context, there, now) ?: TransitFormat.clockWithDay(there, now)),
                    destination,
                )
            }
            Kind.ARRIVED -> Texts(context.getString(R.string.pl_step_arrived, destination), "", "✓", destination)
        }
    }

    /** "OsmAnd: there in 6 min"; or, walking to [ride]'s stop too slowly for it, by how much it'll be missed. */
    private fun osmandText(context: Context, walk: OsmAndTrip.Navigation, ride: Leg?, now: Long): String {
        val late = ride?.let { walk.arrival - it.departure }?.takeIf { it > 0 }
        return if (late != null) {
            context.getString(R.string.pl_eta_miss, ItineraryViews.vehicle(context, ride.ride!!), ItineraryViews.duration(context, late))
        } else {
            context.getString(R.string.pl_osmand_there_in, TransitFormat.relative(context, walk.arrival, now) ?: TransitFormat.clock(walk.arrival))
        }
    }

    /** "live, 2 min late". */
    private fun liveText(context: Context, call: Call): String {
        val minutes = call.delayMinutes
        val live = context.getString(TtR.string.tt_live)
        return when {
            minutes > 0 -> "$live, ${context.getString(TtR.string.tt_late, minutes)}"
            minutes < 0 -> "$live, ${context.getString(TtR.string.tt_early, -minutes)}"
            else -> live
        }
    }

    /**
     * The trip's notification, counting down to [TripProgress.Progress.until]; [walk] as for [texts], [walking] as for
     * [TripSteps.fraction].
     */
    fun ongoing(
        context: Context,
        trip: ActiveTrip,
        progress: TripProgress.Progress,
        now: Long,
        walk: OsmAndTrip.Navigation? = null,
        walking: Pair<Int, Float>? = null,
    ) {
        val texts = texts(context, trip, progress, now, walk)
        // On the last walk, there when OsmAnd says.
        val end = walk?.takeIf { progress.kind == Kind.WALK_THERE }?.arrival ?: trip.itinerary.end
        val ride = trip.itinerary.legs[progress.leg].ride
        val builder = NotificationCompat.Builder(context, channel(context))
            .setSmallIcon(dev.maksim.companion.core.R.drawable.ic_notification)
            .setContentTitle(texts.title)
            .setContentText(texts.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texts.text))
            .setSubText(context.getString(R.string.pl_trip_arrive, TransitFormat.clock(end), trip.destination.name))
            .setColor(ride?.let { Mode.of(it.mode).color } ?: context.getColor(TtR.color.tt_osm_accent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(open(context))
            .addAction(0, context.getString(R.string.pl_trip_new_way), action(context, TripActionReceiver.ACTION_REPLAN))
            .addAction(0, context.getString(R.string.pl_trip_stop), action(context, TripActionReceiver.ACTION_STOP))
        if (progress.kind != Kind.ARRIVED) {
            builder.setWhen(if (progress.kind == Kind.WALK_THERE) end else progress.until).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            // How far along the whole way it is; on foot, by how far it's been walked.
            builder.setProgress(PROGRESS_MAX, (TripSteps.fraction(trip.itinerary, now, walking) * PROGRESS_MAX).toInt(), false)
        }
        notify(context, ONGOING_ID, builder)
    }

    /** An alert that sounds, for what needs doing now; it takes the place of the one before. */
    fun alert(context: Context, title: String, text: String) {
        val builder = NotificationCompat.Builder(context, alertsChannel(context))
            .setSmallIcon(dev.maksim.companion.core.R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setTimeoutAfter(ALERT_TIMEOUT_MS)
            .setContentIntent(open(context))
        notify(context, ALERT_ID, builder)
    }

    /** Title and text of [alert] about [trip]. */
    fun alertTexts(context: Context, trip: ActiveTrip, alert: TripProgress.Alert, now: Long): Pair<String, String> {
        val itinerary = trip.itinerary
        val leg = itinerary.legs[alert.leg]
        val ride = leg.ride
        return when (alert) {
            is TripProgress.Alert.Leave -> context.getString(R.string.pl_alert_leave_title) to
                if (ride == null) context.getString(R.string.pl_step_walk, trip.destination.name)
                else context.getString(
                    R.string.pl_alert_leave_text, ItineraryViews.vehicle(context, ride), ride.headsign, leg.from.name,
                    TransitFormat.clock(leg.departure),
                )
            is TripProgress.Alert.GetOff -> context.getString(R.string.pl_alert_get_off_title) to
                context.getString(R.string.pl_alert_get_off_text, leg.to.name, TransitFormat.clock(leg.arrival))
            is TripProgress.Alert.Delay -> {
                val vehicle = ride?.let { ItineraryViews.vehicle(context, it) }.orEmpty()
                val title = when {
                    alert.minutes > 0 -> context.getString(R.string.pl_alert_late_title, vehicle, alert.minutes)
                    alert.minutes < 0 -> context.getString(R.string.pl_alert_early_title, vehicle, abs(alert.minutes))
                    else -> context.getString(R.string.pl_alert_on_time_title, vehicle)
                }
                val leaves = context.getString(R.string.pl_alert_leaves_at, leg.from.name, TransitFormat.clock(leg.departure))
                title to if (now < itinerary.start) {
                    "$leaves · ${context.getString(R.string.pl_leave_at, TransitFormat.clock(itinerary.start))}"
                } else {
                    leaves
                }
            }
        }
    }

    /** "New way: Bus 5 from Kaja at 15:40", for a trip planned again. */
    fun replannedText(context: Context, itinerary: Itinerary): String {
        val first = itinerary.rides.firstOrNull() ?: return context.getString(R.string.pl_walk_only)
        return ItineraryViews.ride(context, first)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(ONGOING_ID)
        NotificationManagerCompat.from(context).cancel(ALERT_ID)
    }

    // Checked first thing: lint doesn't see a check that only applies from Android 13 on.
    @SuppressLint("MissingPermission")
    private fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        // Android 13+ asks; the trip is followed all the same, without.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (_: SecurityException) {
            // Turned off meanwhile.
        }
    }

    /** The trip's live screen, from the notification or OsmAnd's widget. */
    fun open(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, LiveTripActivity.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(context, TripActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun channel(context: Context): String {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.pl_trip_channel), NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return CHANNEL
    }

    private fun alertsChannel(context: Context): String {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                ALERTS_CHANNEL, context.getString(R.string.pl_trip_alerts_channel), NotificationManager.IMPORTANCE_HIGH,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return ALERTS_CHANNEL
    }
}
