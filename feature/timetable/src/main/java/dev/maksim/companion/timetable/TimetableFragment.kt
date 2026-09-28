package dev.maksim.companion.timetable

import android.Manifest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import dev.maksim.companion.core.CompanionService
import dev.maksim.companion.core.canPostNotifications
import dev.maksim.companion.core.companion
import dev.maksim.companion.core.feature
import dev.maksim.companion.timetable.databinding.TtFragmentTimetableBinding
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** The on/off switch for timetables in OsmAnd, and a stop search that opens a stop's full timetable. */
class TimetableFragment : Fragment() {

    private var _binding: TtFragmentTimetableBinding? = null
    private val binding get() = _binding!!
    private val feature get() = requireContext().feature<TimetableFeature>()
    private val peatus = PeatusClient()
    private val background = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val searchLater = Runnable { search() }

    /** Answers to an older query are dropped. */
    private var request = 0

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { setEnabled(true) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = TtFragmentTimetableBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.showSwitch.isChecked = feature.isEnabled
        binding.showSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !requireContext().canPostNotifications()) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                setEnabled(checked)
            }
        }
        binding.searchInput.doAfterTextChanged {
            mainHandler.removeCallbacks(searchLater)
            mainHandler.postDelayed(searchLater, SEARCH_DELAY_MS)
        }
        binding.searchInput.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            mainHandler.removeCallbacks(searchLater)
            search()
            true
        }
    }

    override fun onStart() {
        super.onStart()
        search()
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacks(searchLater)
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    private fun setEnabled(enabled: Boolean) {
        feature.isEnabled = enabled
        CompanionService.update(requireContext())
    }

    /** Stops by name, or with no name typed, the stops around OsmAnd's map center. */
    private fun search() {
        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        if (query.isNotEmpty() && query.length < MIN_QUERY) return
        val id = ++request
        val osmand = requireContext().companion.osmand
        val loaded = feature.nearbyStops
        binding.progress.isVisible = true
        background.execute {
            val result = runCatching {
                if (query.isNotEmpty()) return@runCatching peatus.searchStops(query, MAX_RESULTS) to null
                // The feature already has them while it runs; otherwise ask OsmAnd where its map is.
                val center = osmand.takeIf { it.hasAccess }?.call("getAppInfo") { it.appInfo }?.mapLocation
                    ?: return@runCatching loaded.take(MAX_NEARBY) to null
                val stops = loaded.takeIf { it.isNotEmpty() }
                    ?: if (Estonia.contains(center.latitude, center.longitude)) {
                        peatus.nearbyStops(center.latitude, center.longitude, NEARBY_RADIUS_M, MAX_NEARBY, 4)
                    } else {
                        emptyList()
                    }
                stops.take(MAX_NEARBY) to (center.latitude to center.longitude)
            }
            activity?.runOnUiThread { if (id == request && _binding != null) show(query, result) }
        }
    }

    private fun show(query: String, result: Result<Pair<List<Stop>, Pair<Double, Double>?>>) {
        val results = binding.results
        binding.progress.isVisible = false
        results.removeAllViews()
        val (stops, center) = result.getOrElse {
            val message = if (it is IOException) it.message else it.toString()
            Rows.section(results, getString(R.string.tt_load_failed, message))
            return
        }
        Rows.section(results, if (query.isEmpty()) getString(R.string.tt_near_map) else getString(R.string.tt_search_results, query))
        if (stops.isEmpty()) {
            Rows.row(results).run {
                time.isVisible = false
                badge.isVisible = false
                title.text = if (query.isEmpty()) getString(R.string.tt_near_map_empty) else getString(R.string.tt_search_empty, query)
            }
            return
        }
        for (stop in stops) {
            val distance = center?.let { (lat, lon) ->
                getString(R.string.tt_distance_m, distanceMeters(lat, lon, stop.lat, stop.lon).roundToInt())
            }
            Rows.stop(results, stop, distance) {
                startActivity(StopActivity.intent(requireContext(), stop.id, stop.name))
            }
        }
    }

    private companion object {
        const val SEARCH_DELAY_MS = 400L
        const val MIN_QUERY = 3
        const val MAX_RESULTS = 40
        const val MAX_NEARBY = 25
        const val NEARBY_RADIUS_M = 800
    }
}
