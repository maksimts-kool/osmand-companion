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
import dev.maksim.companion.core.Parallel
import dev.maksim.companion.core.companion
import dev.maksim.companion.core.padForSystemBars
import dev.maksim.companion.planner.databinding.PlActivitySearchBinding
import dev.maksim.companion.timetable.Mode
import dev.maksim.companion.timetable.PeatusClient
import dev.maksim.companion.timetable.Rows
import dev.maksim.companion.timetable.distanceMeters
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Picks where from or where to: where OsmAnd has you, where it navigates to, or its map's center; a place picked
 * before; or one found by name, as Moovit lists them: cities ([Towns]), then stops ([StopIndex]), both found on the
 * phone as it's typed, then addresses and places ([Geocoder]) once they're in. Typos and missing Estonian letters
 * are forgiven ([Fuzzy]). Answers with [EXTRA_PLACE].
 */
class PlaceSearchActivity : AppCompatActivity() {

    private lateinit var binding: PlActivitySearchBinding
    private val geocoder = Geocoder()
    private val peatus = PeatusClient()

    /** Cities and stops, on the phone; addresses, online; the stop list, fetched now and then and slow then. */
    private val background = Executors.newSingleThreadExecutor()
    private val remote = Executors.newSingleThreadExecutor()
    private val loader = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val searchLater = Runnable { search() }

    /** Answers to an older query are dropped. */
    private var request = 0
    private var osmand: OsmAndTrip.Places? = null

    /** What's found for the query being shown: on the phone ([local]) and online ([online], null until it's in). */
    private var local: Local? = null
    private var online: Result<Online>? = null

    private class Local(val towns: List<Found>, val stops: List<Found>?)
    private class Online(val places: List<Found>, val stops: List<Found>)

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
        loader.execute {
            val loaded = runCatching { StopIndex.load(context) }.isSuccess
            // Stops typed for before the list was in were looked for online; now they can be found properly.
            runOnUiThread { if (loaded && !isDestroyed && query().length >= MIN_QUERY) search() }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(searchLater)
        background.shutdownNow()
        remote.shutdownNow()
        loader.shutdown()
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
        local = null
        online = null
        binding.progress.isVisible = true
        val near = near()
        val context = applicationContext
        background.execute {
            val fuzzy = Fuzzy.Query(query)
            val towns = Towns.of(context).search(fuzzy, near, MAX_TOWNS)
            val stops = StopIndex.loaded()?.search(fuzzy, near, MAX_STOPS)
            runOnUiThread {
                if (id != request || isDestroyed) return@runOnUiThread
                local = Local(towns, stops)
                show(query)
            }
        }
        remote.execute {
            if (id != request) return@execute
            val result = runCatching {
                val stops = if (StopIndex.loaded() == null) Parallel.submit { stopsOnline(context, query) } else null
                val places = geocoder.search(query, near?.lat, near?.lon)
                Online(places, stops?.let { runCatching { Parallel.await(it) }.getOrNull() }.orEmpty())
            }
            runOnUiThread {
                if (id != request || isDestroyed) return@runOnUiThread
                online = result
                show(query)
            }
        }
    }

    /** peatus.ee's own stop search, for while there's no [StopIndex] yet: only names containing what's typed. */
    private fun stopsOnline(context: Context, query: String): List<Found> =
        StopIndex.found(
            peatus.searchStops(query, MAX_STOPS * 3).map { StopIndex.Stop(it.name, it.lat, it.lon, it.mode, it.routes) },
            Towns.of(context),
        ).take(MAX_STOPS)

    /** Where to look around first: the other end of the trip, else where OsmAnd is. */
    private fun near(): Place? {
        if (intent.hasExtra(EXTRA_NEAR_LAT)) {
            return Place("", intent.getDoubleExtra(EXTRA_NEAR_LAT, 0.0), intent.getDoubleExtra(EXTRA_NEAR_LON, 0.0))
        }
        return osmand?.myLocation ?: osmand?.mapCenter
    }

    /** What's found so far for [query], in sections: cities, stops, then addresses and places. */
    private fun show(query: String) {
        val results = binding.results
        results.removeAllViews()
        val local = local
        val online = online
        binding.progress.isVisible = local == null || online == null
        val places = online?.getOrNull()?.places.orEmpty()
        val localTowns = local?.towns.orEmpty()
        // Villages and the like that aren't bundled come from online, after the bundled ones.
        val towns = (localTowns + places.filter { it.kind.section == Found.Section.TOWNS }
            .filter { found -> localTowns.none { same(it, found) } }).take(MAX_TOWNS)
        val stops = local?.stops ?: online?.getOrNull()?.stops.orEmpty()
        val addresses = places.filter { it.kind.section == Found.Section.ADDRESSES }
        val here = osmand?.myLocation
        val primary = MaterialColors.getColor(results, androidx.appcompat.R.attr.colorPrimary)
        val grey = MaterialColors.getColor(results, com.google.android.material.R.attr.colorOnSurfaceVariant)
        if (towns.isNotEmpty()) {
            Rows.section(results, getString(R.string.pl_towns))
            for (found in towns) row(found, R.drawable.pl_ic_city, primary, here)
        }
        if (stops.isNotEmpty()) {
            Rows.section(results, getString(R.string.pl_stops))
            for (found in stops) Mode.of(found.mode).let { row(found, it.icon, it.color, here) }
        }
        if (addresses.isNotEmpty()) {
            Rows.section(results, getString(R.string.pl_places))
            for (found in addresses) row(found, R.drawable.pl_ic_place, grey, here)
        }
        online?.exceptionOrNull()?.let {
            val message = if (it is IOException) it.message else it.toString()
            Rows.section(results, getString(R.string.pl_search_failed, message))
        }
        if (local != null && online?.isSuccess == true && towns.isEmpty() && stops.isEmpty() && addresses.isEmpty()) {
            Rows.section(results, getString(R.string.pl_search_empty, query))
        }
    }

    /** The same town found twice: same name, a few km apart at most. */
    private fun same(a: Found, b: Found) =
        Fuzzy.fold(a.place.name) == Fuzzy.fold(b.place.name) && a.place.isNear(b.place, SAME_TOWN_M)

    /** "City", "District · Tallinn", "Bus · 5, 18, 36 · Lasnamäe, Tallinn", or the address's place. */
    private fun subtitle(found: Found): String? {
        val detail = found.place.detail
        val kind = when (found.kind) {
            Found.Kind.CITY -> R.string.pl_kind_city
            Found.Kind.TOWN -> R.string.pl_kind_town
            Found.Kind.VILLAGE -> R.string.pl_kind_village
            Found.Kind.DISTRICT -> R.string.pl_kind_district
            Found.Kind.STOP -> Mode.of(found.mode).label
            Found.Kind.ADDRESS -> return detail
        }
        val lines = found.lines.take(MAX_LINES).joinToString(", ") + if (found.lines.size > MAX_LINES) ", …" else ""
        return listOf(getString(kind), lines, detail.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** [found] as a row, with how far it is from [here] if known. */
    private fun row(found: Found, @DrawableRes icon: Int, color: Int, here: Place?) {
        val distance = here?.let { ItineraryViews.distance(this, distanceMeters(it.lat, it.lon, found.place.lat, found.place.lon)) }
        row(found.place.copy(detail = subtitle(found)), icon, color, distance)
    }

    private fun row(place: Place, @DrawableRes icon: Int, color: Int, note: String? = null) = Rows.row(binding.results).run {
        time.isVisible = false
        badge.isVisible = false
        this.icon.isVisible = true
        this.icon.setImageResource(icon)
        this.icon.setBackgroundResource(dev.maksim.companion.timetable.R.drawable.tt_badge_bg)
        this.icon.backgroundTintList = ColorStateList.valueOf(color)
        title.text = place.name
        subtitle.text = place.detail
        subtitle.isVisible = !place.detail.isNullOrEmpty()
        this.note.text = note
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
        private const val MAX_TOWNS = 4
        private const val MAX_STOPS = 6
        private const val MAX_LINES = 8
        private const val SAME_TOWN_M = 5_000.0

        /** Picks where from ([isFrom]) or where to, looking around [near] first. */
        fun intent(context: Context, isFrom: Boolean, near: Place?): Intent =
            Intent(context, PlaceSearchActivity::class.java).putExtra(EXTRA_IS_FROM, isFrom).apply {
                near?.let { putExtra(EXTRA_NEAR_LAT, it.lat).putExtra(EXTRA_NEAR_LON, it.lon) }
            }

        /** The place picked, from the result's [Intent]. */
        fun place(data: Intent): Place? = IntentCompat.getSerializableExtra(data, EXTRA_PLACE, Place::class.java)
    }
}
