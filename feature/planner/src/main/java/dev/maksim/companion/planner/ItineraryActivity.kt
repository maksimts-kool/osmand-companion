package dev.maksim.companion.planner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import dev.maksim.companion.core.Analytics
import dev.maksim.companion.core.canPostNotifications
import dev.maksim.companion.core.companion
import dev.maksim.companion.core.padForSystemBars
import dev.maksim.companion.planner.databinding.PlActivityItineraryBinding
import dev.maksim.companion.planner.databinding.PlItemChangeBinding
import dev.maksim.companion.planner.databinding.PlItemWalkBinding
import dev.maksim.companion.timetable.Mode
import dev.maksim.companion.timetable.OsmAndRoute
import dev.maksim.companion.timetable.PeatusClient
import dev.maksim.companion.timetable.Rows
import dev.maksim.companion.timetable.TransitFormat
import dev.maksim.companion.timetable.TripActivity
import dev.maksim.companion.timetable.TripLineView
import dev.maksim.companion.timetable.databinding.TtItemTripStopBinding
import java.util.concurrent.Executors
import kotlin.math.abs
import dev.maksim.companion.timetable.R as TtR

/**
 * One way there, step by step: each walk, and each ride from where it's got on to where it's got off (the stops in
 * between a tap away), with its live times, refreshed every [REFRESH_MS]; and the time to change between rides. A
 * ride opens its trip's screen. The header's button shows it all on OsmAnd's map; at the bottom, walk to the first
 * stop with OsmAnd, and take it as a trip ([TripFeature]). The trip being taken ([activeIntent]) is shown as the
 * trip has it, new way and all.
 */
class ItineraryActivity : AppCompatActivity() {

    private lateinit var binding: PlActivityItineraryBinding
    private lateinit var itinerary: Itinerary
    private lateinit var origin: String
    private lateinit var destination: String

    /** Whether this is the trip being taken, so it shows what [TripStore] has. */
    private var following = false
    private val planner by lazy { TripPlanner(this) }
    private val peatus = PeatusClient()
    private val background = Executors.newSingleThreadExecutor()

    /** The legs whose stops in between are shown, by index. */
    private val unfolded = HashSet<Int>()

    private val tripChanged = Runnable { if (!isDestroyed) follow() }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startTrip() }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            binding.root.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trip = TripStore.current(this)
        if (intent.getBooleanExtra(EXTRA_ACTIVE, false)) {
            trip ?: return finish()
            itinerary = trip.itinerary
            origin = trip.origin
            destination = trip.destination.name
        } else {
            itinerary = IntentCompat.getSerializableExtra(intent, EXTRA_ITINERARY, Itinerary::class.java) ?: return finish()
            origin = intent.getStringExtra(EXTRA_ORIGIN).orEmpty()
            destination = intent.getStringExtra(EXTRA_DESTINATION).orEmpty()
        }
        following = trip != null && (intent.getBooleanExtra(EXTRA_ACTIVE, false) || trip.itinerary.signature == itinerary.signature)
        binding = PlActivityItineraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        binding.header.back.setOnClickListener { finish() }
        with(binding.header.action) {
            isVisible = true
            setIconResource(TtR.drawable.tt_ic_map)
            contentDescription = getString(R.string.pl_show_in_osmand)
            TooltipCompat.setTooltipText(this, contentDescription)
            setOnClickListener { showInOsmand() }
        }
        binding.walk.setOnClickListener { walkThere() }
        binding.trip.setOnClickListener { if (following) TripStore.stop(this, arrived = false) else askToStartTrip() }
        TripStore.addListener(tripChanged)
        follow()
    }

    override fun onStart() {
        super.onStart()
        binding.root.postDelayed(tick, REFRESH_MS)
        val context = applicationContext
        background.execute { OsmAndRoute.clearLeftover(context, context.companion.osmand) }
    }

    override fun onStop() {
        binding.root.removeCallbacks(tick)
        super.onStop()
    }

    override fun onDestroy() {
        TripStore.removeListener(tripChanged)
        background.shutdownNow()
        super.onDestroy()
    }

    /** The trip being taken as [TripStore] has it now, if this is it; else as it is. */
    private fun follow() {
        if (following) {
            val trip = TripStore.current(this)
            if (trip == null) following = false else itinerary = trip.itinerary
        }
        binding.trip.setText(if (following) R.string.pl_trip_stop else R.string.pl_trip_start)
        binding.walk.isVisible = itinerary.legs.firstOrNull()?.isWalk == true && itinerary.rides.isNotEmpty() &&
            System.currentTimeMillis() < itinerary.rides.first().departure
        render()
    }

    /** Notifications are what the trip is followed in: Android 13+ asks first. It's taken without them all the same. */
    private fun askToStartTrip() {
        if (canPostNotifications()) startTrip() else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startTrip() {
        val end = itinerary.legs.last().to
        TripStore.start(this, itinerary, origin, Place(destination, end.lat, end.lon))
        following = true
        Toast.makeText(this, R.string.pl_trip_started, Toast.LENGTH_LONG).show()
        follow()
    }

    private fun refresh() {
        // The trip feature keeps the trip being taken fresh.
        if (following) return follow()
        val current = itinerary
        if (current.rides.none { it.ride?.feed != LiveFeed.NONE }) return render()
        binding.progress.isVisible = true
        background.execute {
            val fresh = runCatching { planner.refresh(current) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.progress.isVisible = false
                if (fresh != null && itinerary === current) itinerary = fresh
                render()
            }
        }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val itinerary = itinerary
        val firstRide = itinerary.rides.firstOrNull()?.ride
        val facts = buildList {
            add(TtR.drawable.tt_ic_timer to ItineraryViews.duration(this@ItineraryActivity, itinerary.end - itinerary.start))
            ItineraryViews.changes(this@ItineraryActivity, itinerary)?.let { add(TtR.drawable.tt_ic_route to it) }
            ItineraryViews.walk(this@ItineraryActivity, itinerary)?.let { add(R.drawable.pl_ic_walk to it) }
        }
        Rows.header(
            binding.header,
            firstRide?.let { Mode.of(it.mode) } ?: Mode.OTHER,
            null,
            getString(R.string.pl_title, destination),
            getString(R.string.pl_subtitle_from, origin),
            facts,
            live = if (itinerary.isLive) true else null,
        )
        val content = binding.content
        content.removeAllViews()
        ItineraryViews.warning(this, itinerary, now)?.let { warning ->
            val late = getColor(TtR.color.tt_late)
            content.addView(
                TextView(this).apply {
                    text = warning
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setTextColor(late)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(TtR.drawable.tt_ic_warning, 0, 0, 0)
                    compoundDrawableTintList = ColorStateList.valueOf(late)
                    compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
                    val padding = (12 * resources.displayMetrics.density).toInt()
                    setPadding(padding, padding, padding, padding / 2)
                },
            )
        }
        val changes = itinerary.changes
        var ride = 0
        for ((i, leg) in itinerary.legs.withIndex()) {
            if (leg.isWalk) {
                walk(leg, last = i == itinerary.legs.lastIndex)
            } else {
                ride(i, leg, now)
                changes.getOrNull(ride)?.let(::change)
                ride++
            }
        }
        arrival(itinerary.end)
    }

    private fun walk(leg: Leg, last: Boolean) {
        val row = PlItemWalkBinding.inflate(layoutInflater, binding.content, true)
        val minutes = ItineraryViews.walk(this, leg)
        row.time.text = TransitFormat.clock(leg.departure)
        row.title.text = if (last) getString(R.string.pl_walk, minutes) else getString(R.string.pl_walk_to, minutes, leg.to.name)
        row.subtitle.isVisible = leg.distance > 0
        row.subtitle.text = getString(R.string.pl_walk_distance, ItineraryViews.roundMeters(leg.distance))
    }

    /** Where the trip ends: the place, when you're there. */
    private fun arrival(time: Long) {
        val row = PlItemWalkBinding.inflate(layoutInflater, binding.content, true)
        row.time.text = TransitFormat.clock(time)
        row.title.text = destination
        row.title.setTypeface(row.title.typeface, Typeface.BOLD)
        row.subtitle.isVisible = false
        row.line.isVisible = false
        row.icon.setImageResource(R.drawable.pl_ic_place)
        row.icon.imageTintList = ColorStateList.valueOf(getColor(TtR.color.tt_offline_strike))
    }

    /** A ride: where it's got on, the stops in between (folded), and where it's got off. */
    private fun ride(index: Int, leg: Leg, now: Long) {
        val ride = leg.ride ?: return
        val mode = Mode.of(ride.mode)
        val board = stop(leg.from, mode, TripLineView.Stop.FIRST, now)
        board.status.isVisible = true
        board.status.text = "${ItineraryViews.vehicle(this, ride)} → ${ride.headsign}"
        board.status.setTextColor(mode.color)
        board.name.setTypeface(board.name.typeface, Typeface.BOLD)
        board.root.setOnClickListener { openTrip(leg) }
        if (leg.stops.isNotEmpty()) {
            if (index in unfolded) {
                for (call in leg.stops) stop(call, mode, TripLineView.Stop.MIDDLE, now, eta = false).root.setOnClickListener { fold(index) }
            } else {
                stop(null, mode, TripLineView.Stop.MIDDLE, now, eta = false).run {
                    name.text = resources.getQuantityString(R.plurals.pl_stops_between, leg.stops.size, leg.stops.size)
                    name.setTextColor(MaterialColors.getColor(name, com.google.android.material.R.attr.colorOnSurfaceVariant))
                    root.contentDescription = getString(R.string.pl_show_stops)
                    root.setOnClickListener { fold(index) }
                }
            }
        }
        stop(leg.to, mode, TripLineView.Stop.LAST, now).run {
            name.setTypeface(name.typeface, Typeface.BOLD)
            root.setOnClickListener { openTrip(leg) }
        }
    }

    private fun fold(index: Int) {
        if (!unfolded.remove(index)) unfolded += index
        render()
    }

    /**
     * A stop of a ride in the trip screen's look: its time (green when live), its piece of the line, its name, and how
     * soon with how late; [call] null for the folded stops in between.
     */
    private fun stop(call: Call?, mode: Mode, kind: TripLineView.Stop, now: Long, eta: Boolean = true): TtItemTripStopBinding {
        val row = TtItemTripStopBinding.inflate(layoutInflater, binding.content, true)
        val live = getColor(TtR.color.tt_live)
        row.line.setMode(mode)
        row.line.kind = kind
        row.line.passed = call != null && call.expected < now - PASSED_GRACE_MS
        row.time.text = call?.let { TransitFormat.clock(it.expected) }
        if (call?.isLive == true) row.time.setTextColor(live)
        row.name.text = call?.name
        val soon = call?.takeIf { eta && it.expected >= now - PASSED_GRACE_MS }?.let { TransitFormat.relative(this, it.expected, now) }
        row.eta.isVisible = soon != null
        row.etaText.text = soon
        val color = if (call?.isLive == true) live else mode.color
        row.etaMain.backgroundTintList = ColorStateList.valueOf(color).withAlpha(PILL_ALPHA)
        if (call?.isLive == true) row.etaText.setTextColor(live)
        Rows.liveMark(row.live, call?.isLive == true && soon != null, live)
        val delay = call?.let { ItineraryViews.delay(it) }
        row.delay.isVisible = soon != null && delay != null
        row.etaMain.setBackgroundResource(if (row.delay.isVisible) TtR.drawable.tt_pill_start_bg else TtR.drawable.tt_pill_bg)
        row.etaMain.backgroundTintList = ColorStateList.valueOf(color).withAlpha(PILL_ALPHA)
        if (row.delay.isVisible && call != null) {
            val delayColor = getColor(if (call.delayMinutes > 0) TtR.color.tt_late else TtR.color.tt_early)
            row.delay.text = delay
            row.delay.setTextColor(delayColor)
            row.delay.backgroundTintList = ColorStateList.valueOf(delayColor).withAlpha(PILL_ALPHA)
            row.eta.contentDescription = listOf(
                soon,
                getString(if (call.delayMinutes > 0) TtR.string.tt_late else TtR.string.tt_early, abs(call.delayMinutes)),
            ).joinToString(", ")
        }
        return row
    }

    /** The time to change after a ride: orange when it's tight, red when it's missed. */
    private fun change(change: Itinerary.Change) {
        val row = PlItemChangeBinding.inflate(layoutInflater, binding.content, true)
        val margin = change.margin
        val color = when {
            margin < 0 -> getColor(TtR.color.tt_offline_strike)
            margin < ItineraryViews.TIGHT_MS -> getColor(TtR.color.tt_late)
            else -> MaterialColors.getColor(row.margin, com.google.android.material.R.attr.colorOnSurfaceVariant)
        }
        row.margin.text = if (margin < 0) getString(R.string.pl_change_margin_missed, ItineraryViews.duration(this, -margin))
        else getString(R.string.pl_change_margin, ItineraryViews.duration(this, margin))
        row.margin.setTextColor(color)
        row.margin.backgroundTintList = ColorStateList.valueOf(color).withAlpha(PILL_ALPHA)
    }

    /**
     * The ride's trip on its own screen. Rides found by this app's own search have no peatus.ee id, so it's looked up
     * among the stop's departures by the line and the time.
     */
    private fun openTrip(leg: Leg) {
        val ride = leg.ride ?: return
        if (ride.tripId != null && ride.serviceDate != null) {
            startActivity(TripActivity.intent(this, ride.tripId, OtpPlanner.midnight(ride.serviceDate) / 1000, leg.from.stopId))
            return
        }
        binding.progress.isVisible = true
        background.execute {
            val found = runCatching { findTrip(leg, ride) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.progress.isVisible = false
                if (found == null) {
                    Toast.makeText(this, R.string.pl_trip_unknown, Toast.LENGTH_LONG).show()
                } else {
                    startActivity(TripActivity.intent(this, found.tripId, found.serviceDay, found.stopId))
                }
            }
        }
    }

    private class FoundTrip(val tripId: String, val serviceDay: Long, val stopId: String)

    private fun findTrip(leg: Leg, ride: Ride): FoundTrip? {
        val stopId = leg.from.stopId ?: leg.from.code?.let { code ->
            peatus.nearbyStops(leg.from.lat, leg.from.lon, SAME_STOP_M, 20, 0).firstOrNull { it.code == code }?.id
        } ?: return null
        val stop = peatus.stopWithin(stopId, FIND_WINDOW_S) ?: return null
        val departure = stop.departures.firstOrNull {
            it.route.equals(ride.route, ignoreCase = true) &&
                abs((it.serviceDay + it.scheduled) * 1000 - leg.from.scheduled) <= MATCH_MS
        } ?: return null
        return FoundTrip(departure.tripId, departure.serviceDay, stopId)
    }

    /** Draws the itinerary in OsmAnd with its card open, and switches there; it's gone once the card is closed. */
    private fun showInOsmand() {
        val osmand = companion.osmand
        if (osmand.osmandPackage == null && osmand.findInstalledOsmand() == null) {
            return Toast.makeText(this, TtR.string.tt_osmand_missing, Toast.LENGTH_LONG).show()
        }
        if (!osmand.checkAccess()) return Toast.makeText(this, TtR.string.tt_route_no_access, Toast.LENGTH_LONG).show()
        binding.progress.isVisible = true
        val itinerary = itinerary
        background.execute {
            val shown = OsmAndTrip.show(this, osmand, itinerary, destination)
            val launch = OsmAndTrip.launchIntent(this, osmand)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.progress.isVisible = false
                if (shown && launch != null) {
                    Analytics.signal("Planner.shownInOsmAnd")
                    startActivity(launch)
                } else {
                    Toast.makeText(this, TtR.string.tt_route_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** OsmAnd's walking navigation to the first stop: OsmAnd first, as it only takes it with its map up. */
    private fun walkThere() {
        val stop = itinerary.rides.firstOrNull()?.from ?: return
        val osmand = companion.osmand
        val launch = OsmAndTrip.launchIntent(this, osmand)
            ?: return Toast.makeText(this, TtR.string.tt_osmand_missing, Toast.LENGTH_LONG).show()
        if (!osmand.checkAccess()) return Toast.makeText(this, TtR.string.tt_route_no_access, Toast.LENGTH_LONG).show()
        Analytics.signal("Planner.walkInOsmAnd")
        startActivity(launch)
        background.execute {
            Thread.sleep(OSMAND_UP_MS)
            OsmAndTrip.walkTo(osmand, stop)
        }
    }

    companion object {
        private const val EXTRA_ITINERARY = "itinerary"
        private const val EXTRA_ACTIVE = "active"
        private const val EXTRA_ORIGIN = "origin"
        private const val EXTRA_DESTINATION = "destination"
        private const val REFRESH_MS = 30_000L

        /** A stop that was due this long ago has been passed. */
        private const val PASSED_GRACE_MS = 30_000L
        private const val PILL_ALPHA = 0x26

        /** How long OsmAnd takes to come up with its map. */
        private const val OSMAND_UP_MS = 1_500L

        private const val SAME_STOP_M = 80
        private const val FIND_WINDOW_S = 3 * 60 * 60
        private const val MATCH_MS = 90_000L

        /** The trip being taken, whatever its way is by now: from its notification, OsmAnd's widget, the Trips tab. */
        fun activeIntent(context: Context): Intent =
            Intent(context, ItineraryActivity::class.java).putExtra(EXTRA_ACTIVE, true)

        fun intent(context: Context, itinerary: Itinerary, origin: String, destination: String): Intent =
            Intent(context, ItineraryActivity::class.java)
                .putExtra(EXTRA_ITINERARY, itinerary)
                .putExtra(EXTRA_ORIGIN, origin)
                .putExtra(EXTRA_DESTINATION, destination)
    }
}
