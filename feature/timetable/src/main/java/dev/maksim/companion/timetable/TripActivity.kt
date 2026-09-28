package dev.maksim.companion.timetable

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import dev.maksim.companion.core.padForSystemBars
import dev.maksim.companion.timetable.databinding.TtActivityTripBinding
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One run of a route: every stop it calls at and when, the route's timetable seen from the vehicle. */
class TripActivity : AppCompatActivity() {

    private lateinit var binding: TtActivityTripBinding
    private val peatus = PeatusClient()
    private val background = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = TtActivityTripBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        binding.toolbar.setNavigationOnClickListener { finish() }
        load()
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
        val content = binding.content
        binding.progress.isVisible = false
        content.removeAllViews()
        val trip = result.getOrElse {
            Rows.section(content, getString(R.string.tt_load_failed, it.message))
            content.addView(MaterialButton(this).apply {
                setText(R.string.tt_retry)
                setOnClickListener { load() }
            })
            return
        }
        binding.toolbar.title = "${trip.route} → ${trip.headsign}"
        binding.toolbar.subtitle = listOf(
            trip.longName,
            Estonia.format("EEE d MMM", trip.serviceDay * 1000 + TimeUnit.HOURS.toMillis(12)),
        ).filter { it.isNotEmpty() }.joinToString(" · ")

        val now = System.currentTimeMillis()
        val fromStopId = intent.getStringExtra(EXTRA_FROM_STOP_ID)
        var fromRow: View? = null
        for (tripStop in trip.stops) {
            val time = tripStop.time(trip.serviceDay)
            Rows.row(content).run {
                this.time.text = TransitFormat.clock(time)
                badge.isVisible = false
                title.text = tripStop.stop.name
                note.text = listOfNotNull(
                    TransitFormat.relative(this@TripActivity, time, now)?.takeIf { time >= now },
                    getString(R.string.tt_live).takeIf { tripStop.isRealtime },
                ).joinToString("\n")
                if (time < now) root.alpha = 0.5f
                if (tripStop.stop.id == fromStopId) {
                    title.setTypeface(title.typeface, Typeface.BOLD)
                    this.time.setTypeface(this.time.typeface, Typeface.BOLD)
                    fromRow = root
                }
                root.setOnClickListener {
                    startActivity(StopActivity.intent(this@TripActivity, tripStop.stop.id, tripStop.stop.name))
                }
            }
        }
        // Start at the stop you came from, with a couple of stops before it in view.
        fromRow?.let { row -> binding.scroll.post { binding.scroll.scrollTo(0, maxOf(0, row.top - row.height * 2)) } }
    }

    companion object {
        private const val EXTRA_TRIP_ID = "trip_id"
        private const val EXTRA_SERVICE_DAY = "service_day"
        private const val EXTRA_FROM_STOP_ID = "from_stop_id"

        fun intent(context: Context, tripId: String, serviceDay: Long, fromStopId: String?): Intent =
            Intent(context, TripActivity::class.java)
                .putExtra(EXTRA_TRIP_ID, tripId)
                .putExtra(EXTRA_SERVICE_DAY, serviceDay)
                .putExtra(EXTRA_FROM_STOP_ID, fromStopId)
    }
}
