package dev.maksim.companion.timetable

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
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
 * at the stop itself. Tapping a departure or a minute opens that trip ([TripActivity]).
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
            load()
        }
    }

    /** Also on returning here, so the next departures are fresh. */
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

    private fun show(result: Result<Triple<Stop, List<RouteDay>, List<Departure>?>>) {
        val content = binding.content
        binding.progress.isVisible = false
        content.removeAllViews()
        val (stop, routes, next) = result.getOrElse {
            Rows.section(content, getString(R.string.tt_load_failed, it.message))
            content.addView(MaterialButton(this).apply {
                setText(R.string.tt_retry)
                setOnClickListener { load() }
            })
            return
        }
        this.stop = stop
        val mode = Mode.of(stop.mode)
        val served = routes.map { it.route }.distinct().sortedWith(RouteOrder).joinToString(", ")
        val about = listOfNotNull(stop.code, served.ifEmpty { null }).joinToString(" · ")
        Rows.header(binding.header, mode, null, stop.name, about)
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
        if (routes.isEmpty()) Rows.row(content).run {
            time.isVisible = false
            badge.isVisible = false
            title.setText(R.string.tt_no_departures_day)
        }
        routes.forEach { addRoute(it, now) }
    }

    private fun addRoute(route: RouteDay, now: Long) {
        val item = TtItemRouteDayBinding.inflate(layoutInflater, binding.content, true)
        val color = ColorStateList.valueOf(Mode.of(route.mode).color)
        Rows.badge(item.badge, route.route, route.mode)
        item.headsign.text = getString(R.string.tt_towards, route.headsign)
        item.longName.text = route.longName
        item.longName.isVisible = route.longName.isNotEmpty()
        val upcoming = route.times.firstOrNull { TransitFormat.serviceTime(route.serviceDay, it.first) >= now }
        val nextTime = upcoming?.let { TransitFormat.serviceTime(route.serviceDay, it.first) }
        item.header.setOnClickListener { openTrip((upcoming ?: route.times.first()).second, route.serviceDay) }
        val soon = nextTime?.let { TransitFormat.relative(this, it, now) }
        item.next.text = if (soon == getString(R.string.tt_now)) soon else getString(R.string.tt_next_in, soon)
        item.next.backgroundTintList = color.withAlpha(0x33)
        item.next.isVisible = soon != null

        // Service times can pass 24:00; the clock hour puts 25:10 under 01 at the end, as printed timetables do.
        val hourOf = { seconds: Int -> Estonia.format("HH", TransitFormat.serviceTime(route.serviceDay, seconds)) }
        val nextHour = upcoming?.let { hourOf(it.first) }
        for ((hour, times) in route.times.groupBy { hourOf(it.first) }) {
            val line = TtItemHourBinding.inflate(layoutInflater, item.hours, true)
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

        /** The stop on screen, if any; tells [TimetableFeature] whether Android let it open this. */
        @Volatile
        var resumedStopId: String? = null
            private set

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
