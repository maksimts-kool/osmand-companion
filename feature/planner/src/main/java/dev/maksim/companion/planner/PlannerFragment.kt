package dev.maksim.companion.planner

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.BundleCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import dev.maksim.companion.core.Analytics
import dev.maksim.companion.core.companion
import dev.maksim.companion.planner.databinding.PlFragmentPlannerBinding
import dev.maksim.companion.planner.databinding.PlItemWayGroupBinding
import dev.maksim.companion.timetable.Estonia
import dev.maksim.companion.timetable.OsmAndStopUi
import dev.maksim.companion.timetable.Rows
import dev.maksim.companion.timetable.States
import dev.maksim.companion.timetable.TransitFormat
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * The Trips tab: where from and where to (OsmAnd's location, its destination, or a search), when, and the ways there,
 * on live times ([TripPlanner]), refreshed every [REFRESH_MS] while it's on screen. Opened from OsmAnd ([open]), it
 * takes where to go from there.
 */
class PlannerFragment : Fragment() {

    private var _binding: PlFragmentPlannerBinding? = null
    private val binding get() = _binding!!
    private val background = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var planner: TripPlanner

    private var from: Place? = null
    private var to: Place? = null

    /** When to leave or arrive by, epoch ms; null: now. */
    private var time: Long? = null
    private var arriveBy = false

    /** What's on screen, and when it was planned. */
    private var shown: List<Itinerary> = emptyList()
    private var plannedAt = 0L

    /** The plan on screen, and what it was asked for, so a refresh can only [TripPlanner.again] it. */
    private var lastPlan: TripPlanner.Result? = null
    private var lastAsked: List<Any?>? = null

    /** Answers to an older plan are dropped. */
    private var request = 0

    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data?.takeIf { result.resultCode == Activity.RESULT_OK } ?: return@registerForActivityResult
        val place = PlaceSearchActivity.place(data) ?: return@registerForActivityResult
        if (data.getBooleanExtra(PlaceSearchActivity.EXTRA_IS_FROM, false)) from = place else to = place
        showPlaces()
        plan()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (from != null && to != null) refresh()
            showTrip()
            mainHandler.postDelayed(this, REFRESH_MS)
        }
    }

    private val tripChanged = Runnable { if (_binding != null) showTrip() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        planner = TripPlanner()
        savedInstanceState?.let { state ->
            from = BundleCompat.getSerializable(state, KEY_FROM, Place::class.java)
            to = BundleCompat.getSerializable(state, KEY_TO, Place::class.java)
            time = state.getLong(KEY_TIME, 0L).takeIf { it != 0L }
            arriveBy = state.getBoolean(KEY_ARRIVE_BY)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = PlFragmentPlannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.fromRow.setOnClickListener { choose(isFrom = true) }
        binding.toRow.setOnClickListener { choose(isFrom = false) }
        binding.swap.setOnClickListener {
            from = to.also { to = from }
            showPlaces()
            plan()
        }
        binding.leaveNow.setOnClickListener {
            time = null
            arriveBy = false
            showWhen()
            plan()
        }
        binding.departAt.setOnClickListener { pickTime(arrive = false) }
        binding.arriveBy.setOnClickListener { pickTime(arrive = true) }
        binding.refresh.setOnClickListener { plan() }
        binding.tripOpen.setOnClickListener { startActivity(LiveTripActivity.intent(requireContext())) }
        binding.tripCard.setOnClickListener { startActivity(LiveTripActivity.intent(requireContext())) }
        binding.tripStop.setOnClickListener { TripStore.stop(requireContext(), arrived = false) }
        TripStore.addListener(tripChanged)
        showTrip()
        showPlaces()
        showWhen()
        if (!consumeLink()) {
            if (from == null || to == null) fillFromOsmAnd() else plan()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) startTicking()
        consumeLink()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(tick)
        super.onPause()
    }

    /** The home screen shows one tab at a time by hiding the others. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            mainHandler.removeCallbacks(tick)
        } else {
            if (!consumeLink()) plan(quiet = true)
            startTicking()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(KEY_FROM, from)
        outState.putSerializable(KEY_TO, to)
        outState.putLong(KEY_TIME, time ?: 0L)
        outState.putBoolean(KEY_ARRIVE_BY, arriveBy)
    }

    override fun onDestroyView() {
        TripStore.removeListener(tripChanged)
        mainHandler.removeCallbacksAndMessages(null)
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    /** Every [REFRESH_MS]; straight away if what's on screen is older than that (back from another screen). */
    private fun startTicking() {
        mainHandler.removeCallbacks(tick)
        val age = System.currentTimeMillis() - plannedAt
        mainHandler.postDelayed(tick, if (shown.isNotEmpty() && age >= REFRESH_MS) 0 else REFRESH_MS)
    }

    private fun choose(isFrom: Boolean) {
        val near = (if (isFrom) to else from) ?: (if (isFrom) from else to)
        pick.launch(PlaceSearchActivity.intent(requireContext(), isFrom, near))
    }

    /**
     * Fills in what's still empty from OsmAnd, then plans: from where OsmAnd has you (or its map, without a fix), to
     * where it navigates, if it does. With [fromHere], from where OsmAnd has you even if somewhere else was picked; with
     * [takeDestination], to OsmAnd's destination likewise. Just after this app starts, OsmAnd may not be connected
     * yet: then it tries again a few times ([tries]).
     */
    private fun fillFromOsmAnd(fromHere: Boolean = false, takeDestination: Boolean = false, tries: Int = OSMAND_TRIES) {
        val context = requireContext().applicationContext
        val osmand = context.companion.osmand
        background.execute {
            val places = OsmAndTrip.places(context, osmand)
            mainHandler.post {
                if (_binding == null) return@post
                if (places == null && tries > 1) {
                    mainHandler.postDelayed({ if (_binding != null) fillFromOsmAnd(fromHere, takeDestination, tries - 1) }, OSMAND_RETRY_MS)
                    return@post
                }
                if (from == null || fromHere) from = places?.myLocation ?: from ?: places?.mapCenter
                if (to == null || takeDestination) to = places?.destination ?: to
                showPlaces()
                plan()
            }
        }
    }

    /** For the home screen, after [open]: takes the link now if the tab is on screen (it does on showing otherwise). */
    fun linkArrived() {
        if (isAdded && !isHidden) consumeLink()
    }

    /** Takes where to go from a link OsmAnd opened ([open]); true if there was one. */
    private fun consumeLink(): Boolean {
        if (_binding == null) return false
        val uri = pending ?: return false
        pending = null
        val lat = uri.getQueryParameter(OsmAndStopUi.PARAM_LAT)?.toDoubleOrNull()
        val lon = uri.getQueryParameter(OsmAndStopUi.PARAM_LON)?.toDoubleOrNull()
        val place = if (lat != null && lon != null) {
            Place(uri.getQueryParameter(OsmAndStopUi.PARAM_NAME) ?: getString(R.string.pl_to), lat, lon)
        } else {
            null
        }
        place?.let { to = it }
        time = null
        arriveBy = false
        showWhen()
        // From where you are, as it's OsmAnd that's asking.
        fillFromOsmAnd(fromHere = true, takeDestination = place == null)
        return true
    }

    /** The trip being taken, if there is one, at the top. */
    private fun showTrip() {
        val context = requireContext()
        val trip = TripStore.current(context)
        binding.tripCard.visibility = if (trip == null) View.GONE else View.VISIBLE
        trip ?: return
        val now = System.currentTimeMillis()
        val texts = TripNotifications.texts(context, trip, TripProgress.at(trip.itinerary, now, trip.reached), now)
        binding.tripDestination.text = getString(R.string.pl_trip_banner, trip.destination.name)
        binding.tripStep.text = texts.title
        binding.tripText.text = texts.text
        binding.tripText.visibility = if (texts.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showPlaces() {
        fun show(view: android.widget.TextView, place: Place?, empty: Int) {
            view.text = place?.name ?: getString(empty)
            view.setTextColor(
                MaterialColors.getColor(
                    view,
                    if (place == null) com.google.android.material.R.attr.colorOnSurfaceVariant
                    else com.google.android.material.R.attr.colorOnSurface,
                ),
            )
        }
        show(binding.fromText, from, R.string.pl_choose_from)
        show(binding.toText, to, R.string.pl_choose_to)
    }

    private fun showWhen() {
        val time = time
        val now = System.currentTimeMillis()
        binding.leaveNow.isChecked = time == null
        binding.departAt.isChecked = time != null && !arriveBy
        binding.arriveBy.isChecked = time != null && arriveBy
        binding.departAt.text = if (time != null && !arriveBy) {
            getString(R.string.pl_depart_at, TransitFormat.clockWithDay(time, now))
        } else {
            getString(R.string.pl_depart_pick)
        }
        binding.arriveBy.text = if (time != null && arriveBy) {
            getString(R.string.pl_arrive_by, TransitFormat.clockWithDay(time, now))
        } else {
            getString(R.string.pl_arrive_pick)
        }
    }

    /** A time today, or tomorrow if that's more than a little while ago. */
    private fun pickTime(arrive: Boolean) {
        val chosen = time ?: System.currentTimeMillis()
        val current = Calendar.getInstance(Estonia.timeZone).apply { timeInMillis = chosen }
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(current.get(Calendar.HOUR_OF_DAY))
            .setMinute(current.get(Calendar.MINUTE))
            .setTitleText(if (arrive) R.string.pl_arrive_pick else R.string.pl_depart_pick)
            .build()
        picker.addOnPositiveButtonClickListener {
            val now = System.currentTimeMillis()
            val at = Calendar.getInstance(Estonia.timeZone).apply {
                set(Calendar.HOUR_OF_DAY, picker.hour)
                set(Calendar.MINUTE, picker.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis < now - PAST_GRACE_MS) add(Calendar.DAY_OF_MONTH, 1)
            }
            time = at.timeInMillis
            arriveBy = arrive
            showWhen()
            plan()
        }
        // Cancelled: the chips as they were.
        picker.addOnDismissListener { if (_binding != null) showWhen() }
        picker.show(childFragmentManager, "time")
    }

    /** What [plan] is asked; a plan for something else can't just be refreshed. */
    private fun asked(): List<Any?> = listOf(from, to, time, arriveBy)

    /**
     * Keeps the ways on screen fresh: their live times now ([TripPlanner.again]), and only every [REPLAN_MS] asking the
     * planners again (a lot more work for them, and data), for ways that weren't there before. Also when none of the
     * ways can be taken any more.
     */
    private fun refresh() {
        val last = lastPlan
        if (last == null || lastAsked != asked() || System.currentTimeMillis() - last.plannedAt >= REPLAN_MS) return plan(quiet = true)
        val id = ++request
        binding.progress.visibility = View.VISIBLE
        background.execute {
            val result = runCatching { planner.again(last) }
            mainHandler.post {
                if (id != request || _binding == null) return@post
                if (result.getOrNull()?.itineraries.isNullOrEmpty()) plan(quiet = true) else show(result, quiet = true)
            }
        }
    }

    /** Plans from [from] to [to]; [quiet] keeps what's on screen until the new ways are in, as for a refresh. */
    private fun plan(quiet: Boolean = false) {
        if (_binding == null) return
        val from = from
        val to = to
        if (from == null || to == null) {
            request++
            shown = emptyList()
            lastPlan = null
            binding.intro.isVisible = true
            binding.progress.visibility = View.INVISIBLE
            binding.refresh.visibility = View.GONE
            binding.status.text = if (from == null && to != null) getString(R.string.pl_no_location) else null
            States.empty(binding.results, getString(R.string.pl_needs_places), R.raw.pl_anim_where)
            return
        }
        val id = ++request
        binding.progress.visibility = View.VISIBLE
        if (!quiet || shown.isEmpty()) {
            States.loading(
                binding.results, MaterialColors.getColor(binding.results, androidx.appcompat.R.attr.colorPrimary),
                getString(R.string.pl_searching),
            )
        }
        val context = requireContext().applicationContext
        val osmand = context.companion.osmand
        val time = time
        val arriveBy = arriveBy
        val asked = asked()
        background.execute {
            val result = runCatching {
                // Where OsmAnd has you now, not when it was picked.
                val origin = if (from.isMyLocation) OsmAndTrip.places(context, osmand)?.myLocation ?: from else from
                planner.plan(TripPlanner.Request(origin, to, time, arriveBy))
            }
            mainHandler.post {
                if (id != request || _binding == null) return@post
                lastAsked = asked
                show(result, quiet)
            }
        }
    }

    private fun show(result: Result<TripPlanner.Result>, quiet: Boolean) {
        binding.progress.visibility = View.INVISIBLE
        binding.refresh.visibility = View.VISIBLE
        val planned = result.getOrElse { e ->
            val message = if (e is IOException) e.message else e.toString()
            if (quiet && shown.isNotEmpty()) {
                binding.status.text = getString(R.string.pl_failed, message)
                return
            }
            shown = emptyList()
            lastPlan = null
            binding.intro.isVisible = true
            binding.status.text = null
            States.error(binding.results, getString(R.string.pl_failed, message)) { plan() }
            return
        }
        shown = planned.itineraries
        plannedAt = planned.at
        lastPlan = planned
        val updated = TransitFormat.clock(planned.at)
        binding.status.text = if (planned.failed.isEmpty()) {
            getString(R.string.pl_updated, updated)
        } else {
            getString(R.string.pl_updated_partly, updated, planned.failed.joinToString { getString(sourceName(it)) })
        }
        render(planned.at)
        if (!quiet) {
            Analytics.signal(
                "Planner.planned",
                mapOf(
                    "results" to planned.itineraries.size.coerceAtMost(Ranking.MAX).toString(),
                    "live" to planned.itineraries.any { it.isLive }.toString(),
                    "best" to (planned.itineraries.firstOrNull()?.source?.name ?: "none"),
                ),
            )
        }
    }

    /** The ways, as Citymapper lists them: walking on its own, then the rest, each with its departures. */
    private fun render(now: Long) {
        val results = binding.results
        binding.intro.isVisible = shown.isEmpty()
        if (shown.isEmpty()) {
            States.empty(results, getString(R.string.pl_nothing))
            return
        }
        results.removeAllViews()
        val origin = from?.name.orEmpty()
        val destination = to?.name.orEmpty()
        val planned = time != null
        val (walks, rides) = Way.group(shown).partition { it.isWalk }
        val inflater = layoutInflater
        for (group in listOf(walks, rides).filter { it.isNotEmpty() }) {
            if (group === rides) Rows.section(results, getString(R.string.pl_suggested))
            val card = PlItemWayGroupBinding.inflate(inflater, results, true)
            for (way in group) {
                ItineraryViews.way(card.rows, way, now, planned) {
                    startActivity(ItineraryActivity.intent(requireContext(), way, origin, destination))
                }
            }
        }
    }

    private fun sourceName(source: Source) = when (source) {
        Source.TALLINN -> R.string.pl_source_tallinn
        Source.RIDANGO -> R.string.pl_source_ridango
        Source.PEATUS -> R.string.pl_source_peatus
    }

    companion object {
        private const val KEY_FROM = "from"
        private const val KEY_TO = "to"
        private const val KEY_TIME = "time"
        private const val KEY_ARRIVE_BY = "arrive_by"

        /** OsmAnd may take a few seconds to connect after this app starts. */
        private const val OSMAND_TRIES = 4
        private const val OSMAND_RETRY_MS = 1_500L

        /** Live times change about this often. */
        private const val REFRESH_MS = 30_000L

        /** How often a refresh asks the planners again, rather than only putting in the live times. */
        private const val REPLAN_MS = 3 * 60_000L

        /** A time picked this long before now is meant for tomorrow. */
        private const val PAST_GRACE_MS = 30 * 60 * 1000L

        /** A link from OsmAnd ([OsmAndStopUi.PLANNER_LINK]) waiting for the tab to show it. */
        @Volatile
        private var pending: Uri? = null

        /**
         * For the home screen, which shows this tab next: plans to the place in [link] (OsmAndStopUi.plannerLink),
         * from where OsmAnd has you; without a place, to OsmAnd's destination if it has one.
         */
        fun open(link: Uri) {
            pending = link
        }
    }
}
