package dev.maksim.companion.planner

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import dev.maksim.companion.core.Analytics
import dev.maksim.companion.core.AppLog
import dev.maksim.companion.core.BackgroundFeature
import dev.maksim.companion.core.OsmAndConnection
import dev.maksim.companion.planner.TripProgress.Kind
import dev.maksim.companion.timetable.Mode
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.mapwidget.AMapWidget
import net.osmand.aidlapi.mapwidget.AddMapWidgetParams
import net.osmand.aidlapi.mapwidget.RemoveMapWidgetParams
import net.osmand.aidlapi.mapwidget.UpdateMapWidgetParams

/**
 * Trip mode: follows a trip being taken ([TripStore]) on live times. Every [TICK_MS] it gets the rides' live times,
 * shows the next step with a countdown in a notification and in OsmAnd's "Trip (live)" widget, and alerts: leave
 * now, how late the next ride is when that changes, get off at the next stop. When a ride can't be caught any more
 * (it left early, or the one before gets in too late), it plans again from where the trip will be, and goes on with
 * the new way. A few minutes after getting there, it ends.
 *
 * Runs in [dev.maksim.companion.core.CompanionService] like the other features, but without OsmAnd too
 * ([runsWithoutOsmAnd]): on a bus, the phone is in a pocket.
 */
class TripFeature(private val context: Context, private val osmand: OsmAndConnection) : BackgroundFeature {

    private val planner by lazy { TripPlanner() }

    override val title: String
        get() = TripStore.current(context)?.let { context.getString(R.string.pl_trip_title, it.destination.name) }
            ?: context.getString(R.string.pl_trip_feature)

    override val isEnabled: Boolean get() = TripStore.current(context) != null

    override val runsWithoutOsmAnd: Boolean get() = true

    private var thread: HandlerThread? = null

    @Volatile
    private var handler: Handler? = null

    /** The OsmAnd the widget was added to; it forgets widgets when it restarts. Worker thread only. */
    private var widgetIn: IOsmAndAidlInterface? = null

    private val tick = object : Runnable {
        override fun run() {
            try {
                update()
            } catch (e: RuntimeException) {
                AppLog.log("Trip: update failed: $e")
            }
            handler?.postDelayed(this, TICK_MS)
        }
    }

    private val onAccessGranted = OsmAndConnection.AccessListener { handler?.post { widgetIn = null } }

    override fun start() {
        val thread = HandlerThread("Trip").also { it.start() }
        this.thread = thread
        handler = Handler(thread.looper).also { it.post(tick) }
        osmand.addAccessListener(onAccessGranted)
    }

    override fun stop() {
        osmand.removeAccessListener(onAccessGranted)
        handler?.run {
            removeCallbacks(tick)
            post {
                removeWidget()
                TripNotifications.cancel(context)
            }
        }
        handler = null
        thread?.quitSafely()
        thread = null
    }

    /** From the notification's "New way": plans again from where OsmAnd has you, now. */
    fun replanNow() {
        handler?.post {
            val trip = TripStore.current(context) ?: return@post
            val now = System.currentTimeMillis()
            finish(replan(trip, missed = null, now), now)
        }
    }

    private fun update() {
        val stored = TripStore.current(context) ?: return
        val now = System.currentTimeMillis()
        // The other departures too, while there's still a choice: the live screen lists them.
        val options = stored.options.takeIf { stored.itinerary.rides.firstOrNull()?.let { now < it.departure } == true }.orEmpty()
        val fresh = runCatching { planner.refresh(listOf(stored.itinerary) + options) }.getOrNull()
        val itinerary = fresh?.first() ?: stored.itinerary
        // Stopped, or planned again or another departure taken, while the live times came in.
        if (TripStore.current(context)?.version != stored.version) return
        var trip = stored.copy(itinerary = itinerary, options = fresh?.drop(1) ?: options)
        if (now > itinerary.end + DONE_AFTER_MS) return TripStore.stop(context, arrived = true)
        TripProgress.missed(itinerary, now)?.let { leg ->
            if (now - trip.replannedAt >= REPLAN_EVERY_MS) trip = replan(trip, leg, now)
        }
        finish(trip, now)
    }

    /** Gives [trip]'s alerts due at [now], keeps it, and shows its next step. */
    private fun finish(trip: ActiveTrip, now: Long) {
        val progress = TripProgress.at(trip.itinerary, now)
        val (alerts, noted) = TripProgress.alerts(trip, progress, now)
        for (alert in alerts) {
            val (title, text) = TripNotifications.alertTexts(context, noted, alert, now)
            TripNotifications.alert(context, title, text)
        }
        TripStore.save(context, noted)
        TripNotifications.ongoing(context, noted, progress, now)
        showWidget(noted, progress, now)
    }

    /**
     * Plans the rest of [trip] again, the ride at leg [missed] (if any) being out of reach: from where the ride before
     * gets off, at its time, if there was one; else from where OsmAnd has you, now. Tells what's next either way
     * (that there's no way, once per ride missed). The trip with the new itinerary, or as it was if there's none.
     */
    private fun replan(trip: ActiveTrip, missed: Int?, now: Long): ActiveTrip {
        val legs = trip.itinerary.legs
        val before = missed?.let { legs.subList(0, it).lastOrNull { leg -> !leg.isWalk && leg.departure <= now } }
        val (from, time) = if (before != null) {
            Place(before.to.name, before.to.lat, before.to.lon) to before.arrival.takeIf { it > now }
        } else {
            val here = OsmAndTrip.places(context, osmand)?.myLocation
            // Without OsmAnd's location: at the stop of the ride that was to be taken next.
            val stop = (missed?.let { legs[it] } ?: legs.firstOrNull { !it.isWalk && it.departure > now })?.from
            (here ?: stop?.let { Place(it.name, it.lat, it.lon) } ?: return trip.copy(replannedAt = now)) to null
        }
        val found = runCatching { planner.plan(TripPlanner.Request(from, trip.destination, time, arriveBy = false)) }
        val way = found.getOrNull()?.itineraries?.let(Way::group)?.firstOrNull()
        val best = way?.best
        Analytics.signal(
            "Trip.replanned",
            mapOf("reason" to if (missed == null) "asked" else if (legs[missed].ride?.gone == true) "gone" else "connection", "found" to (best != null).toString()),
        )
        if (best == null) {
            AppLog.log("Trip: no new way found (${found.exceptionOrNull()?.message ?: "none"})")
            val key = NO_WAY + (missed?.let { TripProgress.rideKey(legs[it]) } ?: now)
            if (key !in trip.alerted) {
                TripNotifications.alert(context, context.getString(R.string.pl_alert_no_way_title), context.getString(R.string.pl_alert_no_way_text))
            }
            return trip.copy(replannedAt = now, alerted = trip.alerted + key)
        }
        AppLog.log("Trip: planned again from ${from.name}")
        val title = context.getString(if (missed == null) R.string.pl_alert_new_way_title else R.string.pl_alert_replanned_title)
        TripNotifications.alert(context, title, TripNotifications.replannedText(context, best))
        return trip.copy(
            itinerary = best,
            replannedAt = now,
            version = trip.version + 1,
            options = way.options,
            // Leave again, for the new way.
            alerted = trip.alerted - TripProgress.LEAVE,
        )
    }

    /** OsmAnd's "Trip (live)" widget: "23 · 4 min" over where. Added again whenever OsmAnd has forgotten it. */
    private fun showWidget(trip: ActiveTrip, progress: TripProgress.Progress, now: Long) {
        val api = osmand.api ?: return
        if (!osmand.hasAccess) return
        val texts = TripNotifications.texts(context, trip, progress, now)
        val ride = trip.itinerary.legs[progress.leg].ride
        val icon = when {
            progress.kind == Kind.ARRIVED -> ICON_ARRIVED
            ride == null || progress.kind == Kind.WALK_THERE -> ICON_WALK
            else -> Mode.of(ride.mode).osmandIcon
        }
        val onClick = LiveTripActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val widget = AMapWidget(
            WIDGET_ID, ICON_WALK, context.getString(R.string.pl_widget_title), icon, icon, texts.widget, texts.where,
            WIDGET_ORDER, onClick,
        )
        if (widgetIn !== api) {
            osmand.call("addMapWidget") { it.addMapWidget(AddMapWidgetParams(widget)) }
            widgetIn = api
        } else {
            osmand.call("updateMapWidget") { it.updateMapWidget(UpdateMapWidgetParams(widget)) }
        }
    }

    private fun removeWidget() {
        if (widgetIn != null && osmand.hasAccess) {
            osmand.call("removeMapWidget") { it.removeMapWidget(RemoveMapWidgetParams(WIDGET_ID)) }
        }
        widgetIn = null
    }

    private companion object {
        const val TICK_MS = 20_000L

        /** Once there, the trip's screen and widget stay this long. */
        const val DONE_AFTER_MS = 3 * 60_000L

        /** Planned again at most this often, so a connection missed for good isn't asked about every tick. */
        const val REPLAN_EVERY_MS = 90_000L

        /** For [ActiveTrip.alerted]: no new way was found for this missed ride. */
        const val NO_WAY = "noway:"

        const val WIDGET_ID = "transit_trip"
        const val WIDGET_ORDER = 101
        const val ICON_WALK = "ic_action_pedestrian_dark"
        const val ICON_ARRIVED = "ic_action_flag"
    }
}
