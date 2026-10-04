package dev.maksim.companion.planner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.color.MaterialColors
import dev.maksim.companion.core.Analytics
import dev.maksim.companion.core.canPostNotifications
import dev.maksim.companion.core.companion
import dev.maksim.companion.core.padForSystemBars
import dev.maksim.companion.planner.databinding.PlActivityItineraryBinding
import dev.maksim.companion.planner.databinding.PlItemChangeBinding
import dev.maksim.companion.planner.databinding.PlItemOptionBinding
import dev.maksim.companion.planner.databinding.PlItemStepBinding
import dev.maksim.companion.planner.databinding.PlItemSummaryBinding
import dev.maksim.companion.timetable.Arrows
import dev.maksim.companion.timetable.Mode
import dev.maksim.companion.timetable.OsmAndRoute
import dev.maksim.companion.timetable.PeatusClient
import dev.maksim.companion.timetable.Rows
import dev.maksim.companion.timetable.TransitFormat
import dev.maksim.companion.timetable.TripActivity
import dev.maksim.companion.timetable.TripLineView
import dev.maksim.companion.timetable.databinding.TtItemTripStopBinding
import java.util.concurrent.Executors
import kotlin.math.abs
import dev.maksim.companion.timetable.R as TtR

/**
 * One way there, as Citymapper shows it: when to leave and when you're there, then a card for each step on a track,
 * live, refreshed every [REFRESH_MS]. The first ride's card has the way's other departures to choose from ([Way]);
 * each ride's has where it's got on and off (the stops in between a tap away), and opens its trip's screen. Between
 * rides, the time to change. The header's button shows it all on OsmAnd's map; at the bottom, walk to the first stop
 * with OsmAnd, and GO: take it as a trip ([TripFeature]), followed on [LiveTripActivity]. If it's the trip being
 * taken, it's shown as the trip has it, new way and all.
 */
class ItineraryActivity : AppCompatActivity() {

    private lateinit var binding: PlActivityItineraryBinding
    private lateinit var itinerary: Itinerary

    /** The way's departures, [itinerary] among them. */
    private var options: List<Itinerary> = emptyList()
    private lateinit var origin: String
    private lateinit var destination: String

    /** Whether this is the trip being taken, so it shows what [TripStore] has. */
    private var following = false
    private val planner by lazy { TripPlanner() }
    private val peatus = PeatusClient()
    private val background = Executors.newSingleThreadExecutor()

    /** The legs whose stops in between are shown, by index. */
    private val unfolded = HashSet<Int>()

    private val tripChanged = Runnable { if (!isDestroyed) follow() }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startTrip() }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            binding.root.postDelayed(this, REFRESH_MS)
        }
    }

    /** Its arrows go out first. */
    override fun finish() {
        if (!Arrows.leave(this) { super.finish() }) super.finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back through finish(), not the system's own, so the arrows go out first.
        onBackPressedDispatcher.addCallback(this) { finish() }
        val way = IntentCompat.getSerializableExtra(intent, EXTRA_WAY, Way::class.java) ?: return finish()
        itinerary = way.best
        options = way.options
        origin = intent.getStringExtra(EXTRA_ORIGIN).orEmpty()
        destination = intent.getStringExtra(EXTRA_DESTINATION).orEmpty()
        val trip = TripStore.current(this)
        following = trip != null && options.any { it.signature == trip.itinerary.signature }
        binding = PlActivityItineraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        binding.header.back.setOnClickListener { finish() }
        with(binding.header.action) {
            isVisible = true
            setIconResource(TtR.drawable.tt_ic_map)
            contentDescription = getString(R.string.pl_show_in_osmand)
            TooltipCompat.setTooltipText(this, contentDescription)
            setOnClickListener { showInOsmand() }
        }
        binding.walk.setOnClickListener { walkThere() }
        binding.trip.setOnClickListener {
            if (following) startActivity(LiveTripActivity.intent(this)) else askToStartTrip()
        }
        TripStore.addListener(tripChanged)
        follow()
    }

    override fun onStart() {
        super.onStart()
        binding.root.postDelayed(tick, REFRESH_MS)
        val context = applicationContext
        background.execute { OsmAndRoute.clearLeftover(context, context.companion.osmand) }
    }

    override fun onStop() {
        binding.root.removeCallbacks(tick)
        super.onStop()
    }

    override fun onDestroy() {
        TripStore.removeListener(tripChanged)
        background.shutdownNow()
        super.onDestroy()
    }

    /** The trip being taken as [TripStore] has it now, if this is it; else as it is. */
    private fun follow() {
        if (following) {
            val trip = TripStore.current(this)
            if (trip == null) {
                following = false
            } else {
                itinerary = trip.itinerary
                if (trip.options.isNotEmpty()) options = trip.options
            }
        }
        binding.trip.setText(if (following) R.string.pl_go_open else R.string.pl_go)
        binding.walk.isVisible = itinerary.legs.firstOrNull()?.isWalk == true && itinerary.rides.isNotEmpty() &&
            System.currentTimeMillis() < itinerary.rides.first().departure
        render()
    }

    /** Notifications are what the trip is followed in: Android 13+ asks first. It's taken without them all the same. */
    private fun askToStartTrip() {
        if (canPostNotifications()) startTrip() else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startTrip() {
        val end = itinerary.legs.last().to
        TripStore.start(this, itinerary, origin, Place(destination, end.lat, end.lon), options)
        following = true
        follow()
        startActivity(LiveTripActivity.intent(this))
    }

    /** Takes [option], another departure the same way: for the trip too, if it's the one being taken. */
    private fun choose(option: Itinerary) {
        Analytics.signal("Planner.choseDeparture")
        if (following) return TripStore.choose(this, option)
        itinerary = option
        follow()
    }

    private fun refresh() {
        // The trip feature keeps the trip being taken fresh.
        if (following) return follow()
        val current = itinerary
        val all = options.ifEmpty { listOf(current) }
        if (all.none { option -> option.rides.any { it.ride?.feed != LiveFeed.NONE } }) return render()
        binding.progress.isVisible = true
        background.execute {
            val fresh = runCatching { planner.refresh(all) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.progress.isVisible = false
                if (fresh != null && itinerary === current) {
                    options = fresh
                    itinerary = fresh.firstOrNull { it.signature == current.signature } ?: current
                }
                render()
            }
        }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val itinerary = itinerary
        val firstRide = itinerary.rides.firstOrNull()?.ride
        Rows.header(
            binding.header,
            firstRide?.let { Mode.of(it.mode) } ?: Mode.OTHER,
            null,
            getString(R.string.pl_title, destination),
            getString(R.string.pl_subtitle_from, origin),
        )
        val content = binding.content
        content.removeAllViews()
        summary(content, itinerary, now)
        ItineraryViews.warning(this, itinerary, now)?.let { warning ->
            val late = getColor(TtR.color.tt_late)
            content.addView(
                TextView(this).apply {
                    text = warning
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setTextColor(late)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(TtR.drawable.tt_ic_warning, 0, 0, 0)
                    compoundDrawableTintList = ColorStateList.valueOf(late)
                    compoundDrawablePadding = dp(8)
                    setPadding(dp(4), 0, dp(4), dp(12))
                },
            )
        }
        val changes = itinerary.changes
        var ride = 0
        for ((i, leg) in itinerary.legs.withIndex()) {
            if (i > 0) track(content)
            if (leg.isWalk) {
                walk(content, leg, last = i == itinerary.legs.lastIndex)
            } else {
                ride(content, i, leg, first = ride == 0, now)
                changes.getOrNull(ride)?.let { change(content, it) }
                ride++
            }
        }
        track(content)
        arrival(content, itinerary)
    }

    /** "Leave within 8 min · Arrive 19:15", and the way at a glance. */
    private fun summary(parent: ViewGroup, itinerary: Itinerary, now: Long) {
        val card = PlItemSummaryBinding.inflate(layoutInflater, parent, true)
        val live = getColor(TtR.color.tt_live)
        val leavesLive = itinerary.rides.firstOrNull()?.from?.isLive == true
        val soon = TransitFormat.relative(this, itinerary.start, now)
        card.leave.text = if (itinerary.start - now >= LEAVE_NOW_MS && soon != null) getString(R.string.pl_leave_within, soon)
        else ItineraryViews.leave(this, itinerary.start, now)
        card.leave.setTypeface(card.leave.typeface, Typeface.BOLD)
        if (leavesLive) card.leave.setTextColor(live)
        Rows.liveMark(card.live, leavesLive, live)
        card.arrive.text = getString(R.string.pl_arrive_at, TransitFormat.clock(itinerary.end))
        ItineraryViews.chain(card.chain, itinerary)
        card.summary.text = ItineraryViews.summary(this, itinerary)
    }

    private fun walk(parent: ViewGroup, leg: Leg, last: Boolean) {
        val card = step(parent, R.drawable.pl_ic_walk, MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurfaceVariant))
        card.title.text = if (last) getString(R.string.pl_walk_to_destination) else getString(R.string.pl_step_walk, leg.to.name)
        val distance = leg.distance.takeIf { it > 0 }?.let { getString(R.string.pl_walk_distance, ItineraryViews.roundMeters(it)) }
        subtitle(card, listOfNotNull(distance, TransitFormat.clock(leg.departure)).joinToString(" · "))
        minutes(card, maxOf(leg.duration, 60_000L))
        card.root.contentDescription = getString(R.string.pl_walk_to, ItineraryViews.walk(this, leg), leg.to.name)
    }

    /**
     * A ride's card: the line and where it goes, how soon it leaves; the other departures to choose from, if it's the
     * [first] ride; and where it's got on, the stops in between (folded), and where it's got off.
     */
    private fun ride(parent: ViewGroup, index: Int, leg: Leg, first: Boolean, now: Long) {
        val ride = leg.ride ?: return
        val mode = Mode.of(ride.mode)
        val card = step(parent, mode.icon, mode.color)
        val choices = if (first) options.filter { it.rides.isNotEmpty() }.take(MAX_OPTIONS) else emptyList()
        if (choices.size > 1) {
            val lines = choices.map { it.rides.first().ride!!.route }.distinct().joinToString(" / ")
            val headsigns = choices.map { it.rides.first().ride!!.headsign }.distinct().joinToString(" | ")
            Arrows.set(card.title, "${getString(mode.label)} $lines → $headsigns")
        } else {
            Arrows.set(card.title, "${ItineraryViews.vehicle(this, ride)} → ${ride.headsign}")
        }
        subtitle(card, getString(R.string.pl_get_off, leg.to.name))
        val live = getColor(TtR.color.tt_live)
        val soon = TransitFormat.relative(this, leg.departure, now)?.takeIf { leg.departure >= now - PASSED_GRACE_MS }
        if (soon != null && leg.departure - now >= 60_000) {
            card.prefix.isVisible = true
            card.prefix.setText(R.string.pl_in)
            minutes(card, leg.departure - now, countdown = true)
        } else {
            card.number.text = TransitFormat.clock(leg.departure)
            card.unit.isVisible = false
        }
        if (leg.from.isLive) card.number.setTextColor(live)
        card.head.setOnClickListener { openTrip(leg) }
        card.body.isVisible = true
        if (choices.size > 1) options(card.body, choices, now)

        val board = stop(card.body, leg.from, mode, TripLineView.Stop.FIRST, now)
        board.name.setTypeface(board.name.typeface, Typeface.BOLD)
        board.root.setOnClickListener { openTrip(leg) }
        if (leg.stops.isNotEmpty()) {
            if (index in unfolded) {
                for (call in leg.stops) stop(card.body, call, mode, TripLineView.Stop.MIDDLE, now, eta = false).root.setOnClickListener { fold(index) }
            } else {
                stop(card.body, null, mode, TripLineView.Stop.MIDDLE, now, eta = false).run {
                    name.text = "${resources.getQuantityString(R.plurals.pl_stops_between, leg.stops.size, leg.stops.size)} ▾"
                    name.setTextColor(MaterialColors.getColor(name, com.google.android.material.R.attr.colorOnSurfaceVariant))
                    time.text = ItineraryViews.duration(this@ItineraryActivity, leg.arrival - leg.departure)
                    time.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                    root.contentDescription = getString(R.string.pl_show_stops)
                    root.setOnClickListener { fold(index) }
                }
            }
        }
        stop(card.body, leg.to, mode, TripLineView.Stop.LAST, now).run {
            name.setTypeface(name.typeface, Typeface.BOLD)
            root.setOnClickListener { openTrip(leg) }
        }
    }

    /** The departures to choose from, as Citymapper lists them: the one taken highlighted, those too soon dimmed. */
    private fun options(parent: ViewGroup, choices: List<Itinerary>, now: Long) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.pl_option_bg)
            backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(parent, com.google.android.material.R.attr.colorSurfaceContainer),
            )
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        parent.addView(
            box,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(12), 0, dp(12), dp(8))
            },
        )
        for (option in choices) optionRow(box, option, option.signature == itinerary.signature, now) { choose(option) }
    }

    /** Pull down the stops in between, or put them away. */
    private fun fold(index: Int) {
        if (!unfolded.remove(index)) unfolded += index
        render()
    }

    /**
     * A stop of a ride in the trip screen's look: its time (green when live), its piece of the line, its name, and how
     * soon with how late; [call] null for the folded stops in between.
     */
    private fun stop(parent: ViewGroup, call: Call?, mode: Mode, kind: TripLineView.Stop, now: Long, eta: Boolean = true): TtItemTripStopBinding =
        stopRow(layoutInflater, parent, call, mode, kind, now, eta)

    /** The time to change after a ride, on the track: orange when it's tight, red when it's missed. */
    private fun change(parent: ViewGroup, change: Itinerary.Change) {
        val row = PlItemChangeBinding.inflate(layoutInflater, parent, true)
        row.root.tag = CHANGE
        val margin = change.margin
        val color = when {
            margin < 0 -> getColor(TtR.color.tt_offline_strike)
            margin < ItineraryViews.TIGHT_MS -> getColor(TtR.color.tt_late)
            else -> MaterialColors.getColor(row.margin, com.google.android.material.R.attr.colorOnSurfaceVariant)
        }
        row.margin.text = if (margin < 0) getString(R.string.pl_change_margin_missed, ItineraryViews.duration(this, -margin))
        else getString(R.string.pl_change_margin, ItineraryViews.duration(this, margin))
        row.margin.setTextColor(color)
        row.margin.backgroundTintList = ColorStateList.valueOf(color).withAlpha(PILL_ALPHA)
    }

    /** Where the trip ends: the place, when you're there. */
    private fun arrival(parent: ViewGroup, itinerary: Itinerary) {
        val card = step(parent, R.drawable.pl_ic_place, getColor(TtR.color.tt_offline_strike))
        card.title.text = destination
        card.number.text = TransitFormat.clock(itinerary.end)
        card.unit.isVisible = false
        if (itinerary.rides.lastOrNull()?.to?.isLive == true) card.number.setTextColor(getColor(TtR.color.tt_live))
    }

    private fun step(parent: ViewGroup, icon: Int, color: Int): PlItemStepBinding = stepCard(layoutInflater, parent, icon, color)

    /** The track between two cards, unless the time to change, which is on it, is there already. */
    private fun track(parent: ViewGroup) {
        if (parent.getChildAt(parent.childCount - 1)?.tag != CHANGE) layoutInflater.inflate(R.layout.pl_item_connector, parent, true)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /**
     * The ride's trip on its own screen. Rides found by this app's own search have no peatus.ee id, so it's looked up
     * among the stop's departures by the line and the time.
     */
    private fun openTrip(leg: Leg) {
        val ride = leg.ride ?: return
        if (ride.tripId != null && ride.serviceDate != null) {
            startActivity(TripActivity.intent(this, ride.tripId, OtpPlanner.midnight(ride.serviceDate) / 1000, leg.from.stopId))
            return
        }
        binding.progress.isVisible = true
        background.execute {
            val found = runCatching { findTrip(leg, ride) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.progress.isVisible = false
                if (found == null) {
                    Toast.makeText(this, R.string.pl_trip_unknown, Toast.LENGTH_LONG).show()
                } else {
                    startActivity(TripActivity.intent(this, found.tripId, found.serviceDay, found.stopId))
                }
            }
        }
    }

    private class FoundTrip(val tripId: String, val serviceDay: Long, val stopId: String)

    private fun findTrip(leg: Leg, ride: Ride): FoundTrip? {
        val stopId = leg.from.stopId ?: leg.from.code?.let { code ->
            peatus.nearbyStops(leg.from.lat, leg.from.lon, SAME_STOP_M, 20, 0).firstOrNull { it.code == code }?.id
        } ?: return null
        val stop = peatus.stopWithin(stopId, FIND_WINDOW_S) ?: return null
        val departure = stop.departures.firstOrNull {
            it.route.equals(ride.route, ignoreCase = true) &&
                abs((it.serviceDay + it.scheduled) * 1000 - leg.from.scheduled) <= MATCH_MS
        } ?: return null
        return FoundTrip(departure.tripId, departure.serviceDay, stopId)
    }

    /** Draws the itinerary in OsmAnd with its card open, and switches there; it's gone once the card is closed. */
    private fun showInOsmand() {
        val osmand = companion.osmand
        if (osmand.osmandPackage == null && osmand.findInstalledOsmand() == null) {
            return Toast.makeText(this, TtR.string.tt_osmand_missing, Toast.LENGTH_LONG).show()
        }
        if (!osmand.checkAccess()) return Toast.makeText(this, TtR.string.tt_route_no_access, Toast.LENGTH_LONG).show()
        binding.progress.isVisible = true
        val itinerary = itinerary
        background.execute {
            val shown = OsmAndTrip.show(this, osmand, itinerary, destination)
            val launch = OsmAndTrip.launchIntent(this, osmand)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.progress.isVisible = false
                if (shown && launch != null) {
                    Analytics.signal("Planner.shownInOsmAnd")
                    startActivity(launch)
                } else {
                    Toast.makeText(this, TtR.string.tt_route_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** OsmAnd's walking navigation to the first stop. */
    private fun walkThere() {
        val stop = itinerary.rides.firstOrNull()?.from ?: return
        walkWithOsmAnd(this, stop)
    }

    companion object {
        private const val EXTRA_WAY = "way"
        private const val EXTRA_ORIGIN = "origin"
        private const val EXTRA_DESTINATION = "destination"
        private const val REFRESH_MS = 30_000L

        /** Marks the change pill, which has the track in it already. */
        private const val CHANGE = "change"

        /** Departures to choose from on the first ride's card. */
        private const val MAX_OPTIONS = 5

        /** Less than this from now, it's "Leave now". */
        private const val LEAVE_NOW_MS = 60_000L

        /** A stop that was due this long ago has been passed. */
        const val PASSED_GRACE_MS = 30_000L
        const val PILL_ALPHA = 0x26

        /** How long OsmAnd takes to come up with its map. */
        private const val OSMAND_UP_MS = 1_500L

        private const val SAME_STOP_M = 80
        private const val FIND_WINDOW_S = 3 * 60 * 60
        private const val MATCH_MS = 90_000L

        fun intent(context: Context, way: Way, origin: String, destination: String): Intent =
            Intent(context, ItineraryActivity::class.java)
                .putExtra(EXTRA_WAY, way)
                .putExtra(EXTRA_ORIGIN, origin)
                .putExtra(EXTRA_DESTINATION, destination)

        /** OsmAnd's walking navigation to [to]: OsmAnd first, as it only takes it with its map up. */
        fun walkWithOsmAnd(activity: AppCompatActivity, to: Call) {
            val osmand = activity.companion.osmand
            val launch = OsmAndTrip.launchIntent(activity, osmand)
                ?: return Toast.makeText(activity, TtR.string.tt_osmand_missing, Toast.LENGTH_LONG).show()
            if (!osmand.checkAccess()) return Toast.makeText(activity, TtR.string.tt_route_no_access, Toast.LENGTH_LONG).show()
            Analytics.signal("Planner.walkInOsmAnd")
            activity.startActivity(launch)
            Thread {
                Thread.sleep(OSMAND_UP_MS)
                OsmAndTrip.walkTo(osmand, to)
            }.start()
        }

        /** A step card at the end of [parent]: [icon] on a square of [color]. */
        fun stepCard(inflater: LayoutInflater, parent: ViewGroup, icon: Int, color: Int): PlItemStepBinding {
            val card = PlItemStepBinding.inflate(inflater, parent, true)
            card.icon.setImageResource(icon)
            card.icon.backgroundTintList = ColorStateList.valueOf(color)
            return card
        }

        /** [card]'s line under its title, if there's anything to say. */
        fun subtitle(card: PlItemStepBinding, text: String?) {
            card.subtitle.isVisible = !text.isNullOrEmpty()
            Arrows.set(card.subtitle, text)
        }

        /** [ms] in [card]'s value, as "13 min", or "1 h 5 min" whole; a [countdown] counts whole minutes to go. */
        fun minutes(card: PlItemStepBinding, ms: Long, countdown: Boolean = false) {
            val minutes = if (countdown) ItineraryViews.countdown(ms) else ItineraryViews.minutes(ms)
            card.number.text = if (minutes < 60) minutes.toString() else ItineraryViews.duration(card.root.context, ms)
            card.unit.isVisible = minutes < 60
            card.unit.setText(R.string.pl_unit_min)
        }

        /** A departure to choose: [chosen] highlighted, one that would've needed leaving already dimmed. */
        fun optionRow(parent: ViewGroup, option: Itinerary, chosen: Boolean, now: Long, onClick: () -> Unit) {
            val context = parent.context
            val leg = option.rides.first()
            val ride = leg.ride!!
            val mode = Mode.of(ride.mode)
            val row = PlItemOptionBinding.inflate(LayoutInflater.from(context), parent, true)
            row.radio.isChecked = chosen
            row.badge.text = ride.route
            row.badge.backgroundTintList = ColorStateList.valueOf(mode.color)
            row.badge.setCompoundDrawablesRelativeWithIntrinsicBounds(mode.icon, 0, 0, 0)
            row.headsign.text = ride.headsign
            row.headsign.setTypeface(null, if (chosen) Typeface.BOLD else Typeface.NORMAL)
            val live = context.getColor(TtR.color.tt_live)
            val soon = TransitFormat.relative(context, leg.departure, now)?.takeIf { leg.departure >= now - PASSED_GRACE_MS }
            row.time.text = soon ?: TransitFormat.clock(leg.departure)
            if (leg.from.isLive) row.time.setTextColor(live)
            val tooSoon = option.start < now - PASSED_GRACE_MS
            row.timer.isVisible = tooSoon
            row.root.alpha = if (tooSoon && !chosen) TOO_SOON_ALPHA else 1f
            row.root.backgroundTintList = ColorStateList.valueOf(
                if (chosen) MaterialColors.getColor(parent, com.google.android.material.R.attr.colorSurfaceContainerLowest) else Color.TRANSPARENT,
            )
            row.root.elevation = if (chosen) 2 * context.resources.displayMetrics.density else 0f
            row.root.contentDescription = listOfNotNull(
                "${ItineraryViews.vehicle(context, ride)} → ${ride.headsign}", row.time.text,
                context.getString(R.string.pl_cant_make).takeIf { tooSoon },
            ).joinToString(", ")
            row.root.setOnClickListener { onClick() }
        }

        /**
         * A stop row of a ride in the trip screen's look into [parent]: its time (green when live), its piece of the
         * line, its name, and how soon with how late; [call] null for the folded stops in between.
         */
        fun stopRow(
            inflater: LayoutInflater,
            parent: ViewGroup,
            call: Call?,
            mode: Mode,
            kind: TripLineView.Stop,
            now: Long,
            eta: Boolean = true,
        ): TtItemTripStopBinding {
            val row = TtItemTripStopBinding.inflate(inflater, parent, true)
            val context = parent.context
            row.root.updatePadding(right = row.root.paddingRight + (4 * context.resources.displayMetrics.density).toInt())
            val live = context.getColor(TtR.color.tt_live)
            row.line.setMode(mode)
            row.line.kind = kind
            row.line.passed = call != null && call.expected < now - PASSED_GRACE_MS
            row.time.text = call?.let { TransitFormat.clock(it.expected) }
            if (call?.isLive == true) row.time.setTextColor(live)
            row.name.text = call?.name
            val soon = call?.takeIf { eta && it.expected >= now - PASSED_GRACE_MS }?.let { TransitFormat.relative(context, it.expected, now) }
            row.eta.isVisible = soon != null
            row.etaText.text = soon
            val color = if (call?.isLive == true) live else mode.color
            if (call?.isLive == true) row.etaText.setTextColor(live)
            val delay = call?.let { ItineraryViews.delay(it) }
            row.delay.isVisible = soon != null && delay != null
            row.etaMain.setBackgroundResource(if (row.delay.isVisible) TtR.drawable.tt_pill_start_bg else TtR.drawable.tt_pill_bg)
            row.etaMain.backgroundTintList = ColorStateList.valueOf(color).withAlpha(PILL_ALPHA)
            if (row.delay.isVisible && call != null) {
                val delayColor = context.getColor(if (call.delayMinutes > 0) TtR.color.tt_late else TtR.color.tt_early)
                row.delay.text = delay
                row.delay.setTextColor(delayColor)
                row.delay.backgroundTintList = ColorStateList.valueOf(delayColor).withAlpha(PILL_ALPHA)
                row.eta.contentDescription = listOf(
                    soon,
                    context.getString(if (call.delayMinutes > 0) TtR.string.tt_late else TtR.string.tt_early, abs(call.delayMinutes)),
                ).joinToString(", ")
            }
            return row
        }

        private const val TOO_SOON_ALPHA = 0.5f
    }
}
