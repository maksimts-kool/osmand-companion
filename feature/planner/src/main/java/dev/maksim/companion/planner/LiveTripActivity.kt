package dev.maksim.companion.planner

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import dev.maksim.companion.core.Analytics
import dev.maksim.companion.core.padForSystemBars
import dev.maksim.companion.planner.TripSteps.Kind
import dev.maksim.companion.planner.databinding.PlActivityLiveBinding
import dev.maksim.companion.planner.databinding.PlItemCountdownBinding
import dev.maksim.companion.timetable.Mode
import dev.maksim.companion.timetable.Rows
import dev.maksim.companion.timetable.TransitFormat
import dev.maksim.companion.timetable.TripLineView
import dev.maksim.companion.timetable.R as TtR

/**
 * The trip being taken ([TripStore]), live, as Citymapper's GO has it but without its map: time left and when you're
 * there, how far along it is, and what to do now, one step at a time ([TripSteps]); Prev and Next look at the others,
 * and it goes back to following once the trip moves on to its next step. A walk's step walks there with OsmAnd; a
 * ride's, before it leaves, counts down to it with its live times and the other departures to switch to; on it, the
 * stops to go and whether to be told to get off. [TripFeature] keeps the trip's times fresh; this shows them, every
 * [TICK_MS] for the countdowns.
 */
class LiveTripActivity : AppCompatActivity() {

    private lateinit var binding: PlActivityLiveBinding

    /** The step looked at with Prev and Next; null: the one it's at. */
    private var viewing: Int? = null
    private var lastCurrent = -1

    /** What [page] last showed, so the arrival's animation isn't started again by every tick. */
    private var pageKey: String? = null

    private val tripChanged = Runnable { if (!isDestroyed) render() }

    private val tick = object : Runnable {
        override fun run() {
            render()
            binding.root.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (TripStore.current(this) == null) return finish()
        binding = PlActivityLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        viewing = savedInstanceState?.getInt(KEY_VIEWING, -1)?.takeIf { it >= 0 }
        binding.back.setOnClickListener { finish() }
        binding.end.setOnClickListener {
            TripStore.stop(this, arrived = false)
            finish()
        }
        binding.prev.setOnClickListener { look(-1) }
        binding.next.setOnClickListener { look(+1) }
        binding.now.setOnClickListener {
            viewing = null
            render()
        }
        TripStore.addListener(tripChanged)
        Analytics.signal("Trip.liveOpened")
    }

    override fun onStart() {
        super.onStart()
        tick.run()
    }

    override fun onStop() {
        binding.root.removeCallbacks(tick)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_VIEWING, viewing ?: -1)
    }

    override fun onDestroy() {
        TripStore.removeListener(tripChanged)
        super.onDestroy()
    }

    private fun look(by: Int) {
        val trip = TripStore.current(this) ?: return finish()
        val steps = TripSteps.of(trip.itinerary)
        val at = (viewing ?: TripSteps.current(trip.itinerary, steps, System.currentTimeMillis())) + by
        viewing = at.coerceIn(0, steps.lastIndex)
        render()
    }

    private fun render() {
        val trip = TripStore.current(this) ?: return finish()
        val itinerary = trip.itinerary
        val now = System.currentTimeMillis()
        val steps = TripSteps.of(itinerary)
        val current = TripSteps.current(itinerary, steps, now)
        // On to the next step: follow it again.
        if (current != lastCurrent) {
            if (lastCurrent >= 0) viewing = null
            lastCurrent = current
        }
        val shown = (viewing ?: current).coerceIn(0, steps.lastIndex)
        val live = getColor(TtR.color.tt_live)

        binding.left.text = ItineraryViews.countdown(itinerary.end - now).toString()
        binding.eta.text = TransitFormat.clock(itinerary.end)
        Rows.liveMark(binding.live, itinerary.isLive, live)
        binding.pill.contentDescription = getString(R.string.pl_trip_arrive, TransitFormat.clock(itinerary.end), trip.destination.name)
        binding.tripProgress.setProgressCompat((TripSteps.fraction(itinerary, now) * PROGRESS_MAX).toInt(), true)

        instruction(trip, steps[shown], isCurrent = shown == current, now)
        dots(steps.size, shown, current)
        binding.prev.isEnabled = shown > 0
        binding.next.isEnabled = shown < steps.lastIndex
        binding.now.isVisible = shown != current
        page(trip, steps[shown], shown == current, now)
    }

    /** What to do at [step], as the card on top says it: "Walk to stop · Koidu · 3 min". */
    private fun instruction(trip: ActiveTrip, step: TripSteps.Step, isCurrent: Boolean, now: Long) {
        val legs = trip.itinerary.legs
        val leg = legs[step.leg]
        val ride = leg.ride
        val color = color(step, leg)
        binding.stepIcon.setImageResource(icon(step, leg))
        binding.stepIcon.backgroundTintList = ColorStateList.valueOf(color)
        binding.pulse.isVisible = isCurrent && step.kind != Kind.ARRIVED
        val filter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        binding.pulse.addValueCallback(KeyPath("**"), LottieProperty.COLOR_FILTER) { filter }
        val (kicker, headline, until) = when (step.kind) {
            Kind.WALK -> Triple(getString(R.string.pl_walk_to_stop), leg.to.name, leg.arrival)
            Kind.BOARD -> Triple("${ItineraryViews.vehicle(this, ride!!)} → ${ride.headsign}", leg.from.name, leg.departure)
            Kind.RIDE -> Triple("${ItineraryViews.vehicle(this, ride!!)} → ${ride.headsign}", getString(R.string.pl_get_off, leg.to.name), leg.arrival)
            Kind.WALK_THERE -> Triple(getString(R.string.pl_walk_to_destination), trip.destination.name, trip.itinerary.end)
            Kind.ARRIVED -> Triple("", getString(R.string.pl_step_arrived, trip.destination.name), null)
        }
        binding.headline.text = headline
        // Not yet left: when to, and how long the walk is.
        if (step.kind == Kind.WALK && isCurrent && now < leg.departure) {
            binding.kicker.text = "${ItineraryViews.leave(this, leg.departure, now)} · ${getString(R.string.pl_walk_to_stop).lowercase()}"
            binding.kicker.isVisible = true
            binding.away.isVisible = true
            binding.away.text = ItineraryViews.walk(this, leg)
            binding.away.setTextColor(MaterialColors.getColor(binding.away, androidx.appcompat.R.attr.colorPrimary))
            return
        }
        binding.kicker.text = kicker
        binding.kicker.isVisible = kicker.isNotEmpty()
        binding.away.isVisible = until != null
        if (until != null) {
            binding.away.text = if (isCurrent || until > now) {
                TransitFormat.relative(this, until, now)?.takeIf { until >= now } ?: TransitFormat.clock(until)
            } else {
                TransitFormat.clock(until)
            }
            val liveTime = (step.kind == Kind.BOARD && leg.from.isLive) || (step.kind == Kind.RIDE && leg.to.isLive)
            binding.away.setTextColor(
                if (liveTime) getColor(TtR.color.tt_live) else MaterialColors.getColor(binding.away, androidx.appcompat.R.attr.colorPrimary),
            )
        }
    }

    /** A dot per step: the one looked at in the accent color, the one it's at in green. */
    private fun dots(count: Int, shown: Int, current: Int) {
        val dots = binding.dots
        dots.removeAllViews()
        val size = dp(7)
        val other = MaterialColors.getColor(dots, com.google.android.material.R.attr.colorOutlineVariant)
        val primary = MaterialColors.getColor(dots, androidx.appcompat.R.attr.colorPrimary)
        for (i in 0 until count) {
            dots.addView(
                ImageView(this).apply {
                    setImageResource(R.drawable.pl_dot)
                    imageTintList = ColorStateList.valueOf(
                        when (i) {
                            shown -> primary
                            current -> getColor(R.color.pl_go)
                            else -> other
                        },
                    )
                    importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(size, size).apply { setMargins(dp(3), 0, dp(3), 0) },
            )
        }
    }

    /** The step in full, under the card on top. */
    private fun page(trip: ActiveTrip, step: TripSteps.Step, isCurrent: Boolean, now: Long) {
        val key = "${trip.version}:$step"
        if (step.kind == Kind.ARRIVED && key == pageKey) return
        pageKey = key
        val page = binding.page
        page.removeAllViews()
        val itinerary = trip.itinerary
        val leg = itinerary.legs[step.leg]
        when (step.kind) {
            Kind.WALK -> walkPage(page, itinerary, step.leg, leg)
            Kind.BOARD -> boardPage(page, trip, step.leg, leg, now)
            Kind.RIDE -> ridePage(page, trip, leg, isCurrent, now)
            Kind.WALK_THERE -> {
                val card = ItineraryActivity.stepCard(layoutInflater, page, R.drawable.pl_ic_walk, getColor(R.color.pl_go))
                card.title.setText(R.string.pl_walk_to_destination)
                ItineraryActivity.subtitle(card, walkText(leg, trip.itinerary.end))
                ItineraryActivity.minutes(card, maxOf(leg.duration, 60_000L))
                card.body.isVisible = true
                osmandButton(card.body, leg.to.copy(name = trip.destination.name))
            }
            Kind.ARRIVED -> arrivedPage(page, trip)
        }
    }

    /** Walk to the stop: how far, by when, the ride it's for, and OsmAnd to walk there with. */
    private fun walkPage(page: ViewGroup, itinerary: Itinerary, index: Int, leg: Leg) {
        val card = ItineraryActivity.stepCard(layoutInflater, page, R.drawable.pl_ic_walk, getColor(R.color.pl_go))
        card.title.text = getString(R.string.pl_step_walk, leg.to.name)
        ItineraryActivity.subtitle(card, walkText(leg, leg.arrival))
        ItineraryActivity.minutes(card, maxOf(leg.duration, 60_000L))
        card.body.isVisible = true
        itinerary.legs.drop(index + 1).firstOrNull { !it.isWalk }?.let { next ->
            val ride = next.ride!!
            card.body.addView(
                TextView(this).apply {
                    text = getString(
                        R.string.pl_then_catch, "${ItineraryViews.vehicle(this@LiveTripActivity, ride)} → ${ride.headsign}",
                        TransitFormat.clock(next.departure),
                    )
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    if (next.from.isLive) setTextColor(getColor(TtR.color.tt_live))
                    setPadding(dp(16), 0, dp(16), dp(4))
                },
            )
        }
        osmandButton(card.body, leg.to)
    }

    /** Before a ride leaves: a countdown to it, live or not, and the other departures the same way to switch to. */
    private fun boardPage(page: ViewGroup, trip: ActiveTrip, index: Int, leg: Leg, now: Long) {
        val ride = leg.ride!!
        val mode = Mode.of(ride.mode)
        val card = PlItemCountdownBinding.inflate(layoutInflater, page, true)
        card.badge.text = ride.route
        card.badge.backgroundTintList = ColorStateList.valueOf(mode.color)
        card.badge.setCompoundDrawablesRelativeWithIntrinsicBounds(mode.icon, 0, 0, 0)
        card.headsign.text = "→ ${ride.headsign}"
        val live = getColor(TtR.color.tt_live)
        val minutes = ItineraryViews.countdown(leg.departure - now)
        card.minutes.text = if (minutes < 60) minutes.toString() else TransitFormat.clock(leg.departure)
        card.unit.isVisible = minutes < 60
        card.minutes.setTextColor(if (leg.from.isLive) live else mode.color)
        Rows.liveMark(card.live, leg.from.isLive, live)
        card.status.text = status(leg.from)
        card.status.setTextColor(
            when {
                !leg.from.isLive -> MaterialColors.getColor(card.status, com.google.android.material.R.attr.colorOnSurfaceVariant)
                leg.from.delayMinutes > 0 -> getColor(TtR.color.tt_late)
                leg.from.delayMinutes < 0 -> getColor(TtR.color.tt_early)
                else -> live
            },
        )
        card.from.text = getString(R.string.pl_leaving_from, leg.from.name, TransitFormat.clock(leg.departure))

        // Only the first ride has a choice, and only while it's still to come.
        val first = trip.itinerary.legs.indexOfFirst { !it.isWalk } == index
        val choices = trip.options.filter { it.rides.isNotEmpty() && it.rides.first().departure >= now - ItineraryActivity.PASSED_GRACE_MS }
        if (!first || choices.size < 2 || leg.departure < now) return
        page.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
        val options = ItineraryActivity.stepCard(layoutInflater, page, mode.icon, mode.color)
        options.title.setText(R.string.pl_departures)
        options.value.isVisible = false
        options.body.isVisible = true
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        options.body.addView(box)
        for (option in choices.take(MAX_OPTIONS)) {
            // The one taken as the trip has it, live times and all.
            val chosen = option.signature == trip.itinerary.signature
            ItineraryActivity.optionRow(box, if (chosen) trip.itinerary else option, chosen, now) {
                Analytics.signal("Trip.choseDeparture")
                TripStore.choose(this, option)
            }
        }
    }

    /** On a ride: its stops, those passed greyed, how soon each is; and whether to be told to get off. */
    private fun ridePage(page: ViewGroup, trip: ActiveTrip, leg: Leg, isCurrent: Boolean, now: Long) {
        val ride = leg.ride!!
        val mode = Mode.of(ride.mode)
        val card = ItineraryActivity.stepCard(layoutInflater, page, mode.icon, mode.color)
        val count = leg.stops.size + 1
        card.title.text = resources.getQuantityString(R.plurals.pl_ride_stops, count, count, leg.to.name)
        ItineraryActivity.subtitle(card, "${ItineraryViews.vehicle(this, ride)} → ${ride.headsign}")
        if (isCurrent) ItineraryActivity.minutes(card, leg.arrival - now, countdown = true) else ItineraryActivity.minutes(card, leg.duration)
        card.body.isVisible = true
        ItineraryActivity.stopRow(layoutInflater, card.body, leg.from, mode, TripLineView.Stop.FIRST, now)
        for (call in leg.stops) ItineraryActivity.stopRow(layoutInflater, card.body, call, mode, TripLineView.Stop.MIDDLE, now)
        ItineraryActivity.stopRow(layoutInflater, card.body, leg.to, mode, TripLineView.Stop.LAST, now).run {
            name.setTypeface(name.typeface, android.graphics.Typeface.BOLD)
        }
        card.body.addView(
            MaterialSwitch(this).apply {
                setText(R.string.pl_get_off_alert)
                isChecked = trip.getOffAlert
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setPadding(dp(16), dp(4), dp(16), dp(4))
                setOnCheckedChangeListener { _, on -> TripStore.setGetOffAlert(this@LiveTripActivity, on) }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    /** There: a check, where, and Done to end the trip. */
    private fun arrivedPage(page: ViewGroup, trip: ActiveTrip) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        page.addView(box)
        box.addView(
            LottieAnimationView(this).apply {
                setAnimation(R.raw.pl_anim_done)
                importantForAccessibility = LottieAnimationView.IMPORTANT_FOR_ACCESSIBILITY_NO
                playAnimation()
            },
            LinearLayout.LayoutParams(dp(112), dp(112)),
        )
        box.addView(
            TextView(this).apply {
                text = getString(R.string.pl_step_arrived, trip.destination.name)
                gravity = Gravity.CENTER
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                setPadding(0, dp(12), 0, dp(16))
            },
        )
        box.addView(
            MaterialButton(this).apply {
                setText(R.string.pl_done)
                setOnClickListener {
                    TripStore.stop(this@LiveTripActivity, arrived = true)
                    finish()
                }
            },
        )
    }

    private fun osmandButton(parent: ViewGroup, to: Call) {
        parent.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                setText(R.string.pl_walk_in_osmand)
                setIconResource(R.drawable.pl_ic_navigation)
                setOnClickListener { ItineraryActivity.walkWithOsmAnd(this@LiveTripActivity, to) }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(8), dp(16), dp(4))
            },
        )
    }

    /** "200 m · be there by 18:49", or just by when. */
    private fun walkText(leg: Leg, by: Long): String {
        val distance = leg.distance.takeIf { it > 0 }?.let { getString(R.string.pl_walk_distance, ItineraryViews.roundMeters(it)) }
        return if (distance != null) getString(R.string.pl_be_there_by, distance, TransitFormat.clock(by))
        else getString(R.string.pl_arrive_at, TransitFormat.clock(by))
    }

    /** "live, 2 min late", "live", or that it's by the timetable. */
    private fun status(call: Call): String {
        if (!call.isLive) return getString(R.string.pl_by_timetable)
        val minutes = call.delayMinutes
        val live = getString(TtR.string.tt_live)
        return when {
            minutes > 0 -> "$live, ${getString(TtR.string.tt_late, minutes)}"
            minutes < 0 -> "$live, ${getString(TtR.string.tt_early, -minutes)}"
            else -> live
        }
    }

    private fun icon(step: TripSteps.Step, leg: Leg): Int = when (step.kind) {
        Kind.WALK, Kind.WALK_THERE -> R.drawable.pl_ic_walk
        Kind.BOARD, Kind.RIDE -> Mode.of(leg.ride?.mode).icon
        Kind.ARRIVED -> R.drawable.pl_ic_place
    }

    private fun color(step: TripSteps.Step, leg: Leg): Int = when (step.kind) {
        Kind.BOARD, Kind.RIDE -> Mode.of(leg.ride?.mode).color
        else -> getColor(R.color.pl_go)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_VIEWING = "viewing"

        /** Countdowns go by the minute; this keeps them close. */
        private const val TICK_MS = 5_000L
        private const val PROGRESS_MAX = 1000
        private const val MAX_OPTIONS = 5

        /** The trip being taken: from its notification, OsmAnd's widget, the Trips tab, or GO. */
        fun intent(context: Context): Intent = Intent(context, LiveTripActivity::class.java)
    }
}
