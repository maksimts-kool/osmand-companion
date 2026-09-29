package dev.maksim.companion.timetable

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.maksim.companion.core.companion
import dev.maksim.companion.core.padForSystemBars
import dev.maksim.companion.timetable.databinding.TtActivityTripBinding
import dev.maksim.companion.timetable.databinding.TtItemTripStopBinding
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * One run of a route: every stop it calls at and when, the route's timetable seen from the vehicle. The header's
 * route button draws it on OsmAnd's map ([showRoute]), after warning if OsmAnd's own version of it is out of date.
 */
class TripActivity : AppCompatActivity() {

    private lateinit var binding: TtActivityTripBinding
    private val peatus = PeatusClient()
    private val osmCheck = OsmRouteCheck()
    private val background = Executors.newSingleThreadExecutor()
    private var trip: Trip? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = TtActivityTripBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        with(binding.header) {
            back.setOnClickListener { finish() }
            action.setIconResource(R.drawable.tt_ic_route)
            action.contentDescription = getString(R.string.tt_route_in_osmand)
            action.tooltipText = action.contentDescription
            action.setOnClickListener { showRoute() }
        }
        States.loading(binding.content, MaterialColors.getColor(binding.content, androidx.appcompat.R.attr.colorPrimary))
        load()
    }

    /** Moves the vehicle along while the screen is open; the times themselves don't change. */
    private val tick = object : Runnable {
        override fun run() {
            render(scrollToFrom = false)
            binding.root.postDelayed(this, TICK_MS)
        }
    }

    override fun onStart() {
        super.onStart()
        binding.root.postDelayed(tick, TICK_MS)
        background.execute { OsmAndRoute.clearLeftover(this, companion.osmand) }
    }

    override fun onStop() {
        binding.root.removeCallbacks(tick)
        super.onStop()
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        val tripId = intent.getStringExtra(EXTRA_TRIP_ID) ?: return finish()
        val serviceDay = intent.getLongExtra(EXTRA_SERVICE_DAY, 0L)
        // Noon of the service day is safely inside it, even on the days clocks change.
        val date = Estonia.format("yyyyMMdd", serviceDay * 1000 + TimeUnit.HOURS.toMillis(12))
        val unknown = getString(R.string.tt_trip_unknown)
        binding.progress.isVisible = true
        background.execute {
            val result = runCatching { peatus.trip(tripId, date) ?: throw IOException(unknown) }
            runOnUiThread { if (!isDestroyed) show(result) }
        }
    }

    private fun show(result: Result<Trip>) {
        binding.progress.isVisible = false
        trip = result.getOrElse {
            States.error(binding.content, getString(R.string.tt_load_failed, it.message)) {
                States.loading(binding.content, MaterialColors.getColor(binding.content, androidx.appcompat.R.attr.colorPrimary))
                load()
            }
            return
        }
        binding.header.action.isVisible = true
        binding.content.alpha = 0f
        binding.content.animate().alpha(1f).setDuration(FADE_MS).start()
        render(scrollToFrom = true)
    }

    /** Draws the trip as a line diagram, with the vehicle where the timetable says it is now. */
    private fun render(scrollToFrom: Boolean) {
        val trip = trip ?: return
        val mode = Mode.of(trip.mode)
        val color = ColorStateList.valueOf(mode.color)
        val now = System.currentTimeMillis()
        val times = trip.stops.map { it.time(trip.serviceDay) }
        showHeader(trip, mode, times)

        val content = binding.content
        content.removeAllViews()
        content.overlay.clear()
        val fromStopId = intent.getStringExtra(EXTRA_FROM_STOP_ID)
        // The vehicle is between the last stop it has passed and the next; -1 before it sets off.
        val last = times.indexOfLast { it <= now }
        val between = last >= 0 && last < times.lastIndex
        val progress = if (between) (now - times[last]).toFloat() / max(1L, times[last + 1] - times[last]) else 0f
        var fromRow: View? = null
        for ((i, tripStop) in trip.stops.withIndex()) {
            val time = times[i]
            val passed = i <= last
            val row = TtItemTripStopBinding.inflate(layoutInflater, content, true)
            row.time.text = TransitFormat.clock(time)
            row.name.text = tripStop.stop.name
            with(row.line) {
                setMode(mode)
                kind = when (i) {
                    0 -> TripLineView.Stop.FIRST
                    trip.stops.lastIndex -> TripLineView.Stop.LAST
                    else -> TripLineView.Stop.MIDDLE
                }
                this.passed = passed
                passedIn = if (passed) 1f else if (between && i == last + 1) progress * 2 - 1 else 0f
                passedOut = if (!passed) 0f else if (between && i == last) progress * 2 else 1f
            }
            // Where the vehicle is along this row, 0 at the top and 1 at the bottom. The halfway point between two
            // stops is the border between their rows.
            val vehicleAt = when {
                !between -> null
                i == last && progress < 0.5f -> 0.5f + progress
                i == last + 1 && progress >= 0.5f -> progress - 0.5f
                else -> null
            }
            vehicleAt?.let { at -> placeVehicle(row, at, mode) }

            val status = mutableListOf<String>()
            val delay = (tripStop.expected - tripStop.scheduled) / 60
            if (tripStop.isRealtime) status += getString(R.string.tt_live)
            if (delay > 0) status += getString(R.string.tt_late, delay)
            if (delay < 0) status += getString(R.string.tt_early, -delay)
            if (tripStop.stop.id == fromStopId) {
                status.add(0, getString(R.string.tt_your_stop))
                row.root.setBackgroundResource(R.drawable.tt_row_highlight_bg)
                row.root.backgroundTintList = color.withAlpha(0x26)
                row.line.emphasized = true
                row.name.setTypeface(row.name.typeface, Typeface.BOLD)
                row.time.setTypeface(row.time.typeface, Typeface.BOLD)
                row.status.setTextColor(if (delay > 0) LATE else mode.color)
                fromRow = row.root
            } else if (delay > 0) {
                row.status.setTextColor(LATE)
            }
            row.status.text = status.joinToString(" · ")
            row.status.isVisible = status.isNotEmpty()

            val soon = TransitFormat.relative(this, time, now)?.takeIf { time >= now }
            row.eta.text = soon
            row.eta.isVisible = soon != null
            row.eta.backgroundTintList = color.withAlpha(if (i == last + 1) 0x40 else 0x1A)
            if (passed) {
                row.time.alpha = PAST_ALPHA
                row.name.alpha = PAST_ALPHA
            }
            row.root.setOnClickListener {
                startActivity(StopActivity.intent(this@TripActivity, tripStop.stop.id, tripStop.stop.name))
            }
        }
        // Start at the stop you came from, with a couple of stops before it in view.
        if (scrollToFrom) fromRow?.let { row ->
            binding.scroll.post { binding.scroll.scrollTo(0, maxOf(0, row.top - row.height * 2)) }
        }
    }

    /** Puts the vehicle [at] that far down [row]'s piece of the line, over the rows, once they're laid out. */
    private fun placeVehicle(row: TtItemTripStopBinding, at: Float, mode: Mode) {
        val marker = VehicleMarker(this, mode)
        row.root.doOnLayout {
            val line = row.line
            marker.moveTo(row.root.left + line.left + line.width / 2f, row.root.top + line.top + line.height * at)
            binding.content.overlay.add(marker)
        }
    }

    /**
     * Draws the trip on OsmAnd's map and switches to it. First checks OSM, where OsmAnd's own transport routes come
     * from, and says so if its version of this route is out of date, since tapping OsmAnd's own stops shows that.
     */
    private fun showRoute() {
        val trip = trip ?: return
        val osmand = companion.osmand
        if (osmand.osmandPackage == null && osmand.findInstalledOsmand() == null) {
            return Toast.makeText(this, R.string.tt_osmand_missing, Toast.LENGTH_LONG).show()
        }
        if (!osmand.checkAccess()) return Toast.makeText(this, R.string.tt_route_no_access, Toast.LENGTH_LONG).show()
        binding.progress.isVisible = true
        binding.header.action.isEnabled = false
        val key = "${trip.route}|${trip.headsign}|${trip.mode}|${trip.stops.size}"
        background.execute {
            val shape = runCatching { peatus.tripShape(trip.id) }.getOrDefault(emptyList())
            val check = checks[key] ?: runCatching { osmCheck.check(trip) }.getOrNull()?.also { checks[key] = it }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.progress.isVisible = false
                binding.header.action.isEnabled = true
                when (check) {
                    null -> {
                        Toast.makeText(this, R.string.tt_route_check_failed, Toast.LENGTH_SHORT).show()
                        openRoute(trip, shape)
                    }
                    OsmRouteCheck.Result.Matches -> openRoute(trip, shape)
                    else -> warnOutdated(trip, check) { openRoute(trip, shape) }
                }
            }
        }
    }

    private fun warnOutdated(trip: Trip, check: OsmRouteCheck.Result, then: () -> Unit) {
        fun names(list: List<String>) = list.take(MAX_NAMES).joinToString(", ") + if (list.size > MAX_NAMES) ", …" else ""
        val message = when (check) {
            is OsmRouteCheck.Result.Outdated -> buildList {
                if (!check.osmRef.equals(trip.route, true)) add(getString(R.string.tt_route_renamed, check.osmRef))
                val stops = trip.stops.map { it.stop.name }.distinct().size
                if (check.missing.isNotEmpty()) {
                    add(getString(R.string.tt_route_missing_stops, check.missing.size, stops, names(check.missing)))
                }
                if (check.extra.isNotEmpty()) add(getString(R.string.tt_route_extra_stops, names(check.extra)))
                add(getString(R.string.tt_route_outdated_note))
            }.joinToString("\n\n")
            else -> getString(R.string.tt_route_missing, trip.route) + "\n\n" + getString(R.string.tt_route_outdated_note)
        }
        MaterialAlertDialogBuilder(this)
            .setIcon(
                getDrawable(R.drawable.tt_ic_warning)?.mutate()?.apply {
                    setTint(MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorError))
                },
            )
            .setTitle(R.string.tt_route_outdated_title)
            .setMessage(message)
            .setPositiveButton(R.string.tt_route_show) { _, _ -> then() }
            .setNegativeButton(R.string.tt_cancel, null)
            .show()
    }

    /** Draws the route in OsmAnd with its card open, and switches there; it goes away once the card is closed. */
    private fun openRoute(trip: Trip, shape: List<LatLon>) {
        val osmand = companion.osmand
        val fromStopId = intent.getStringExtra(EXTRA_FROM_STOP_ID)
        binding.progress.isVisible = true
        background.execute {
            val shown = OsmAndRoute.show(this, osmand, trip, shape, fromStopId)
            val launch = (osmand.osmandPackage ?: osmand.findInstalledOsmand())?.let { packageManager.getLaunchIntentForPackage(it) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.progress.isVisible = false
                if (shown && launch != null) startActivity(launch)
                else Toast.makeText(this, R.string.tt_route_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showHeader(trip: Trip, mode: Mode, times: List<Long>) {
        val minutes = ((times.lastOrNull() ?: 0L) - (times.firstOrNull() ?: 0L)) / 60_000
        Rows.header(
            binding.header, mode, trip.route, getString(R.string.tt_towards, trip.headsign), trip.longName,
            listOf(
                R.drawable.tt_ic_calendar to
                    Estonia.format("EEE d MMM", trip.serviceDay * 1000 + TimeUnit.HOURS.toMillis(12)),
                R.drawable.tt_ic_stops to
                    resources.getQuantityString(R.plurals.tt_stops, trip.stops.size, trip.stops.size),
                R.drawable.tt_ic_timer to
                    if (minutes < 60) getString(R.string.tt_in_min, minutes.toInt())
                    else getString(R.string.tt_in_h_min, (minutes / 60).toInt(), (minutes % 60).toInt()),
            ),
        )
    }

    companion object {
        private const val EXTRA_TRIP_ID = "trip_id"
        private const val EXTRA_SERVICE_DAY = "service_day"
        private const val EXTRA_FROM_STOP_ID = "from_stop_id"
        private const val TICK_MS = 30_000L
        private const val PAST_ALPHA = 0.5f
        private const val FADE_MS = 200L

        /** Stops named in the out-of-date warning; the rest are "…". */
        private const val MAX_NAMES = 6

        /** What OSM had for each route this session, so asking again doesn't bother Overpass. */
        private val checks = java.util.concurrent.ConcurrentHashMap<String, OsmRouteCheck.Result>()
        private const val LATE = 0xFFE65100.toInt()

        fun intent(context: Context, tripId: String, serviceDay: Long, fromStopId: String?): Intent =
            Intent(context, TripActivity::class.java)
                .putExtra(EXTRA_TRIP_ID, tripId)
                .putExtra(EXTRA_SERVICE_DAY, serviceDay)
                .putExtra(EXTRA_FROM_STOP_ID, fromStopId)
    }
}
