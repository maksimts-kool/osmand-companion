package dev.maksim.companion.planner

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.color.MaterialColors
import dev.maksim.companion.core.companion
import dev.maksim.companion.core.padForSystemBars
import dev.maksim.companion.planner.databinding.PlActivitySearchBinding
import dev.maksim.companion.timetable.Rows
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Picks where from or where to: where OsmAnd has you, where it navigates to, or its map's center; a place picked
 * before; or one found by name ([Geocoder]). Answers with [EXTRA_PLACE].
 */
class PlaceSearchActivity : AppCompatActivity() {

    private lateinit var binding: PlActivitySearchBinding
    private val geocoder = Geocoder()
    private val background = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val searchLater = Runnable { search() }

    /** Answers to an older query are dropped. */
    private var request = 0
    private var osmand: OsmAndTrip.Places? = null

    private val isFrom get() = intent.getBooleanExtra(EXTRA_IS_FROM, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = PlActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        binding.back.setOnClickListener { finish() }
        binding.queryLayout.hint = getString(if (isFrom) R.string.pl_choose_from else R.string.pl_choose_to)
        binding.query.doAfterTextChanged {
            handler.removeCallbacks(searchLater)
            handler.postDelayed(searchLater, SEARCH_DELAY_MS)
        }
        binding.query.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            handler.removeCallbacks(searchLater)
            search()
            true
        }
        binding.query.requestFocus()
        showStart()
        val context = applicationContext
        background.execute {
            val places = OsmAndTrip.places(context, context.companion.osmand)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                osmand = places
                if (query().isEmpty()) showStart()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(searchLater)
        background.shutdownNow()
        super.onDestroy()
    }

    private fun query() = binding.query.text?.toString()?.trim().orEmpty()

    /** Before anything's typed: OsmAnd's places, then recent ones. */
    private fun showStart() {
        val results = binding.results
        results.removeAllViews()
        binding.progress.isVisible = false
        val primary = MaterialColors.getColor(results, androidx.appcompat.R.attr.colorPrimary)
        val fromOsmand = listOfNotNull(
            osmand?.myLocation?.let { it to R.drawable.pl_ic_my_location },
            osmand?.destination?.let { it to R.drawable.pl_ic_place },
            osmand?.mapCenter?.let { it to dev.maksim.companion.timetable.R.drawable.tt_ic_map },
        )
        if (fromOsmand.isNotEmpty()) {
            Rows.section(results, getString(R.string.pl_from_osmand))
            for ((place, icon) in fromOsmand) row(place, icon, primary)
        }
        val recent = Recents.list(this)
        if (recent.isNotEmpty()) {
            Rows.section(results, getString(R.string.pl_recent))
            val grey = MaterialColors.getColor(results, com.google.android.material.R.attr.colorOnSurfaceVariant)
            for (place in recent) row(place, R.drawable.pl_ic_history, grey)
        }
    }

    private fun search() {
        val query = query()
        if (query.length < MIN_QUERY) {
            request++
            showStart()
            return
        }
        val id = ++request
        binding.progress.isVisible = true
        val near = near()
        background.execute {
            val result = runCatching { geocoder.search(query, near?.lat, near?.lon) }
            runOnUiThread { if (id == request && !isDestroyed) show(query, result) }
        }
    }

    /** Where to look around first: the other end of the trip, else where OsmAnd is. */
    private fun near(): Place? {
        if (intent.hasExtra(EXTRA_NEAR_LAT)) {
            return Place("", intent.getDoubleExtra(EXTRA_NEAR_LAT, 0.0), intent.getDoubleExtra(EXTRA_NEAR_LON, 0.0))
        }
        return osmand?.myLocation ?: osmand?.mapCenter
    }

    private fun show(query: String, result: Result<List<Place>>) {
        val results = binding.results
        binding.progress.isVisible = false
        results.removeAllViews()
        val places = result.getOrElse {
            val message = if (it is IOException) it.message else it.toString()
            Rows.section(results, getString(R.string.pl_search_failed, message))
            return
        }
        if (places.isEmpty()) {
            Rows.section(results, getString(R.string.pl_search_empty, query))
            return
        }
        Rows.section(results, getString(R.string.pl_places))
        val grey = MaterialColors.getColor(results, com.google.android.material.R.attr.colorOnSurfaceVariant)
        for (place in places) row(place, R.drawable.pl_ic_place, grey)
    }

    private fun row(place: Place, @DrawableRes icon: Int, color: Int) = Rows.row(binding.results).run {
        time.isVisible = false
        badge.isVisible = false
        this.icon.isVisible = true
        this.icon.setImageResource(icon)
        this.icon.setBackgroundResource(dev.maksim.companion.timetable.R.drawable.tt_badge_bg)
        this.icon.backgroundTintList = ColorStateList.valueOf(color)
        title.text = place.name
        subtitle.text = place.detail
        subtitle.isVisible = !place.detail.isNullOrEmpty()
        note.text = null
        root.setOnClickListener { choose(place) }
    }

    private fun choose(place: Place) {
        Recents.add(this, place)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PLACE, place).putExtra(EXTRA_IS_FROM, isFrom))
        finish()
    }

    companion object {
        const val EXTRA_PLACE = "place"
        const val EXTRA_IS_FROM = "is_from"
        private const val EXTRA_NEAR_LAT = "near_lat"
        private const val EXTRA_NEAR_LON = "near_lon"
        private const val SEARCH_DELAY_MS = 300L
        private const val MIN_QUERY = 2

        /** Picks where from ([isFrom]) or where to, looking around [near] first. */
        fun intent(context: Context, isFrom: Boolean, near: Place?): Intent =
            Intent(context, PlaceSearchActivity::class.java).putExtra(EXTRA_IS_FROM, isFrom).apply {
                near?.let { putExtra(EXTRA_NEAR_LAT, it.lat).putExtra(EXTRA_NEAR_LON, it.lon) }
            }

        /** The place picked, from the result's [Intent]. */
        fun place(data: Intent): Place? = IntentCompat.getSerializableExtra(data, EXTRA_PLACE, Place::class.java)
    }
}
