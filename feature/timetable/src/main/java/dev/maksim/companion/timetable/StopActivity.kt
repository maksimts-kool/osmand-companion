package dev.maksim.companion.timetable

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import dev.maksim.companion.core.companion
import dev.maksim.companion.core.padForSystemBars
import dev.maksim.companion.timetable.databinding.TtActivityStopBinding
import dev.maksim.companion.timetable.databinding.TtItemHourBinding
import dev.maksim.companion.timetable.databinding.TtItemRouteDayBinding
import net.osmand.aidlapi.map.SetMapLocationParams
import java.io.IOException
import java.util.concurrent.Executors

/**
 * A stop's timetable: live next departures, then every route's times for the chosen day, printed by hour like
 * at the stop itself. Tapping a departure or a minute opens that trip ([TripActivity]).
 *
 * OsmAnd opens this from the Next departure widget, so it's exported.
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

        with(binding.toolbar) {
            title = intent.getStringExtra(EXTRA_STOP_NAME)
            setNavigationOnClickListener { finish() }
            menu.add(R.string.tt_show_in_osmand).apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                setOnMenuItemClickListener {
                    showInOsmand()
                    true
                }
            }
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
        binding.toolbar.title = stop.name
        binding.toolbar.subtitle = listOfNotNull(getString(Mode.of(stop.mode).label), stop.code).joinToString(" · ")
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
        Rows.badge(item.badge, route.route, route.mode)
        item.headsign.text = getString(R.string.tt_towards, route.headsign)
        item.longName.text = route.longName
        item.longName.isVisible = route.longName.isNotEmpty()
        val upcoming = route.times.firstOrNull { TransitFormat.serviceTime(route.serviceDay, it.first) >= now }
        val nextTime = upcoming?.let { TransitFormat.serviceTime(route.serviceDay, it.first) }
        item.header.setOnClickListener { openTrip((upcoming ?: route.times.first()).second, route.serviceDay) }

        // Service times can pass 24:00; the clock hour puts 25:10 under 01 at the end, as printed timetables do.
        val byHour = route.times.groupBy { Estonia.format("HH", TransitFormat.serviceTime(route.serviceDay, it.first)) }
        for ((hour, times) in byHour) {
            val line = TtItemHourBinding.inflate(layoutInflater, item.hours, true)
            line.hour.text = hour
            val normal = line.minutes.currentTextColor
            val past = ColorUtils.setAlphaComponent(normal, 0x66)
            val text = SpannableStringBuilder()
            for ((seconds, tripId) in times) {
                val time = TransitFormat.serviceTime(route.serviceDay, seconds)
                val start = text.length
                text.append(Estonia.format("mm", time))
                text.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = openTrip(tripId, route.serviceDay)
                    override fun updateDrawState(paint: TextPaint) {
                        paint.color = if (time < now) past else normal
                        paint.isFakeBoldText = time == nextTime
                    }
                }, start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.append("   ")
            }
            line.minutes.text = text
            line.minutes.movementMethod = LinkMovementMethod.getInstance()
        }
    }

    private fun openTrip(tripId: String, serviceDay: Long) {
        startActivity(TripActivity.intent(this, tripId, serviceDay, stopId))
    }

    /** Moves OsmAnd's map to the stop, where the timetable layer shows it, and switches to OsmAnd. */
    private fun showInOsmand() {
        val stop = stop ?: return
        val osmand = companion.osmand
        val pkg = osmand.osmandPackage ?: osmand.findInstalledOsmand()
            ?: return Toast.makeText(this, R.string.tt_osmand_missing, Toast.LENGTH_LONG).show()
        background.execute {
            val moved = osmand.hasAccess && osmand.call("setMapLocation") {
                it.setMapLocation(SetMapLocationParams(stop.lat, stop.lon, SHOW_ZOOM, 0f, false))
            } == true
            // Without API access OsmAnd still understands a geo: link.
            val intent = if (moved) packageManager.getLaunchIntentForPackage(pkg)
            else Intent(Intent.ACTION_VIEW, Uri.parse("geo:${stop.lat},${stop.lon}?z=$SHOW_ZOOM")).setPackage(pkg)
            runOnUiThread { intent?.let { startActivity(it) } }
        }
    }

    companion object {
        private const val EXTRA_STOP_ID = "stop_id"
        private const val EXTRA_STOP_NAME = "stop_name"
        private const val KEY_DAY = "day"
        private const val DAYS = 7
        private const val NEXT_DEPARTURES = 8
        private const val SHOW_ZOOM = 17

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
