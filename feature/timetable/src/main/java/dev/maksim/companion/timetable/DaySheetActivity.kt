package dev.maksim.companion.timetable

import android.content.Context
import android.content.Intent
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.os.LocaleList
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import dev.maksim.companion.timetable.databinding.TtActivityDaySheetBinding
import dev.maksim.companion.timetable.databinding.TtItemSheetDepartureBinding
import dev.maksim.companion.timetable.databinding.TtItemSheetRouteBinding
import dev.maksim.companion.timetable.databinding.TtItemSheetTimeBinding
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The Next departures and Full day buttons in a stop's OsmAnd menu open this over OsmAnd's map: the stop's next
 * departures, live where peatus.ee has them, soonest first ([showsNext]); or the rest of today by route, the route
 * leaving soonest first. It's in OsmAnd's colors, day or night look and language, so it reads as part of OsmAnd.
 * (OsmAnd's API can only put text rows in its own menu.) It runs in a task of its own, so closing it goes straight
 * back to OsmAnd rather than to this app. Tapping a departure or a time opens that trip, and Full timetable the
 * stop's timetable, in its place ([leaveFor]).
 */
class DaySheetActivity : AppCompatActivity() {

    private lateinit var binding: TtActivityDaySheetBinding
    private lateinit var behavior: BottomSheetBehavior<View>
    private val peatus = PeatusClient()
    private val background = Executors.newSingleThreadExecutor()

    private lateinit var stopId: String
    private var stopName: String? = null

    /** The next departures rather than the rest of today. */
    private var showsNext = false

    /** What a load brings: the stop, with its next departures when [showsNext], else with [routes]. */
    private class Loaded(val stop: Stop, val routes: List<RouteDay>?)

    /** Answers to a load that's been superseded by Refresh are dropped. */
    private var request = 0

    /** Turns the Refresh icon while loading. */
    private val spin by lazy {
        ObjectAnimator.ofFloat(binding.refresh, View.ROTATION, 0f, 360f).apply {
            duration = SPIN_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            doOnEnd { repeatCount = ValueAnimator.INFINITE }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(locales?.let { OsmAndStopUi.localized(newBase, it) } ?: newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before the views are made, so the colors come from values-night when OsmAnd is dark. The manifest says
        // it handles uiMode, or AppCompat would recreate the activity for this.
        if (intent.hasExtra(EXTRA_NIGHT)) {
            delegate.localNightMode =
                if (intent.getBooleanExtra(EXTRA_NIGHT, false)) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
        }
        super.onCreate(savedInstanceState)
        binding = TtActivityDaySheetBinding.inflate(layoutInflater)
        setContentView(binding.root)
        stopId = intent.getStringExtra(EXTRA_STOP_ID) ?: return finish()
        stopName = intent.getStringExtra(EXTRA_STOP_NAME)
        showsNext = intent.getBooleanExtra(EXTRA_NEXT, false)
        header(stopName, Mode.of(intent.getStringExtra(EXTRA_MODE)))
        binding.subtitle.setText(if (showsNext) R.string.tt_next_departures else R.string.tt_rest_of_today)

        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightNavigationBars = !night
        val buttonsBottom = binding.buttons.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.sheet.updatePadding(left = bars.left, right = bars.right)
            binding.buttons.updatePadding(bottom = buttonsBottom + bars.bottom)
            insets
        }

        behavior = BottomSheetBehavior.from(binding.sheet)
        // Leaves the stop in view above it, like OsmAnd's own menus.
        behavior.maxHeight = (resources.displayMetrics.heightPixels * MAX_HEIGHT).toInt()
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(sheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_HIDDEN -> finish()
                    // The routes may have come in while it slid up, and it stopped where the shorter sheet would.
                    BottomSheetBehavior.STATE_EXPANDED -> sheet.requestLayout()
                }
            }

            override fun onSlide(sheet: View, slideOffset: Float) {
                binding.scrim.alpha = (1 + slideOffset).coerceIn(0f, 1f)
            }
        })
        // Slides up from below once it's been laid out, and after the sheet's state is restored, if it was.
        if (savedInstanceState == null) behavior.state = BottomSheetBehavior.STATE_HIDDEN
        binding.root.post {
            if (behavior.state == BottomSheetBehavior.STATE_EXPANDED) binding.scrim.alpha = 1f
            else behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        binding.scrim.setOnClickListener { dismiss() }
        binding.close.setOnClickListener { dismiss() }
        onBackPressedDispatcher.addCallback(this) { dismiss() }
        binding.refresh.setOnClickListener { load() }
        States.loading(binding.content, getColor(R.color.tt_osm_accent))
        binding.fullTimetable.setOnClickListener { leaveFor(StopActivity.intent(this, stopId, stopName)) }
    }

    /** Also on turning the screen back on, so "in 6 min" is right. */
    override fun onStart() {
        super.onStart()
        load()
    }

    override fun onResume() {
        super.onResume()
        OpenedScreens.resumed(this, if (showsNext) OpenedScreens.Screen.NEXT_SHEET else OpenedScreens.Screen.DAY_SHEET, stopId)
    }

    override fun onDestroy() {
        spin.cancel()
        background.shutdownNow()
        super.onDestroy()
    }

    /**
     * Opens one of this app's screens in this task, and closes the sheet: Back from there goes to OsmAnd. Coming
     * back to the sheet instead would show a black map behind it, as OsmAnd only draws its map again once it's in
     * front itself.
     */
    private fun leaveFor(intent: Intent) {
        startActivity(intent)
        finish()
    }

    private fun dismiss() {
        behavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun header(name: String?, mode: Mode) {
        binding.title.text = name
        binding.icon.setImageResource(mode.icon)
        binding.icon.backgroundTintList = ColorStateList.valueOf(mode.color)
        binding.icon.contentDescription = getString(mode.label)
    }

    private fun load() {
        val id = ++request
        val unknown = getString(R.string.tt_stop_unknown)
        binding.progress.isVisible = true
        if (!spin.isStarted) spin.start()
        background.execute {
            val result = runCatching {
                if (showsNext) Loaded(peatus.stopWithin(stopId, NEXT_WITHIN_S, NEXT_DEPARTURES) ?: throw IOException(unknown), null)
                else peatus.timetable(stopId, Estonia.serviceDate())?.let { (stop, routes) -> Loaded(stop, routes) }
                    ?: throw IOException(unknown)
            }
            runOnUiThread { if (id == request && !isDestroyed) show(result, System.currentTimeMillis()) }
        }
    }

    private fun show(result: Result<Loaded>, now: Long) {
        binding.progress.isVisible = false
        // Finishes the turn it's on rather than stopping at an angle.
        spin.repeatCount = 0
        binding.content.removeAllViews()
        val loaded = result.getOrElse {
            States.error(binding.content, getString(R.string.tt_load_failed, it.message)) { load() }
            return
        }
        val stop = loaded.stop
        stopName = stop.name
        header(stop.name, Mode.of(stop.mode))
        val clock = TransitFormat.clock(now)
        if (loaded.routes == null) {
            binding.subtitle.text = getString(R.string.tt_sheet_subtitle_next, clock)
            showNext(stop.departures, now)
        } else {
            binding.subtitle.text = getString(R.string.tt_sheet_subtitle, clock)
            showDay(loaded.routes, now)
        }
    }

    private fun showNext(departures: List<Departure>, now: Long) {
        // Only the soonest few, and only in the next hour: further off, the Full day sheet says it better.
        val upcoming = departures.filter { it.time >= now - GRACE_MS && it.time < now + NEXT_WITHIN_S * 1000L }
        upcoming.forEach { addDeparture(it, now) }
        if (upcoming.isEmpty()) States.empty(binding.content, getString(R.string.tt_no_departures_hour))
    }

    private fun addDeparture(departure: Departure, now: Long) {
        val item = TtItemSheetDepartureBinding.inflate(layoutInflater, binding.content, true)
        val minutes = ((departure.time - now) / 60_000).toInt()
        val accent = getColor(R.color.tt_osm_accent)
        // Leaving now: the board lights up.
        val leaving = minutes < 1
        item.countdown.backgroundTintList = ColorStateList.valueOf(if (leaving) accent else ColorUtils.setAlphaComponent(accent, 0x1F))
        item.minutes.setTextColor(if (leaving) getColor(R.color.tt_osm_on_accent) else accent)
        item.unit.isVisible = !leaving
        item.minutes.text = if (leaving) getString(R.string.tt_now) else minutes.toString()
        item.minutes.textSize = if (leaving) BOARD_NOW_TEXT_SP else BOARD_TEXT_SP

        Rows.badge(item.badge, departure.route, departure.mode)
        item.headsign.text = departure.headsign
        val delay = (departure.expected - departure.scheduled) / 60
        item.time.text = listOfNotNull(
            TransitFormat.clockWithDay(departure.time, now),
            getString(R.string.tt_live).takeIf { departure.isRealtime },
            when {
                !departure.isRealtime -> null
                delay >= 1 -> getString(R.string.tt_late, delay)
                delay <= -1 -> getString(R.string.tt_early, -delay)
                else -> null
            },
        ).joinToString(" · ")
        item.time.setCompoundDrawablesRelativeWithIntrinsicBounds(if (departure.isRealtime) R.drawable.tt_ic_live else 0, 0, 0, 0)
        item.root.contentDescription = listOfNotNull(
            departure.route, departure.headsign, TransitFormat.relative(this, departure.time, now), item.time.text,
        ).joinToString(", ")
        item.root.setOnClickListener {
            leaveFor(TripActivity.intent(this, departure.tripId, departure.serviceDay, stopId))
        }
    }

    private fun showDay(routes: List<RouteDay>, now: Long) {
        val left = routes.map { route ->
            route to route.times.filter { TransitFormat.serviceTime(route.serviceDay, it.first) >= now - GRACE_MS }
        }.filter { it.second.isNotEmpty() }
        left.sortedBy { (route, times) -> TransitFormat.serviceTime(route.serviceDay, times.first().first) }
            .forEach { (route, times) -> addRoute(route, times, now) }
        if (left.isEmpty()) States.empty(binding.content, getString(R.string.tt_no_more_today))
    }

    private fun addRoute(route: RouteDay, left: List<Pair<Int, String>>, now: Long) {
        val item = TtItemSheetRouteBinding.inflate(layoutInflater, binding.content, true)
        Rows.badge(item.badge, route.route, route.mode)
        item.headsign.text = getString(R.string.tt_towards, route.headsign)
        val soon = TransitFormat.relative(this, TransitFormat.serviceTime(route.serviceDay, left.first().first), now)
        item.next.text = if (soon == getString(R.string.tt_now)) soon else getString(R.string.tt_next_in, soon)
        item.next.isVisible = soon != null

        val accent = ColorStateList.valueOf(getColor(R.color.tt_osm_accent))
        left.forEachIndexed { i, (seconds, tripId) ->
            TtItemSheetTimeBinding.inflate(layoutInflater, item.times, true).root.run {
                text = TransitFormat.clock(TransitFormat.serviceTime(route.serviceDay, seconds))
                if (i == 0) {
                    backgroundTintList = accent
                    setTextColor(getColor(R.color.tt_osm_on_accent))
                    setTypeface(typeface, Typeface.BOLD)
                }
                setOnClickListener { leaveFor(TripActivity.intent(this@DaySheetActivity, tripId, route.serviceDay, stopId)) }
            }
        }
    }

    companion object {
        private const val EXTRA_STOP_ID = "stop_id"
        private const val EXTRA_STOP_NAME = "stop_name"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_NIGHT = "night"
        private const val EXTRA_NEXT = "next"

        /**
         * The Next departures sheet shows those leaving in the next [NEXT_WITHIN_S] seconds, at most
         * [NEXT_DEPARTURES] of them: at a busy stop, the list ends well before the hour is up.
         */
        private const val NEXT_WITHIN_S = 60 * 60
        private const val NEXT_DEPARTURES = 12

        private const val BOARD_TEXT_SP = 24f
        private const val BOARD_NOW_TEXT_SP = 16f

        /** Of the screen's height. */
        private const val MAX_HEIGHT = 0.75f

        /** A departure a few seconds past is probably still at the stop. */
        private const val GRACE_MS = 30_000L

        private const val SPIN_MS = 800L

        /**
         * OsmAnd's language, for the sheets opened from now on; null is this app's. Not an intent extra: the
         * language has to be set in [attachBaseContext], before the intent is there.
         */
        @Volatile
        var locales: LocaleList? = null

        /**
         * Opens the sheet for [stop], with its [next] departures or else the rest of today, in a fresh task of its
         * own, over whatever is in front (OsmAnd). [night] is whether OsmAnd looks dark right now; null follows the
         * phone.
         */
        fun intent(context: Context, stop: Stop, next: Boolean, night: Boolean?): Intent =
            Intent().setClassName(context.packageName, DaySheetActivity::class.java.name)
                .putExtra(EXTRA_STOP_ID, stop.id)
                .putExtra(EXTRA_STOP_NAME, stop.name)
                .putExtra(EXTRA_MODE, stop.mode)
                .putExtra(EXTRA_NEXT, next)
                .apply { if (night != null) putExtra(EXTRA_NIGHT, night) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
