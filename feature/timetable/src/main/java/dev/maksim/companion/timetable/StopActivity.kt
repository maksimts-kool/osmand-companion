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
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import dev.maksim.companion.core.companion
import dev.maksim.companion.core.feature
import dev.maksim.companion.core.padForSystemBars
import dev.maksim.companion.core.Analytics
import dev.maksim.companion.core.Parallel
import dev.maksim.companion.timetable.databinding.TtActivityStopBinding
import dev.maksim.companion.timetable.databinding.TtItemHourBinding
import dev.maksim.companion.timetable.databinding.TtItemMinuteBinding
import dev.maksim.companion.timetable.databinding.TtItemNextTimeBinding
import dev.maksim.companion.timetable.databinding.TtItemRouteDayBinding
import net.osmand.aidlapi.map.SetMapLocationParams
import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.Executors

/**
 * A stop's timetable: one card per route, the route leaving soonest first. Folded, which is how they start, a
 * card is one line of the route's next departures, so all the routes fit on screen together; today those come
 * live where there are live times ([Next], [PeatusClient.stopWithin]), so there's no separate list of next
 * departures. Unfolded, it's the chosen day's times printed by hour like at the stop itself ([Fold]). Tapping a departure or a minute opens that
 * trip ([TripActivity]); tapping a route in the header jumps to it.
 *
 * OsmAnd opens this from the Next departure widget, so it's exported. Its stop menu's Full timetable button
 * opens it too, from [TimetableFeature]. Either way it's in a task of its own, so Back goes back to OsmAnd.
 */
class StopActivity : AppCompatActivity() {

    private lateinit var binding: TtActivityStopBinding
    private lateinit var headerLines: HeaderLines
    private val peatus = PeatusClient()
    private val background = Executors.newSingleThreadExecutor()

    private lateinit var stopId: String
    private var stop: Stop? = null
    private var day = 0

    /** Answers for a day that's no longer selected are dropped. */
    private var request = 0

    /** What's on screen, so [refreshLive] only has to get the next departures again. */
    private var shown: Triple<Stop, List<RouteDay>, List<Departure>?>? = null

    /** The service date [shown] was loaded on: the day's timetable holds until then. */
    private var loadedDate: String? = null

    /** The routes the cards on screen were made for; a refresh of the same updates them ([RouteCard.bind]). */
    private var builtFor: List<RouteDay>? = null
    private val routeCards = IdentityHashMap<RouteDay, RouteCard>()

    /** Where in the content the route cards start, after the section title. */
    private var cardsStart = 0

    /** Keeps today's live times fresh while the screen is open. */
    private val refresh = object : Runnable {
        override fun run() {
            refreshLive()
            binding.root.postDelayed(this, REFRESH_MS)
        }
    }

    /**
     * From the tap that opened it (in OsmAnd, or a screen here) until the timetable, or why not, is showing. It also
     * opens from OsmAnd's widget, whose intent can't carry the tap: then it's timed from onCreate.
     */
    private var screenLoad: Analytics.ScreenLoad? = null

    /** How much of each route's day is open, by [RouteDay] key, kept across reloads of the same day. */
    private val folds = HashMap<String, Fold>()

    /** Each route's first card, so a tap on the route in the header can open and show it. */
    private val cards = HashMap<String, () -> Unit>()

    /**
     * How much of a route shows; a tap on its card moves to the next: only its next departures, then the hours
     * from the next departure's on, then the whole day, then back.
     */
    private enum class Fold { NEXT, UPCOMING, FULL }

    /**
     * A route's next departure: [tripId], its timetabled [scheduled] time, and when it actually leaves ([time]),
     * live when [isRealtime]. A late one still counts once its timetabled time has passed.
     */
    private class Next(val tripId: String, val serviceDay: Long, val scheduled: Long, val time: Long, val isRealtime: Boolean)

    /** Its arrows go out first. */
    override fun finish() {
        if (!Arrows.leave(this) { super.finish() }) super.finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back through finish(), not the system's own, so the arrows go out first.
        onBackPressedDispatcher.addCallback(this) { finish() }
        binding = TtActivityStopBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        stopId = intent.getStringExtra(EXTRA_STOP_ID) ?: return finish()
        val pressedAt = intent.getLongExtra(OpenedScreens.EXTRA_PRESSED_AT, -1).takeIf { it >= 0 && savedInstanceState == null }
        screenLoad = Analytics.screenLoad(this, "Stop timetable", pressedAt)
        day = savedInstanceState?.getInt(KEY_DAY) ?: 0
        headerLines = HeaderLines(binding.header, binding.top, binding.scroll, binding.content)

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

    /**
     * Also on returning here, so the next departures are fresh. The day's timetable stays what it was, so it's only
     * loaded again once the date has changed: otherwise just the live times.
     */
    override fun onStart() {
        super.onStart()
        if (shown != null && loadedDate == Estonia.serviceDate()) refreshLive() else load()
        binding.root.postDelayed(refresh, REFRESH_MS)
        background.execute { OsmAndRoute.clearLeftover(this, companion.osmand) }
    }

    override fun onStop() {
        binding.root.removeCallbacks(refresh)
        super.onStop()
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
        screenLoad?.cancel()
        background.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        val id = ++request
        val day = day
        val unknown = getString(R.string.tt_stop_unknown)
        binding.progress.isVisible = true
        background.execute {
            val date = Estonia.serviceDate()
            val result = runCatching {
                // The timetable and the live next departures don't wait for each other.
                val next = if (day == 0) Parallel.submit { peatus.stopWithin(stopId, LIVE_WITHIN_S)?.departures } else null
                val (stop, routes) = (if (day == 0) peatus.today(stopId) else peatus.timetable(stopId, Estonia.serviceDate(day)))
                    ?: throw IOException(unknown)
                Triple(stop, routes, next?.let { Parallel.await(it) })
            }
            runOnUiThread {
                if (id != request || isDestroyed) return@runOnUiThread
                if (result.isSuccess) loadedDate = date
                show(result)
            }
        }
    }

    /**
     * Today's next departures again, over the timetable already there. If they don't come, the ones there were, with
     * the time moved on ("in 5 min", what has gone).
     */
    private fun refreshLive() {
        val (stop, routes, previous) = shown ?: return
        if (day != 0 || binding.progress.isVisible) return
        val id = request
        background.execute {
            val next = runCatching { peatus.stopWithin(stopId, LIVE_WITHIN_S)?.departures }.getOrNull() ?: previous
            runOnUiThread { if (id == request && !isDestroyed) show(Result.success(Triple(stop, routes, next))) }
        }
    }

    private fun accent() = MaterialColors.getColor(binding.content, androidx.appcompat.R.attr.colorPrimary)

    private fun show(result: Result<Triple<Stop, List<RouteDay>, List<Departure>?>>) {
        val content = binding.content
        binding.progress.isVisible = false
        screenLoad?.finish(result.isSuccess)
        screenLoad = null
        val (stop, routes, next) = result.getOrElse {
            shown = null
            builtFor = null
            routeCards.clear()
            States.error(content, getString(R.string.tt_load_failed, it.message)) {
                States.loading(content, accent())
                load()
            }
            return
        }
        shown = Triple(stop, routes, next)
        val now = System.currentTimeMillis()
        // Soonest first; the routes done for the day go last, in the usual order.
        val live = next.orEmpty().associateBy { it.serviceDay to it.tripId }
        val days = IdentityHashMap<RouteDay, List<Next>>().apply { routes.forEach { put(it, dayOf(it, live)) } }
        val first = IdentityHashMap<RouteDay, Next?>().apply { routes.forEach { put(it, days.getValue(it).firstOrNull { d -> d.time >= now }) } }
        val sorted = routes.sortedWith(
            compareBy<RouteDay>({ first[it] == null }, { first[it]?.time ?: 0L }).thenBy(RouteOrder) { it.route },
        )
        if (routes === builtFor && content.getChildAt(0)?.id != R.id.state) {
            // A refresh: the same cards, in their new order, each with its new times.
            cards.clear()
            sorted.forEachIndexed { i, route ->
                val card = routeCards.getValue(route)
                card.bind(days.getValue(route), now)
                if (content.getChildAt(cardsStart + i) !== card.root) {
                    content.removeView(card.root)
                    content.addView(card.root, cardsStart + i)
                }
                cards.putIfAbsent(route.route, card::jump)
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
        routeCards.clear()
        builtFor = routes
        this.stop = stop
        val mode = Mode.of(stop.mode)
        // The routes come first and big; the stop's code, which only matters on the sign, goes small below the name.
        val about = listOfNotNull(getString(R.string.tt_stop_type, getString(mode.label)), stop.code).joinToString(" · ")
        val lines = stop.lines.ifEmpty { routes.map { Line(it.route, it.mode) }.distinctBy { it.name } }
        Rows.header(
            binding.header, mode, null, stop.name, about,
            lines = lines, running = routes.mapTo(HashSet()) { it.route }, onLine = { cards[it.name]?.invoke() },
        )
        headerLines.refresh()

        Rows.section(content, getString(R.string.tt_timetable_for, dayLabel(this, day)))
        if (routes.isEmpty()) {
            States.empty(FrameLayout(this).also { content.addView(it) }, getString(R.string.tt_no_departures_day))
            return
        }
        cardsStart = content.childCount
        for (route in sorted) {
            val card = RouteCard(route)
            routeCards[route] = card
            card.bind(days.getValue(route), now)
            cards.putIfAbsent(route.route, card::jump)
        }
    }

    /**
     * [route]'s departures for the day in the order they leave, each at its live time if it's among the stop's
     * [live] next departures.
     */
    private fun dayOf(route: RouteDay, live: Map<Pair<Long, String>, Departure>): List<Next> =
        route.times.map { time ->
            val departure = live[time.serviceDay to time.tripId]
            Next(time.tripId, time.serviceDay, time.time, departure?.time ?: time.time, departure?.isRealtime == true)
        }.sortedBy { it.time }

    /**
     * A route's card. [bind] fills it with the day: the route's departures, as [dayOf] has them. The hours show them
     * at their live times, so a bus that's late is under the minute it leaves, in green. A refresh binds it again;
     * the hours, which are most of the screen's views, are only drawn again when what they show has changed.
     */
    private inner class RouteCard(private val route: RouteDay) {
        val item = TtItemRouteDayBinding.inflate(layoutInflater, binding.content, true)
        val root: View get() = item.root
        private val color = ColorStateList.valueOf(Mode.of(route.mode).color)
        private val key = "${route.route}|${route.headsign}|${route.mode}"
        private var fold = folds[key] ?: Fold.NEXT
        private var upcoming: Next? = null

        /** The hours before the next departure's, hidden until the whole day is asked for. */
        private val earlier = mutableListOf<View>()

        /** What the hours were last drawn from. */
        private var hoursDrawn: List<Any?>? = null

        init {
            Rows.badge(item.badge, route.route, route.mode)
            Arrows.set(item.headsign, getString(R.string.tt_towards, route.headsign))
            item.longName.text = route.longName
            item.header.setOnClickListener {
                fold = next(fold)
                folds[key] = fold
                apply(animate = true)
            }
        }

        fun bind(day: List<Next>, now: Long) {
            val next = day.filter { it.time >= now }
            upcoming = next.firstOrNull()
            item.times.removeAllViews()
            addNextTimes(item, next, color, now)
            val hours = listOf(upcoming?.tripId, day.map { listOf(it.tripId, it.time, it.isRealtime, it.time < now) })
            if (hours != hoursDrawn) {
                hoursDrawn = hours
                drawHours(day, now)
            }
            apply(animate = false)
        }

        private fun drawHours(day: List<Next>, now: Long) {
            item.hours.removeAllViews()
            earlier.clear()
            // Service times can pass 24:00; the clock hour puts 25:10 under 01 at the end, as printed timetables do.
            // By date too: after midnight, last night's 01 comes first, and tonight's at the end.
            val hourOf = { time: Long -> Estonia.format("yyyyMMddHH", time) }
            val upcoming = upcoming
            val nextHour = upcoming?.let { hourOf(it.time) }
            val liveColor = getColor(R.color.tt_live)
            for ((hour, times) in day.groupBy { hourOf(it.time) }) {
                val line = TtItemHourBinding.inflate(layoutInflater, item.hours, true)
                if (nextHour != null && earlier.size == item.hours.childCount - 1 && hour != nextHour) earlier += line.root
                line.hour.text = hour.takeLast(2)
                if (hour == nextHour) {
                    line.hour.backgroundTintList = color
                    line.hour.setTextColor(Color.WHITE)
                } else if (times.all { it.time < now }) {
                    line.hour.alpha = PAST_ALPHA
                }
                for (departure in times) {
                    val minute = TtItemMinuteBinding.inflate(layoutInflater, line.minutes, true).root
                    minute.text = Estonia.format("mm", departure.time)
                    minute.contentDescription = listOfNotNull(
                        TransitFormat.clock(departure.time), getString(R.string.tt_live).takeIf { departure.isRealtime },
                    ).joinToString(", ")
                    minute.setOnClickListener { openTrip(departure.tripId, departure.serviceDay) }
                    if (departure.tripId == upcoming?.tripId) {
                        // Like the first of the next departures above it.
                        minute.setBackgroundResource(R.drawable.tt_minute_bg)
                        minute.backgroundTintList =
                            if (departure.isRealtime) ColorStateList.valueOf(liveColor).withAlpha(0x29) else color
                        minute.setTextColor(if (departure.isRealtime) liveColor else Color.WHITE)
                        minute.setTypeface(minute.typeface, Typeface.BOLD)
                    } else if (departure.time < now) {
                        minute.alpha = PAST_ALPHA
                    } else if (departure.isRealtime) {
                        minute.setTextColor(liveColor)
                    }
                }
            }
        }

        // With nothing earlier to show, "upcoming" is the whole day, so the dropdown skips a step; and with
        // nothing to come, there are no upcoming hours.
        private fun next(fold: Fold) = when (fold) {
            Fold.NEXT -> if (upcoming != null) Fold.UPCOMING else Fold.FULL
            Fold.UPCOMING -> if (earlier.isNotEmpty()) Fold.FULL else Fold.NEXT
            Fold.FULL -> Fold.NEXT
        }

        private fun apply(animate: Boolean) {
            if (animate) TransitionManager.beginDelayedTransition(binding.content, foldTransition)
            item.hours.isVisible = fold != Fold.NEXT
            item.divider.isVisible = fold != Fold.NEXT
            item.longName.isVisible = fold != Fold.NEXT && route.longName.isNotEmpty()
            earlier.forEach { it.isVisible = fold == Fold.FULL }
            // Down while there's more to show, up when the next tap folds it.
            val rotation = if (next(fold) == Fold.NEXT) 180f else 0f
            if (animate) item.fold.animate().rotation(rotation).setDuration(FOLD_MS).start() else item.fold.rotation = rotation
            item.header.contentDescription = listOf(
                item.badge.text, item.headsign.text,
                getString(
                    when (next(fold)) {
                        Fold.UPCOMING -> R.string.tt_fold_show_upcoming
                        Fold.FULL -> R.string.tt_fold_show_all
                        Fold.NEXT -> R.string.tt_fold_hide
                    },
                ),
            ).joinToString(", ")
        }

        /** A tap on the route in the header: unfold it if it's folded, bring it into view and give it a nudge. */
        fun jump() {
            if (fold == Fold.NEXT) {
                fold = next(fold)
                folds[key] = fold
                apply(animate = true)
            }
            val card = item.root
            binding.scroll.post {
                binding.scroll.smoothScrollTo(0, headerLines.scrollTo(card, resources.getDimensionPixelSize(R.dimen.tt_jump_margin)))
                card.animate().scaleX(PULSE).scaleY(PULSE).setDuration(PULSE_MS).withEndAction {
                    card.animate().scaleX(1f).scaleY(1f).setDuration(PULSE_MS).start()
                }.start()
            }
        }
    }

    /**
     * The line under a route's name: how soon the next one leaves, in the route's color, then the clock times of
     * the ones after it, as many as fit ([TimesRow]); the live ones are green, with the live mark. Or that it's done
     * for the day.
     */
    private fun addNextTimes(item: TtItemRouteDayBinding, next: List<Next>, color: ColorStateList, now: Long) {
        val muted = MaterialColors.getColor(item.times, com.google.android.material.R.attr.colorOnSurfaceVariant)
        if (next.isEmpty()) {
            with(TtItemNextTimeBinding.inflate(layoutInflater, item.times, true)) {
                text.setText(R.string.tt_done_today)
                text.setTextColor(muted)
                root.backgroundTintList = color.withAlpha(0x14)
                root.foreground = null
            }
            return
        }
        val liveColor = getColor(R.color.tt_live)
        next.take(MAX_NEXT_TIMES).forEachIndexed { i, departure ->
            with(TtItemNextTimeBinding.inflate(layoutInflater, item.times, true)) {
                val clock = TransitFormat.clock(departure.time)
                val soon = if (i == 0) TransitFormat.relative(this@StopActivity, departure.time, now) else null
                text.text = when (soon) {
                    null -> clock
                    getString(R.string.tt_now) -> soon
                    else -> getString(R.string.tt_next_in, soon)
                }
                root.contentDescription = listOfNotNull(
                    text.text, clock.takeIf { soon != null }, getString(R.string.tt_live).takeIf { departure.isRealtime },
                ).joinToString(", ")
                // The first in the route's color; a live one in green instead, which the vehicle's time goes by.
                if (i == 0) {
                    root.backgroundTintList =
                        if (departure.isRealtime) ColorStateList.valueOf(liveColor).withAlpha(0x29) else color
                    text.setTextColor(if (departure.isRealtime) liveColor else Color.WHITE)
                    text.setTypeface(text.typeface, Typeface.BOLD)
                } else if (departure.isRealtime) {
                    text.setTextColor(liveColor)
                }
                Rows.liveMark(live, departure.isRealtime, liveColor)
                root.setOnClickListener { openTrip(departure.tripId, departure.serviceDay) }
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
        /** How far ahead the routes' departures get their live times: beyond, vehicles rarely report any. */
        private const val LIVE_WITHIN_S = 60 * 60

        /** How often the live times are fetched again. */
        private const val REFRESH_MS = 30_000L
        private const val SHOW_ZOOM = 17

        /** In a folded route's line; fewer show when they don't fit. */
        private const val MAX_NEXT_TIMES = 6

        /** Material's disabled-content opacity, for departures already gone. */
        private const val PAST_ALPHA = 0.38f

        private const val FADE_MS = 200L
        private const val FOLD_MS = 250L
        private const val PULSE = 1.03f
        private const val PULSE_MS = 120L

        /**
         * [pressedAt]: [OpenedScreens.now] at the tap that opens it, so its load is timed from there. [fromOsmand]:
         * opened by OsmAnd, so in a fresh task of its own, which Back leaves for OsmAnd rather than this app's home
         * screen (in this app's task, under it).
         */
        fun intent(context: Context, stopId: String, stopName: String?, pressedAt: Long? = null, fromOsmand: Boolean = false): Intent =
            Intent().setClassName(context.packageName, StopActivity::class.java.name)
                .putExtra(EXTRA_STOP_ID, stopId)
                .putExtra(EXTRA_STOP_NAME, stopName)
                .apply { if (pressedAt != null) putExtra(OpenedScreens.EXTRA_PRESSED_AT, pressedAt) }
                .apply { if (fromOsmand) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }

        fun dayLabel(context: Context, daysFromToday: Int): String = when (daysFromToday) {
            0 -> context.getString(R.string.tt_today)
            1 -> context.getString(R.string.tt_tomorrow)
            else -> Estonia.format("EEE d MMM", Estonia.dayStart(daysFromToday))
        }
    }
}
