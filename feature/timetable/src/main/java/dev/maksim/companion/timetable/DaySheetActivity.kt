package dev.maksim.companion.timetable

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import dev.maksim.companion.timetable.databinding.TtActivityDaySheetBinding
import dev.maksim.companion.timetable.databinding.TtItemSheetMessageBinding
import dev.maksim.companion.timetable.databinding.TtItemSheetRouteBinding
import dev.maksim.companion.timetable.databinding.TtItemSheetTimeBinding
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The Full day button in a stop's OsmAnd menu opens this over OsmAnd's map: the rest of today by route, in
 * OsmAnd's colors and day or night look, so it reads as part of OsmAnd. (OsmAnd's API can only put text rows in
 * its own menu.) It runs in a task of its own, so closing it goes straight back to OsmAnd rather than to this app.
 * Tapping a time opens that trip, and Full timetable the stop's timetable, in its place ([leaveFor]).
 */
class DaySheetActivity : AppCompatActivity() {

    private lateinit var binding: TtActivityDaySheetBinding
    private lateinit var behavior: BottomSheetBehavior<View>
    private val peatus = PeatusClient()
    private val background = Executors.newSingleThreadExecutor()

    private lateinit var stopId: String
    private var stopName: String? = null

    /** Answers to a load that's been superseded by Refresh are dropped. */
    private var request = 0

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
        header(stopName, Mode.of(intent.getStringExtra(EXTRA_MODE)))
        binding.subtitle.setText(R.string.tt_rest_of_today)

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
        binding.fullTimetable.setOnClickListener { leaveFor(StopActivity.intent(this, stopId, stopName)) }
    }

    /** Also on turning the screen back on, so "in 6 min" is right. */
    override fun onStart() {
        super.onStart()
        load()
    }

    override fun onResume() {
        super.onResume()
        resumedStopId = stopId
    }

    override fun onPause() {
        resumedStopId = null
        super.onPause()
    }

    override fun onDestroy() {
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
        background.execute {
            val result = runCatching { peatus.timetable(stopId, Estonia.serviceDate()) ?: throw IOException(unknown) }
            runOnUiThread { if (id == request && !isDestroyed) show(result, System.currentTimeMillis()) }
        }
    }

    private fun show(result: Result<Pair<Stop, List<RouteDay>>>, now: Long) {
        binding.progress.isVisible = false
        binding.content.removeAllViews()
        val (stop, routes) = result.getOrElse {
            message(getString(R.string.tt_load_failed, it.message))
            return
        }
        stopName = stop.name
        header(stop.name, Mode.of(stop.mode))
        binding.subtitle.text = getString(R.string.tt_sheet_subtitle, TransitFormat.clock(now))
        var shown = 0
        for (route in routes) {
            val left = route.times.filter { TransitFormat.serviceTime(route.serviceDay, it.first) >= now - GRACE_MS }
            if (left.isEmpty()) continue
            addRoute(route, left, now)
            shown++
        }
        if (shown == 0) message(getString(R.string.tt_no_more_today))
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

    private fun message(text: String) {
        TtItemSheetMessageBinding.inflate(layoutInflater, binding.content, true).root.text = text
    }

    companion object {
        private const val EXTRA_STOP_ID = "stop_id"
        private const val EXTRA_STOP_NAME = "stop_name"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_NIGHT = "night"

        /** Of the screen's height. */
        private const val MAX_HEIGHT = 0.75f

        /** A departure a few seconds past is probably still at the stop. */
        private const val GRACE_MS = 30_000L

        /** The stop whose sheet is on screen, if any; tells [TimetableFeature] whether Android let it open this. */
        @Volatile
        var resumedStopId: String? = null
            private set

        /**
         * Opens the sheet for [stop] in a fresh task of its own, over whatever is in front (OsmAnd). [night] is
         * whether OsmAnd looks dark right now; null follows the phone.
         */
        fun intent(context: Context, stop: Stop, night: Boolean?): Intent =
            Intent().setClassName(context.packageName, DaySheetActivity::class.java.name)
                .putExtra(EXTRA_STOP_ID, stop.id)
                .putExtra(EXTRA_STOP_NAME, stop.name)
                .putExtra(EXTRA_MODE, stop.mode)
                .apply { if (night != null) putExtra(EXTRA_NIGHT, night) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
