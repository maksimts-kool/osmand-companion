package dev.maksim.companion.timetable

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Choreographer
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.activity.addCallback
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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * One run of a route: every stop it calls at and when, the route's timetable seen from the vehicle; live, in
 * green, where the vehicle gives its times ([PeatusClient.live]). The header's route button draws it on OsmAnd's
 * map ([showRoute]), after warning if OsmAnd's own version of it is out of date.
 */
class TripActivity : AppCompatActivity() {

    private lateinit var binding: TtActivityTripBinding
    private val peatus = PeatusClient()
    private val osmCheck = OsmRouteCheck()
    private val background = Executors.newSingleThreadExecutor()

    /** As peatus.ee has it; [trip] is the same with the live times, refreshed while the screen is open. */
    private var timetable: Trip? = null
    private var trip: Trip? = null

    /** The rows [render] drew, and the stop the vehicle had last left then ([Position.last]). */
    private var rows: List<TtItemTripStopBinding> = emptyList()
    private var renderedLast = -1

    /** The stops [rows] were made for: while they're the same, [render] updates them rather than making them anew. */
    private var rowsFor: List<String>? = null

    /** The rows' own colors for the times, for those that aren't live (any more). */
    private var timeColors: ColorStateList? = null
    private var etaColors: ColorStateList? = null

    /** The vehicle, kept from one [render] to the next so it can glide ([glide]) to where it is now. */
    private var vehicle: VehicleMarker? = null
    private var vehicleX = 0f
    private var vehicleY = 0f
    private var shown = false
    private var targetY = 0f

    /** How late each stop ahead was last drawn ([showDelay]), by index, so only a change grows in; and the next one's. */
    private var shownDelays: Map<Int, Int> = emptyMap()
    private val delays = HashMap<Int, Int>()

    /** Its arrows go out first. */
    override fun finish() {
        if (!Arrows.leave(this) { super.finish() }) super.finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back through finish(), not the system's own, so the arrows go out first.
        onBackPressedDispatcher.addCallback(this) { finish() }
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

    /** Moves the vehicle along while the screen is open, with fresh live times if there are any. */
    private val tick = object : Runnable {
        override fun run() {
            render(scrollToFrom = false)
            refreshLive()
            binding.root.postDelayed(this, TICK_MS)
        }
    }

    private fun refreshLive() {
        val timetable = timetable ?: return
        if (!PeatusClient.mayGoLive(timetable)) return
        background.execute {
            val live = runCatching { peatus.live(timetable) }.getOrNull() ?: return@execute
            runOnUiThread {
                if (isDestroyed || this.timetable !== timetable) return@runOnUiThread
                trip = live
                render(scrollToFrom = false)
            }
        }
    }

    /** Moves the vehicle on a little every second, between the [tick]s. */
    private val follow = object : Runnable {
        override fun run() {
            moveVehicle()
            binding.root.postDelayed(this, FOLLOW_MS)
        }
    }

    override fun onStart() {
        super.onStart()
        binding.root.postDelayed(tick, TICK_MS)
        binding.root.postDelayed(follow, FOLLOW_MS)
        background.execute { OsmAndRoute.clearLeftover(this, companion.osmand) }
    }

    override fun onStop() {
        binding.root.removeCallbacks(tick)
        binding.root.removeCallbacks(follow)
        glide.stop()
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
            val result = runCatching {
                val trip = peatus.trip(tripId, date) ?: throw IOException(unknown)
                trip to runCatching { peatus.live(trip) }.getOrDefault(trip)
            }
            runOnUiThread { if (!isDestroyed) show(result) }
        }
    }

    private fun show(result: Result<Pair<Trip, Trip>>) {
        binding.progress.isVisible = false
        val (timetable, trip) = result.getOrElse {
            States.error(binding.content, getString(R.string.tt_load_failed, it.message)) {
                States.loading(binding.content, MaterialColors.getColor(binding.content, androidx.appcompat.R.attr.colorPrimary))
                load()
            }
            return
        }
        this.timetable = timetable
        this.trip = trip
        binding.header.action.isVisible = true
        binding.content.alpha = 0f
        binding.content.animate().alpha(1f).setDuration(FADE_MS).start()
        render(scrollToFrom = true)
    }

    /**
     * Where the vehicle is at [now]: it has left stop [last] (-1 before it sets off) and is [progress] of the way to
     * the next. Between the last stop the city's feed no longer expects it at and the next, when there are live
     * times, else where the times say.
     */
    private data class Position(val last: Int, val progress: Float, val between: Boolean)

    private fun positionOf(trip: Trip, now: Long): Position {
        val times = trip.stops.map { it.time(trip.serviceDay) }
        val last = if (trip.liveFrom >= 0) trip.liveFrom - 1 else times.indexOfLast { it <= now }
        val between = last >= 0 && last < times.lastIndex
        val progress = if (!between) 0f
        else ((now - times[last]).toFloat() / max(1L, times[last + 1] - times[last])).coerceIn(0f, 1f)
        return Position(last, progress, between)
    }

    /** Draws the trip as a line diagram, the vehicle on it where it is now ([moveVehicle]). */
    private fun render(scrollToFrom: Boolean) {
        val trip = trip ?: return
        val mode = Mode.of(trip.mode)
        val color = ColorStateList.valueOf(mode.color)
        val now = System.currentTimeMillis()
        val times = trip.stops.map { it.time(trip.serviceDay) }
        showHeader(trip, mode, times)

        val content = binding.content
        val fromStopId = intent.getStringExtra(EXTRA_FROM_STOP_ID)
        val (last, progress, between) = positionOf(trip, now)
        val live = getColor(R.color.tt_live)
        var fromRow: View? = null
        val ids = trip.stops.map { it.stop.id }
        if (ids != rowsFor || content.getChildAt(0)?.id == R.id.state) {
            // Its first drawing, or another set of stops: rows for them. From then on, only what they show changes.
            content.removeAllViews()
            rows = trip.stops.indices.map { i -> newRow(trip, i, mode, color, fromStopId) }
            rowsFor = ids
            shownDelays = emptyMap()
        }
        for ((i, tripStop) in trip.stops.withIndex()) {
            val time = times[i]
            val passed = i <= last
            val row = rows[i]
            if (tripStop.stop.id == fromStopId) fromRow = row.root
            row.time.text = TransitFormat.clock(time)
            row.time.setTextColor(if (tripStop.isRealtime) ColorStateList.valueOf(live) else timeColors)
            with(row.line) {
                this.passed = passed
                passedIn = if (passed) 1f else if (between && i == last + 1) progress * 2 - 1 else 0f
                passedOut = if (!passed) 0f else if (between && i == last) progress * 2 else 1f
                invalidate()
            }
            // That it's live is said once, in the header; how late, at the end of each stop's ETA.
            // Only the stops still ahead; ahead of a late vehicle too, though their time has passed.
            val soon = TransitFormat.relative(this, time, now)?.takeIf { !passed && (time >= now || trip.liveFrom >= 0) }
            row.etaText.text = soon
            row.eta.isVisible = soon != null
            // The next stop's stronger.
            val tint = if (i == last + 1) 0x40 else 0x1F
            row.etaMain.backgroundTintList =
                if (tripStop.isRealtime) ColorStateList.valueOf(live).withAlpha(tint)
                else color.withAlpha(if (i == last + 1) 0x40 else 0x1A)
            row.etaText.setTextColor(if (tripStop.isRealtime) ColorStateList.valueOf(live) else etaColors)
            showDelay(row, i, (tripStop.expected - tripStop.scheduled) / 60, soon != null, tint)
            row.time.alpha = if (passed) PAST_ALPHA else 1f
            row.name.alpha = if (passed) PAST_ALPHA else 1f
        }
        renderedLast = last
        shownDelays = delays.toMap()
        delays.clear()
        if (vehicle == null) vehicle = VehicleMarker(this, mode)
        content.doOnLayout { moveVehicle() }
        // Start at the stop you came from, with a couple of stops before it in view.
        if (scrollToFrom) fromRow?.let { row ->
            binding.scroll.post { binding.scroll.scrollTo(0, maxOf(0, row.top - row.height * 2)) }
        }
    }

    /** Stop [i]'s row, with what doesn't change while the screen is open: its name, and whether it's yours. */
    private fun newRow(trip: Trip, i: Int, mode: Mode, color: ColorStateList, fromStopId: String?): TtItemTripStopBinding {
        val tripStop = trip.stops[i]
        val row = TtItemTripStopBinding.inflate(layoutInflater, binding.content, true)
        if (timeColors == null) {
            timeColors = row.time.textColors
            etaColors = row.etaText.textColors
        }
        row.name.text = tripStop.stop.name
        with(row.line) {
            setMode(mode)
            kind = when (i) {
                0 -> TripLineView.Stop.FIRST
                trip.stops.lastIndex -> TripLineView.Stop.LAST
                else -> TripLineView.Stop.MIDDLE
            }
        }
        val yours = tripStop.stop.id == fromStopId
        if (yours) {
            row.root.setBackgroundResource(R.drawable.tt_row_highlight_bg)
            row.root.backgroundTintList = color.withAlpha(0x26)
            row.line.emphasized = true
            row.name.setTypeface(row.name.typeface, Typeface.BOLD)
            row.time.setTypeface(row.time.typeface, Typeface.BOLD)
            row.status.setText(R.string.tt_your_stop)
            row.status.setTextColor(mode.color)
        }
        row.status.isVisible = yours
        row.root.setOnClickListener {
            startActivity(StopActivity.intent(this@TripActivity, tripStop.stop.id, tripStop.stop.name, OpenedScreens.now()))
        }
        return row
    }

    /**
     * The minutes [row] (stop [i]) is [delay] late, early if less than 0, as the second part of its ETA chip ([shown]
     * if it has one), like the first: "+2" in orange, "−1" in blue, on a [tint] of the same. Nothing when on time. It
     * grows out of the chip's end when it first shows or changes, but not again on every redraw.
     */
    private fun showDelay(row: TtItemTripStopBinding, i: Int, delay: Int, shown: Boolean, tint: Int) {
        val pill = row.delay
        pill.isVisible = shown && delay != 0
        row.etaMain.setBackgroundResource(if (pill.isVisible) R.drawable.tt_pill_start_bg else R.drawable.tt_pill_bg)
        if (!pill.isVisible) {
            row.eta.contentDescription = null
            return
        }
        delays[i] = delay
        val color = getColor(if (delay > 0) R.color.tt_late else R.color.tt_early)
        pill.text = if (delay > 0) "+$delay" else "\u2212${-delay}"
        pill.setTextColor(color)
        pill.backgroundTintList = ColorStateList.valueOf(color).withAlpha(tint)
        row.eta.contentDescription = listOf(
            row.etaText.text,
            getString(if (delay > 0) R.string.tt_late else R.string.tt_early, abs(delay)),
        ).joinToString(", ")
        if (shownDelays[i] == delay) return
        // Grows out of the chip's end.
        pill.alpha = 0f
        pill.doOnLayout {
            it.pivotX = it.width.toFloat()
            it.scaleX = POP_FROM
            it.animate().scaleX(1f).alpha(1f).setDuration(POP_MS).setInterpolator(OvershootInterpolator()).start()
        }
    }

    /**
     * Sets the vehicle off towards where it is now, on the line between the stop it last left and the next: it
     * glides there ([glide]) rather than jumping, so it creeps along as time goes by, and when fresh live times
     * put it somewhere else, it moves over to there. Once it has passed a stop, the rows are drawn again.
     */
    private fun moveVehicle() {
        val trip = trip ?: return
        val marker = vehicle ?: return
        val position = positionOf(trip, System.currentTimeMillis())
        if (position.last != renderedLast) return render(scrollToFrom = false)
        val from = rows.getOrNull(position.last)
        val to = rows.getOrNull(position.last + 1)
        // Rows just drawn again: it stays where it is until they're laid out.
        if (from != null && to != null && (!from.root.isLaidOut || !to.root.isLaidOut)) return
        if (!position.between || from == null || to == null) {
            binding.content.overlay.remove(marker)
            shown = false
            glide.stop()
            return
        }
        fun centerY(row: TtItemTripStopBinding) = row.root.top + row.line.top + row.line.height / 2f
        vehicleX = from.root.left + from.line.left + from.line.width / 2f
        targetY = centerY(from) + (centerY(to) - centerY(from)) * position.progress
        if (!shown) {
            // Where it is to begin with; from then on, it glides.
            vehicleY = targetY
            marker.moveTo(vehicleX, vehicleY)
            binding.content.overlay.add(marker)
            shown = true
        } else {
            glide.start()
        }
    }

    /**
     * Brings the vehicle to [targetY] a frame at a time, quickly at first and slowing down as it gets there, and stops
     * once it's there: in between the seconds of [follow], nothing is drawn.
     */
    private val glide = object : Choreographer.FrameCallback {
        private var running = false
        private var lastFrame = 0L

        fun start() {
            if (running || abs(targetY - vehicleY) < SETTLED_PX) return
            running = true
            lastFrame = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }

        fun stop() {
            running = false
            Choreographer.getInstance().removeFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val elapsedMs = if (lastFrame == 0L) FRAME_MS else (frameTimeNanos - lastFrame) / 1_000_000f
            lastFrame = frameTimeNanos
            vehicleY += (targetY - vehicleY) * (1 - exp(-elapsedMs / GLIDE_MS))
            if (abs(targetY - vehicleY) < SETTLED_PX) {
                vehicleY = targetY
                running = false
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
            vehicle?.moveTo(vehicleX, vehicleY)
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
            live = trip.stops.any { it.isRealtime },
        )
    }

    companion object {
        private const val EXTRA_TRIP_ID = "trip_id"
        private const val EXTRA_SERVICE_DAY = "service_day"
        private const val EXTRA_FROM_STOP_ID = "from_stop_id"
        private const val TICK_MS = 30_000L

        /** How often the vehicle is moved on ([follow]), and how it glides there ([glide]). */
        private const val FOLLOW_MS = 1_000L
        private const val GLIDE_MS = 250f
        private const val FRAME_MS = 16f
        private const val SETTLED_PX = 0.1f
        private const val PAST_ALPHA = 0.5f
        private const val FADE_MS = 200L

        /** Stops named in the out-of-date warning; the rest are "…". */
        private const val MAX_NAMES = 6

        /** What OSM had for each route this session, so asking again doesn't bother Overpass. */
        private val checks = java.util.concurrent.ConcurrentHashMap<String, OsmRouteCheck.Result>()

        /** How [showDelay]'s part grows in. */
        private const val POP_FROM = 0.4f
        private const val POP_MS = 350L

        fun intent(context: Context, tripId: String, serviceDay: Long, fromStopId: String?): Intent =
            Intent(context, TripActivity::class.java)
                .putExtra(EXTRA_TRIP_ID, tripId)
                .putExtra(EXTRA_SERVICE_DAY, serviceDay)
                .putExtra(EXTRA_FROM_STOP_ID, fromStopId)
    }
}
