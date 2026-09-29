package dev.maksim.companion.timetable

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import dev.maksim.companion.core.AppLog
import dev.maksim.companion.core.BackgroundFeature
import dev.maksim.companion.core.OsmAndConnection
import net.osmand.aidlapi.IOsmAndAidlInterface
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Transit timetables in OsmAnd, Estonia only, from peatus.ee (see [OsmAndStopUi] for what shows up where).
 *
 * While on, it asks OsmAnd every few seconds where its map is. Only while the map is on screen and inside
 * Estonia, it loads the stops around the map center with their next departures, again once the map has moved
 * [REFETCH_DISTANCE_M] or after [REFRESH_MS], and pushes them into OsmAnd.
 */
class TimetableFeature(private val context: Context, private val osmand: OsmAndConnection) : BackgroundFeature {

    private val prefs = context.getSharedPreferences("timetable", Context.MODE_PRIVATE)
    private val peatus = PeatusClient()
    private val ui = OsmAndStopUi(context, osmand).also { it.onButton = ::onButton }

    override val title: String = context.getString(R.string.tt_feature_title)

    override var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** Stops around OsmAnd's map center, nearest first, as last loaded. For the stop list in this app. */
    @Volatile
    var nearbyStops: List<Stop> = emptyList()
        private set

    private var thread: HandlerThread? = null

    @Volatile
    private var handler: Handler? = null

    // Worker thread only.
    private var registeredWith: IOsmAndAidlInterface? = null
    private var fetchedAt = 0L
    private var fetchedLat = 0.0
    private var fetchedLon = 0.0
    private var retryAt = 0L
    private var failures = 0
    private var pinnedStopId: String? = null

    private val tick = object : Runnable {
        override fun run() {
            try {
                update()
            } catch (e: RuntimeException) {
                // e.g. a parcel from a newer OsmAnd we can't read; keep going rather than crash.
                AppLog.log("Timetables: update failed: $e")
            }
            handler?.postDelayed(this, TICK_MS)
        }
    }

    /** OsmAnd forgot our layer if it restarted, or never had it if we just got access. */
    private val onAccessGranted = OsmAndConnection.AccessListener {
        handler?.post {
            registeredWith = null
            fetchedAt = 0L
        }
    }

    override fun start() {
        val thread = HandlerThread("Timetables").also { it.start() }
        this.thread = thread
        handler = Handler(thread.looper).also { it.post(tick) }
        osmand.addAccessListener(onAccessGranted)
        AppLog.log("Timetables: showing Estonian stops on OsmAnd's map")
    }

    override fun stop() {
        osmand.removeAccessListener(onAccessGranted)
        handler?.run {
            removeCallbacks(tick)
            post {
                if (registeredWith != null && osmand.hasAccess) ui.unregister()
                registeredWith = null
            }
        }
        handler = null
        thread?.quitSafely()
        thread = null
        nearbyStops = emptyList()
        AppLog.log("Timetables: removed from OsmAnd")
    }

    private fun update() {
        val api = osmand.api
        if (api == null || !osmand.hasAccess) return
        if (registeredWith === api && ui.languageChanged()) {
            // OsmAnd keeps the names it was given; add everything again in its new language.
            ui.unregister()
            registeredWith = null
            fetchedAt = 0L
        }
        if (registeredWith !== api) {
            if (!ui.register()) return
            registeredWith = api
            nearbyStops = emptyList()
            AppLog.log("Timetables: added stop layer, menu buttons and widget to OsmAnd (${ui.locales.toLanguageTags()})")
        }
        OsmAndRoute.clearLeftover(context, osmand)
        val info = osmand.call("getAppInfo") { it.appInfo } ?: return
        // Nobody is looking: don't spend data or peatus.ee's capacity.
        if (!info.isMapVisible) return
        val center = info.mapLocation ?: return
        val now = System.currentTimeMillis()

        if (!Estonia.contains(center.latitude, center.longitude)) {
            if (nearbyStops.isNotEmpty()) {
                nearbyStops = emptyList()
                ui.showStops(emptyList(), now)
            }
            ui.updateWidget(null, now)
            return
        }

        val moved = distanceMeters(fetchedLat, fetchedLon, center.latitude, center.longitude) > REFETCH_DISTANCE_M
        if ((moved || now - fetchedAt > REFRESH_MS) && now >= retryAt) {
            try {
                nearbyStops = peatus.nearbyStops(center.latitude, center.longitude, RADIUS_M, MAX_STOPS, DEPARTURES)
                fetchedAt = now
                fetchedLat = center.latitude
                fetchedLon = center.longitude
                failures = 0
                ui.showStops(nearbyStops, now)
            } catch (e: IOException) {
                failures++
                retryAt = now + minOf(REFRESH_MS, TICK_MS shl minOf(failures, 5))
                if (failures == 1) AppLog.log("Timetables: couldn't load stops: ${e.message}")
            }
        }
        ui.updateWidget(widgetStop(center.latitude, center.longitude), now)
    }

    /**
     * For this app's stop screen: opens [stop]'s menu on OsmAnd's map, with its next departures, then calls
     * [then] on the worker thread with whether that worked; the caller brings OsmAnd to the front. False, and
     * nothing happens, while timetables aren't on in OsmAnd.
     */
    fun showInOsmand(stop: Stop, then: (shown: Boolean) -> Unit): Boolean {
        val handler = handler ?: return false
        handler.post {
            if (registeredWith == null || !osmand.hasAccess) return@post then(false)
            // One from the map has its next departures; the stop screen's may not.
            val known = nearbyStops.find { it.id == stop.id }
                ?: try {
                    peatus.stop(stop.id, DEPARTURES)
                } catch (e: IOException) {
                    null
                }
                ?: stop
            pinnedStopId = stop.id
            then(ui.showMenu(known, System.currentTimeMillis()))
        }
        return true
    }

    /** The stop you last pressed a button on while it's still loaded, else the one nearest the map center. */
    private fun widgetStop(lat: Double, lon: Double): Stop? =
        nearbyStops.find { it.id == pinnedStopId }
            ?: nearbyStops.filter { it.departures.isNotEmpty() }.minByOrNull { distanceMeters(lat, lon, it.lat, it.lon) }

    /** From OsmAnd, on a binder thread: a button in a stop's context menu. */
    private fun onButton(button: Int, stopId: String) {
        handler?.post {
            pinnedStopId = stopId
            val now = System.currentTimeMillis()
            try {
                when (button) {
                    OsmAndStopUi.BUTTON_SHOW_IN_APP -> showInApp(stopId)
                    OsmAndStopUi.BUTTON_FULL_DAY -> showFullDay(stopId, now)
                    OsmAndStopUi.BUTTON_DEPARTURES -> {
                        val stop = peatus.stop(stopId, MENU_DEPARTURES) ?: return@post
                        nearbyStops = nearbyStops.map { if (it.id == stopId) stop.copy(departures = stop.departures.take(DEPARTURES)) else it }
                        ui.showInMenu(stop, ui.departureDetails(stop, now))
                    }
                }
            } catch (e: IOException) {
                val stop = nearbyStops.find { it.id == stopId } ?: return@post
                ui.showInMenu(stop, listOf(ui.strings.getString(R.string.tt_load_failed, e.message)))
            }
            nearbyStops.find { it.id == stopId }?.let { ui.updateWidget(it, now) }
        }
    }

    /** Opens the stop's timetable here. */
    private fun showInApp(stopId: String) {
        val name = nearbyStops.find { it.id == stopId }?.name
        open(StopActivity.intent(context, stopId, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), name) {
            StopActivity.resumedStopId == stopId
        }
    }

    /** Opens the rest of today at the stop in a sheet over OsmAnd's map, dark when OsmAnd is. */
    private fun showFullDay(stopId: String, now: Long) {
        val stop = nearbyStops.find { it.id == stopId } ?: peatus.stop(stopId, 0) ?: return
        DaySheetActivity.locales = ui.locales
        open(DaySheetActivity.intent(context, stop, ui.isNight(stop.lat, stop.lon, now)), stop.name) {
            DaySheetActivity.resumedStopId == stopId
        }
    }

    /**
     * OsmAnd is in front, so Android may refuse to start our activity from the background (10+); if it hasn't
     * shown up shortly ([isOpen] still false), a notification opens it instead.
     */
    private fun open(intent: Intent, name: String?, isOpen: () -> Boolean) {
        context.startActivity(intent)
        handler?.postDelayed({ if (!isOpen()) notifyOpen(intent, name) }, OPEN_CHECK_MS)
    }

    private fun notifyOpen(intent: Intent, name: String?) {
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) {
            AppLog.log("Timetables: Android didn't let us open the stop from OsmAnd, and notifications are off")
            return
        }
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                OPEN_CHANNEL_ID, context.getString(R.string.tt_open_channel), NotificationManager.IMPORTANCE_HIGH,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(context, OPEN_CHANNEL_ID)
            .setSmallIcon(dev.maksim.companion.core.R.drawable.ic_notification)
            .setContentTitle(name ?: context.getString(R.string.tt_feature_title))
            .setContentText(context.getString(R.string.tt_open_tap))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(OPEN_NOTIFICATION_TIMEOUT_MS)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        try {
            notifications.notify(OPEN_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            AppLog.log("Timetables: can't post notification: ${e.message}")
        }
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val OPEN_CHANNEL_ID = "timetable_open"
        const val OPEN_NOTIFICATION_ID = 2
        val OPEN_CHECK_MS = TimeUnit.SECONDS.toMillis(2)
        val OPEN_NOTIFICATION_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(1)
        val TICK_MS = TimeUnit.SECONDS.toMillis(4)
        val REFRESH_MS = TimeUnit.SECONDS.toMillis(60)

        /** About a phone screen at zoom 16, so stops are there before you pan to them. */
        const val RADIUS_M = 1200
        const val REFETCH_DISTANCE_M = 400.0
        const val MAX_STOPS = 150

        /** Departures per stop in the map's menus; more when you tap Next departures. */
        const val DEPARTURES = 5
        const val MENU_DEPARTURES = 10
    }
}
