package dev.maksim.companion.timetable

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import dev.maksim.companion.core.companion
import dev.maksim.companion.core.feature
import dev.maksim.companion.core.padForSystemBars
import dev.maksim.companion.timetable.databinding.TtActivityStopBinding
import dev.maksim.companion.timetable.databinding.TtItemHourBinding
import dev.maksim.companion.timetable.databinding.TtItemMinuteBinding
import dev.maksim.companion.timetable.databinding.TtItemRouteDayBinding
import net.osmand.aidlapi.map.SetMapLocationParams
import java.io.IOException
import java.util.concurrent.Executors

/**
 * A stop's timetable: live next departures, then every route's times for the chosen day, printed by hour like
 * at the stop itself, the route leaving soonest first. Tapping a departure or a minute opens that trip
 * ([TripActivity]); tapping a route's header folds its times ([Fold]); tapping a route in the header jumps to it.
 *
 * OsmAnd opens this from the Next departure widget, so it's exported. Its stop menu's Show in Companion button
 * opens it too, from [TimetableFeature].
 */
class StopActivity : AppCompatActivity() {

    private lateinit var binding: TtActivityStopBinding
    private val peatus = PeatusClient()
    private val background = Executors.newSingleThreadExecutor()

    private lateinit var stopId: String
    private var stop: Stop? = null
    private var day = 0

    /** Answers for a day that's no longer selected are dropped. */
    private var request = 0

    /**
     * How much of each route's day is open, by [RouteDay] key, kept across reloads of the same day. Missing: the
     * default, from the next departure's hour on, or closed once it's done for the day.
     */
    private val folds = HashMap<String, Fold>()

    /** Each route's first card, so a tap on the route in the header can open and show it. */
    private val cards = HashMap<String, () -> Unit>()

    /** What [Fold] a route's header moves to next: upcoming hours, then the whole day, then closed, then again. */
    private enum class Fold { UPCOMING, FULL, CLOSED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = TtActivityStopBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        stopId = intent.getStringExtra(EXTRA_STOP_ID) ?: return finish()
        day = savedInstanceState?.getInt(KEY_DAY) ?: 0

        with(binding.header) {
            // Until the stop has loaded, the name OsmAnd gave will do.
            title.text = intent.getStringExtra(EXTRA_STOP_NAME)
            back.setOnClickListener { finish() }
            action.isVisible = true
            action.setIconResource(R.drawable.tt_ic_map)
            action.contentDescription = getString(R.string.tt_show_in_osmand)
            action.tooltipText = action.contentDescription
            action.setOnClickListener { showInOsmand() }
        }
        for (i in 0 until DAYS) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                tag = i
                text = dayLabel(this@StopActivity, i)
                isCheckable = true
            }
            binding.dayChips.addView(chip)
            if (i == day) chip.isChecked = true
        }
        binding.dayChips.setOnCheckedStateChangeListener { group, ids ->
            day = group.findViewById<Chip>(ids.firstOrNull() ?: return@setOnCheckedStateChangeListener).tag as Int
            folds.clear()
            States.loading(binding.content, accent())
            load()
        }
        States.loading(binding.content, accent())
    }

    /** Also on returning here, so the next departures are fresh. */
    override fun onStart() {
        super.onStart()
        load()
        background.execute { OsmAndRoute.clearLeftover(this, companion.osmand) }
    }

    override fun onResume() {
        super.onResume()
        OpenedScreens.resumed(this, OpenedScreens.Screen.STOP, stopId)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_DAY, day)
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        val id = ++request
        val day = day
        val unknown = getString(R.string.tt_stop_unknown)
        binding.progress.isVisible = true
        background.execute {
            val result = runCatching {
                val (stop, routes) = peatus.timetable(stopId, Estonia.serviceDate(day)) ?: throw IOException(unknown)
                val next = if (day == 0) peatus.stop(stopId, NEXT_DEPARTURES)?.departures else null
                Triple(stop, routes, next)
            }
            runOnUiThread { if (id == request && !isDestroyed) show(result) }
        }
    }

    private fun accent() = MaterialColors.getColor(binding.content, androidx.appcompat.R.attr.colorPrimary)

    private fun show(result: Result<Triple<Stop, List<RouteDay>, List<Departure>?>>) {
        val content = binding.content
        binding.progress.isVisible = false
        val (stop, routes, next) = result.getOrElse {
            States.error(content, getString(R.string.tt_load_failed, it.message)) {
                States.loading(content, accent())
                load()
            }
            return
        }
        // Coming from a loading or error state rather than a refresh: fade the timetable in.
        if (this.stop == null || content.getChildAt(0)?.id == R.id.state) {
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(FADE_MS).start()
        }
        content.removeAllViews()
        cards.clear()
        this.stop = stop
        val mode = Mode.of(stop.mode)
        // The routes come first and big; the stop's code, which only matters on the sign, goes small below the name.
        val about = listOfNotNull(getString(R.string.tt_stop_type, getString(mode.label)), stop.code).joinToString(" · ")
        val lines = stop.lines.ifEmpty { routes.map { Line(it.route, it.mode) }.distinctBy { it.name } }
        Rows.header(
            binding.header, mode, null, stop.name, about,
            lines = lines, running = routes.mapTo(HashSet()) { it.route }, onLine = { cards[it.name]?.invoke() },
        )
        val now = System.currentTimeMillis()

        if (next != null) {
            Rows.section(content, getString(R.string.tt_next_departures))
            if (next.isEmpty()) Rows.row(content).run {
                time.isVisible = false
                badge.isVisible = false
                title.setText(R.string.tt_no_departures)
            }
            for (departure in next) Rows.row(content).run {
                time.text = TransitFormat.clockWithDay(departure.time, now)
                Rows.badge(badge, departure.route, departure.mode)
                title.text = departure.headsign
                note.text = listOfNotNull(
                    TransitFormat.relative(this@StopActivity, departure.time, now),
                    getString(R.string.tt_live).takeIf { departure.isRealtime },
                ).joinToString("\n")
                root.setOnClickListener { openTrip(departure.tripId, departure.serviceDay) }
            }
        }

        Rows.section(content, getString(R.string.tt_timetable_for, dayLabel(this, day)))
        if (routes.isEmpty()) {
            States.empty(FrameLayout(this).also { content.addView(it) }, getString(R.string.tt_no_departures_day))
            return
        }
        // Soonest first; the routes done for the day go last, in the usual order.
        val upcoming = routes.associateWith { route ->
            route.times.firstOrNull { TransitFormat.serviceTime(route.serviceDay, it.first) >= now }
        }
        val nextTime = { route: RouteDay -> upcoming[route]?.let { TransitFormat.serviceTime(route.serviceDay, it.first) } }
        routes.sortedWith(
            compareBy<RouteDay>({ nextTime(it) == null }, { nextTime(it) ?: 0L }).thenBy(RouteOrder) { it.route },
        ).forEach { addRoute(it, upcoming[it], now) }
    }

    private fun addRoute(route: RouteDay, upcoming: Pair<Int, String>?, now: Long) {
        val item = TtItemRouteDayBinding.inflate(layoutInflater, binding.content, true)
        val color = ColorStateList.valueOf(Mode.of(route.mode).color)
        Rows.badge(item.badge, route.route, route.mode)
        item.headsign.text = getString(R.string.tt_towards, route.headsign)
        item.longName.text = route.longName
        item.longName.isVisible = route.longName.isNotEmpty()
        val nextTime = upcoming?.let { TransitFormat.serviceTime(route.serviceDay, it.first) }
        if (nextTime != null) {
            val soon = TransitFormat.relative(this, nextTime, now)
            item.next.text = when (soon) {
                null -> TransitFormat.clock(nextTime)
                getString(R.string.tt_now) -> soon
                else -> getString(R.string.tt_next_in, soon)
            }
            item.next.backgroundTintList = color.withAlpha(0x33)
            item.next.setOnClickListener { openTrip(upcoming.second, route.serviceDay) }
        } else {
            item.next.setText(R.string.tt_done_today)
            item.next.backgroundTintList = color.withAlpha(0x14)
            item.next.setTextColor(MaterialColors.getColor(item.next, com.google.android.material.R.attr.colorOnSurfaceVariant))
            item.next.isClickable = false
        }

        // Service times can pass 24:00; the clock hour puts 25:10 under 01 at the end, as printed timetables do.
        val hourOf = { seconds: Int -> Estonia.format("HH", TransitFormat.serviceTime(route.serviceDay, seconds)) }
        val nextHour = upcoming?.let { hourOf(it.first) }
        // The hours before the next departure's, hidden until the whole day is asked for.
        val earlier = mutableListOf<View>()
        for ((hour, times) in route.times.groupBy { hourOf(it.first) }) {
            val line = TtItemHourBinding.inflate(layoutInflater, item.hours, true)
            if (nextHour != null && earlier.size == item.hours.childCount - 1 && hour != nextHour) earlier += line.root
            line.hour.text = hour
            if (hour == nextHour) {
                line.hour.backgroundTintList = color
                line.hour.setTextColor(Color.WHITE)
            } else if (times.all { TransitFormat.serviceTime(route.serviceDay, it.first) < now }) {
                line.hour.alpha = PAST_ALPHA
            }
            for ((seconds, tripId) in times) {
                val time = TransitFormat.serviceTime(route.serviceDay, seconds)
                val minute = TtItemMinuteBinding.inflate(layoutInflater, line.minutes, true).root
                minute.text = Estonia.format("mm", time)
                minute.contentDescription = TransitFormat.clock(time)
                minute.setOnClickListener { openTrip(tripId, route.serviceDay) }
                if (time == nextTime) {
                    minute.setBackgroundResource(R.drawable.tt_minute_bg)
                    minute.backgroundTintList = color
                    minute.setTextColor(Color.WHITE)
                    minute.setTypeface(minute.typeface, Typeface.BOLD)
                } else if (time < now) {
                    minute.alpha = PAST_ALPHA
                }
            }
        }

        // With nothing earlier to show, "upcoming" is the whole day, so the dropdown skips a step.
        val key = "${route.route}|${route.headsign}|${route.mode}"
        var fold = folds[key] ?: if (upcoming == null) Fold.CLOSED else Fold.UPCOMING
        fun next(fold: Fold) = when (fold) {
            Fold.UPCOMING -> if (earlier.isNotEmpty()) Fold.FULL else Fold.CLOSED
            Fold.FULL -> Fold.CLOSED
            Fold.CLOSED -> if (upcoming != null) Fold.UPCOMING else Fold.FULL
        }
        fun apply(animate: Boolean) {
            if (animate) TransitionManager.beginDelayedTransition(binding.content, foldTransition)
            item.hours.isVisible = fold != Fold.CLOSED
            item.divider.isVisible = fold != Fold.CLOSED
            earlier.forEach { it.isVisible = fold == Fold.FULL }
            // Down while there's more to show, up when the next tap closes it.
            val rotation = if (next(fold) == Fold.CLOSED) 180f else 0f
            if (animate) item.fold.animate().rotation(rotation).setDuration(FOLD_MS).start() else item.fold.rotation = rotation
            item.header.contentDescription = listOf(
                item.badge.text, item.headsign.text,
                getString(
                    when (next(fold)) {
                        Fold.UPCOMING -> R.string.tt_fold_show_upcoming
                        Fold.FULL -> R.string.tt_fold_show_all
                        Fold.CLOSED -> R.string.tt_fold_hide
                    },
                ),
            ).joinToString(", ")
        }
        apply(animate = false)
        item.header.setOnClickListener {
            fold = next(fold)
            folds[key] = fold
            apply(animate = true)
        }
        // A tap on the route in the header: open it if it's closed, bring it into view and give it a nudge.
        cards.putIfAbsent(route.route) {
            if (fold == Fold.CLOSED) {
                fold = next(fold)
                folds[key] = fold
                apply(animate = true)
            }
            val card = item.root
            binding.scroll.post {
                binding.scroll.smoothScrollTo(0, maxOf(0, card.top - resources.getDimensionPixelSize(R.dimen.tt_jump_margin)))
                card.animate().scaleX(PULSE).scaleY(PULSE).setDuration(PULSE_MS).withEndAction {
                    card.animate().scaleX(1f).scaleY(1f).setDuration(PULSE_MS).start()
                }.start()
            }
        }
    }

    private val foldTransition = AutoTransition().apply {
        duration = FOLD_MS
        interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    }

    private fun openTrip(tripId: String, serviceDay: Long) {
        startActivity(TripActivity.intent(this, tripId, serviceDay, stopId))
    }

    /**
     * Switches to OsmAnd with the stop's menu open on its map, as if it had been tapped there. With timetables
     * off in OsmAnd there's no stop there to open, so the map just moves to it.
     */
    private fun showInOsmand() {
        val stop = stop ?: return
        val osmand = companion.osmand
        val pkg = osmand.osmandPackage ?: osmand.findInstalledOsmand()
            ?: return Toast.makeText(this, R.string.tt_osmand_missing, Toast.LENGTH_LONG).show()
        // Runs on a background thread.
        val open = { shown: Boolean ->
            val moved = shown || osmand.hasAccess && osmand.call("setMapLocation") {
                it.setMapLocation(SetMapLocationParams(stop.lat, stop.lon, SHOW_ZOOM, 0f, false))
            } == true
            // Without API access OsmAnd still understands a geo: link.
            val intent = if (moved) packageManager.getLaunchIntentForPackage(pkg)
            else Intent(Intent.ACTION_VIEW, Uri.parse("geo:${stop.lat},${stop.lon}?z=$SHOW_ZOOM")).setPackage(pkg)
            runOnUiThread { if (!isDestroyed) intent?.let { startActivity(it) } }
        }
        if (!feature<TimetableFeature>().showInOsmand(stop, open)) background.execute { open(false) }
    }

    companion object {
        private const val EXTRA_STOP_ID = "stop_id"
        private const val EXTRA_STOP_NAME = "stop_name"
        private const val KEY_DAY = "day"
        private const val DAYS = 7
        private const val NEXT_DEPARTURES = 8
        private const val SHOW_ZOOM = 17

        /** Material's disabled-content opacity, for departures already gone. */
        private const val PAST_ALPHA = 0.38f

        private const val FADE_MS = 200L
        private const val FOLD_MS = 250L
        private const val PULSE = 1.03f
        private const val PULSE_MS = 120L

        fun intent(context: Context, stopId: String, stopName: String?): Intent =
            Intent().setClassName(context.packageName, StopActivity::class.java.name)
                .putExtra(EXTRA_STOP_ID, stopId)
                .putExtra(EXTRA_STOP_NAME, stopName)

        fun dayLabel(context: Context, daysFromToday: Int): String = when (daysFromToday) {
            0 -> context.getString(R.string.tt_today)
            1 -> context.getString(R.string.tt_tomorrow)
            else -> Estonia.format("EEE d MMM", Estonia.dayStart(daysFromToday))
        }
    }
}
