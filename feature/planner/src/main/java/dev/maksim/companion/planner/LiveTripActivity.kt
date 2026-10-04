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
import androidx.activity.addCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import dev.maksim.companion.core.Analytics
import dev.maksim.companion.core.companion
import dev.maksim.companion.core.padForSystemBars
import dev.maksim.companion.planner.TripSteps.Kind
import dev.maksim.companion.planner.databinding.PlActivityLiveBinding
import dev.maksim.companion.planner.databinding.PlItemCountdownBinding
import dev.maksim.companion.planner.databinding.PlItemStepBinding
import dev.maksim.companion.timetable.Arrows
import dev.maksim.companion.timetable.Mode
import dev.maksim.companion.timetable.Rows
import dev.maksim.companion.timetable.TransitFormat
import dev.maksim.companion.timetable.TripLineView
import java.util.concurrent.Executors
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

    private val background = Executors.newSingleThreadExecutor()

    /** Where OsmAnd has you, and its navigation to where the trip walks to now (asked about), if any. */
    private var position: Pair<Call, OsmAndTrip.Position>? = null
    private var positionAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            render()
            askOsmAnd()
            binding.root.postDelayed(this, TICK_MS)
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
        background.shutdownNow()
        super.onDestroy()
    }

    /**
     * Asks OsmAnd where you are, and whether it's walking you to where the trip walks to now (the stop of the next
     * ride, or the end): how far along the walk it is goes by that, its ETA for the planner's guess, and once you're
     * there, the trip goes on to the next step ([TripPosition.update]), however early.
     */
    private fun askOsmAnd() {
        val trip = TripStore.current(this) ?: return
        val now = System.currentTimeMillis()
        val target = OsmAndTrip.walkTarget(trip.itinerary, TripProgress.at(trip.itinerary, now, trip.reached)) ?: run {
            position = null
            return
        }
        val osmand = companion.osmand
        background.execute {
            val found = runCatching { OsmAndTrip.position(osmand, target) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                // OsmAnd was navigating there a moment ago, and has stopped: it got you there.
                val before = position?.takeIf { (to, _) -> to.lat == target.lat && to.lon == target.lon }?.second?.navigation
                position = found?.let { target to it }
                positionAt = System.currentTimeMillis()
                val latest = TripStore.current(this) ?: return@runOnUiThread
                val updated = TripPosition.update(latest, found?.here, found?.navigation, positionAt, TripPosition.osmandArrived(before, found))
                // Saving it renders it.
                if (updated !== latest) TripStore.save(this, updated) else render()
            }
        }
    }

    /** OsmAnd's position, if it said lately. */
    private fun fresh(): Pair<Call, OsmAndTrip.Position>? = position?.takeIf { System.currentTimeMillis() - positionAt < POSITION_FRESH_MS }

    /** OsmAnd's ETA to [call], if it's walking you there and said so lately. */
    private fun navigationTo(call: Call): OsmAndTrip.Navigation? =
        fresh()?.takeIf { (to, _) -> to.lat == call.lat && to.lon == call.lon }?.second?.navigation

    /** The walk being walked now, by its index, and how far along it is; null if none, or OsmAnd hasn't said. */
    private fun walking(trip: ActiveTrip, now: Long): Pair<Int, TripPosition.Walked>? {
        val itinerary = trip.itinerary
        val index = TripPosition.walkingLeg(itinerary, TripProgress.at(itinerary, now, trip.reached), trip.reached) ?: return null
        val leg = itinerary.legs[index]
        return TripPosition.walked(leg, fresh()?.second?.here, navigationTo(leg.to))?.let { index to it }
    }

    private fun look(by: Int) {
        val trip = TripStore.current(this) ?: return finish()
        val steps = TripSteps.of(trip.itinerary)
        val located = fresh()?.second?.here != null
        val at = (viewing ?: TripSteps.current(trip.itinerary, steps, System.currentTimeMillis(), trip.reached, located)) + by
        viewing = at.coerceIn(0, steps.lastIndex)
        render()
    }

    private fun render() {
        val trip = TripStore.current(this) ?: return finish()
        val itinerary = trip.itinerary
        val now = System.currentTimeMillis()
        val steps = TripSteps.of(itinerary)
        val walking = walking(trip, now)
        val current = TripSteps.current(itinerary, steps, now, trip.reached, located = fresh()?.second?.here != null)
        // On to the next step: follow it again.
        if (current != lastCurrent) {
            if (lastCurrent >= 0) viewing = null
            lastCurrent = current
        }
        val shown = (viewing ?: current).coerceIn(0, steps.lastIndex)
        val live = getColor(TtR.color.tt_live)

        // On the last walk, OsmAnd's ETA, if it's walking you there.
        val end = itinerary.legs.last().takeIf { it.isWalk && steps[current].kind == Kind.WALK_THERE }
            ?.let { navigationTo(it.to)?.arrival } ?: itinerary.end
        binding.left.text = ItineraryViews.countdown(end - now).toString()
        binding.eta.text = TransitFormat.clock(end)
        Rows.liveMark(binding.live, itinerary.isLive, live)
        binding.pill.contentDescription = getString(R.string.pl_trip_arrive, TransitFormat.clock(itinerary.end), trip.destination.name)
        val fraction = TripSteps.fraction(
            itinerary, now,
            walking?.let { (index, walked) -> index to walked.fraction } ?: TripPosition.walkedTo(trip, TripProgress.at(itinerary, now, trip.reached)),
        )
        binding.tripProgress.setProgressCompat((fraction * PROGRESS_MAX).toInt(), true)

        instruction(trip, steps[shown], isCurrent = shown == current, now, walking)
        dots(steps.size, shown, current)
        binding.prev.isEnabled = shown > 0
        binding.next.isEnabled = shown < steps.lastIndex
        binding.now.isVisible = shown != current
        page(trip, steps[shown], shown == current, now, walking?.takeIf { shown == current })
    }

    /** What to do at [step], as the card on top says it: "Walk to stop · Koidu · 3 min". */
    private fun instruction(trip: ActiveTrip, step: TripSteps.Step, isCurrent: Boolean, now: Long, walking: Pair<Int, TripPosition.Walked>?) {
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
        val primary = MaterialColors.getColor(binding.away, androidx.appcompat.R.attr.colorPrimary)
        // On the way, by where OsmAnd has you: OsmAnd's ETA if it's walking you, else how far there is to go.
        val walk = walking?.takeIf { isCurrent }
        if (walk != null) {
            val (index, walked) = walk
            val walkLeg = legs[index]
            val osmand = navigationTo(walkLeg.to)
            if (osmand != null || walked.fraction >= STARTED || now >= walkLeg.departure) {
                binding.headline.text = if (index == legs.lastIndex) trip.destination.name else walkLeg.to.name
                binding.kicker.setText(if (osmand != null) R.string.pl_walking_osmand else R.string.pl_walking)
                binding.kicker.isVisible = true
                val soon = osmand?.let { TransitFormat.relative(this, it.arrival, now) }
                when {
                    osmand == null -> away(ItineraryViews.distance(this, walked.metersLeft.toDouble()), R.string.pl_cap_to_go, primary)
                    soon != null -> away(soon, R.string.pl_cap_there_in, primary)
                    else -> away(TransitFormat.clock(osmand.arrival), R.string.pl_cap_there_by, primary)
                }
                return
            }
        }
        // Got to the stop: waiting there.
        if (step.kind == Kind.BOARD && isCurrent && TripProgress.stopKey(leg.from) in trip.reached) {
            Arrows.set(binding.kicker, "${getString(R.string.pl_at_stop)} · $kicker")
            binding.kicker.isVisible = true
            val timeColor = if (leg.from.isLive) getColor(TtR.color.tt_live) else primary
            val soon = soon(leg.departure, now)
            if (soon != null) away(soon, R.string.pl_cap_leaves_in, timeColor)
            else away(TransitFormat.clock(leg.departure), R.string.pl_cap_leaves, timeColor)
            return
        }
        // Not yet left: when to (how long the walk is, the card under it says).
        if (step.kind == Kind.WALK && isCurrent && now < leg.departure) {
            binding.kicker.setText(R.string.pl_walk_to_stop)
            binding.kicker.isVisible = true
            val soon = soon(leg.departure, now)
            if (soon != null) away(soon, R.string.pl_cap_leave_in, primary)
            else away(TransitFormat.relative(this, leg.departure, now) ?: TransitFormat.clock(leg.departure), R.string.pl_cap_leave, primary)
            return
        }
        Arrows.set(binding.kicker, kicker)
        binding.kicker.isVisible = kicker.isNotEmpty()
        if (until == null) {
            binding.away.isVisible = false
            binding.awayCaption.isVisible = false
            return
        }
        val liveTime = (step.kind == Kind.BOARD && leg.from.isLive) || (step.kind == Kind.RIDE && leg.to.isLive)
        val timeColor = if (liveTime) getColor(TtR.color.tt_live) else primary
        val soon = if (isCurrent || until > now) soon(until, now) else null
        val caption = when (step.kind) {
            Kind.BOARD -> if (soon != null) R.string.pl_cap_leaves_in else R.string.pl_cap_leaves
            Kind.RIDE -> if (soon != null) R.string.pl_cap_get_off_in else R.string.pl_cap_get_off
            else -> if (soon != null) R.string.pl_cap_there_in else R.string.pl_cap_there_by
        }
        away(soon ?: TransitFormat.clock(until), caption, timeColor)
    }

    /** The top card's number, [caption]ed with what it is. */
    private fun away(text: String, @StringRes caption: Int, color: Int) {
        binding.away.isVisible = true
        binding.away.text = text
        binding.away.setTextColor(color)
        binding.awayCaption.isVisible = true
        binding.awayCaption.setText(caption)
    }

    /** "13 min" to [time], if it's ahead and in the next minute or more (so it reads after "leaves in"). */
    private fun soon(time: Long, now: Long): String? =
        TransitFormat.relative(this, time, now)?.takeIf { time - now >= 60_000 }

    /** Says what [card]'s value is. */
    private fun caption(card: PlItemStepBinding, @StringRes caption: Int) {
        card.caption.isVisible = true
        card.caption.setText(caption)
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
    private fun page(trip: ActiveTrip, step: TripSteps.Step, isCurrent: Boolean, now: Long, walking: Pair<Int, TripPosition.Walked>?) {
        val key = "${trip.version}:$step"
        if (step.kind == Kind.ARRIVED && key == pageKey) return
        pageKey = key
        val page = binding.page
        page.removeAllViews()
        val itinerary = trip.itinerary
        val leg = itinerary.legs[step.leg]
        when (step.kind) {
            Kind.WALK -> walkPage(page, itinerary, step.leg, leg, now, walking?.takeIf { it.first == step.leg }?.second)
            Kind.BOARD -> boardPage(page, trip, step.leg, leg, now)
            Kind.RIDE -> ridePage(page, trip, leg, isCurrent, now)
            Kind.WALK_THERE -> {
                val card = ItineraryActivity.stepCard(layoutInflater, page, R.drawable.pl_ic_walk, getColor(R.color.pl_go))
                card.title.setText(R.string.pl_walk_to_destination)
                val osmand = navigationTo(leg.to)
                if (osmand != null) {
                    ItineraryActivity.subtitle(card, osmandText(osmand))
                    ItineraryActivity.minutes(card, osmand.left, countdown = true)
                    caption(card, R.string.pl_cap_to_go)
                } else {
                    ItineraryActivity.subtitle(card, walkText(leg, trip.itinerary.end))
                    ItineraryActivity.minutes(card, maxOf(leg.duration, 60_000L))
                    caption(card, R.string.pl_cap_walk)
                }
                card.body.isVisible = true
                walking?.takeIf { it.first == step.leg }?.let { walkProgress(card.body, it.second) }
                osmandButton(card.body, leg.to.copy(name = trip.destination.name))
            }
            Kind.ARRIVED -> arrivedPage(page, trip)
        }
    }

    /** Walk to the stop: how far, by when, the ride it's for, and OsmAnd to walk there with. */
    private fun walkPage(page: ViewGroup, itinerary: Itinerary, index: Int, leg: Leg, now: Long, walked: TripPosition.Walked?) {
        val card = ItineraryActivity.stepCard(layoutInflater, page, R.drawable.pl_ic_walk, getColor(R.color.pl_go))
        card.title.text = getString(R.string.pl_step_walk, leg.to.name)
        val osmand = navigationTo(leg.to)
        if (osmand != null) {
            ItineraryActivity.subtitle(card, osmandText(osmand))
            ItineraryActivity.minutes(card, osmand.left, countdown = true)
            caption(card, R.string.pl_cap_to_go)
        } else {
            ItineraryActivity.subtitle(card, walkText(leg, leg.arrival))
            ItineraryActivity.minutes(card, maxOf(leg.duration, 60_000L))
            caption(card, R.string.pl_cap_walk)
        }
        card.body.isVisible = true
        walked?.let { walkProgress(card.body, it) }
        val next = itinerary.legs.drop(index + 1).firstOrNull { !it.isWalk }
        if (osmand != null && next != null && next.departure >= now) catchLine(card.body, osmand, next)
        next?.let { next ->
            val ride = next.ride!!
            card.body.addView(
                TextView(this).apply {
                    Arrows.set(
                        this,
                        getString(
                            R.string.pl_then_catch, "${ItineraryViews.vehicle(this@LiveTripActivity, ride)} → ${ride.headsign}",
                            TransitFormat.clock(next.departure),
                        ),
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
        Arrows.set(card.headsign, "→ ${ride.headsign}")
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
        // Still walking there with OsmAnd: whether it'll be in time.
        navigationTo(leg.from)?.takeIf { leg.departure >= now }?.let { catchLine(card.content, it, leg) }

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
        if (isCurrent) {
            ItineraryActivity.minutes(card, leg.arrival - now, countdown = true)
            caption(card, R.string.pl_cap_to_go)
        } else {
            ItineraryActivity.minutes(card, leg.duration)
            caption(card, R.string.pl_cap_ride)
        }
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

    /** "OsmAnd: 420 m · there at 19:24". */
    private fun osmandText(navigation: OsmAndTrip.Navigation): String = getString(
        R.string.pl_osmand_eta, ItineraryViews.distance(this, navigation.meters.toDouble()), TransitFormat.clock(navigation.arrival),
    )

    /** How far along the walk it is, by where OsmAnd has you: a bar, and "220 m to go". */
    private fun walkProgress(parent: ViewGroup, walked: TripPosition.Walked) {
        parent.addView(
            LinearProgressIndicator(this).apply {
                max = PROGRESS_MAX
                setIndicatorColor(getColor(R.color.pl_go))
                trackCornerRadius = dp(3)
                trackThickness = dp(6)
                setProgressCompat((walked.fraction * PROGRESS_MAX).toInt(), false)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(2), dp(16), dp(4))
            },
        )
        parent.addView(
            TextView(this).apply {
                text = getString(R.string.pl_to_go, ItineraryViews.distance(this@LiveTripActivity, walked.metersLeft.toDouble()))
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(dp(16), 0, dp(16), dp(6))
            },
        )
    }

    /**
     * Whether OsmAnd has you at [ride]'s stop before it leaves: "You'll be there 3 min before Bus 24A leaves", in
     * green; or, in red, by how much it'll be missed at this pace.
     */
    private fun catchLine(parent: ViewGroup, navigation: OsmAndTrip.Navigation, ride: Leg) {
        val margin = ride.departure - navigation.arrival
        val vehicle = ItineraryViews.vehicle(this, ride.ride!!)
        val missed = margin < 0
        parent.addView(
            TextView(this).apply {
                text = if (missed) getString(R.string.pl_eta_miss, vehicle, ItineraryViews.duration(this@LiveTripActivity, -margin))
                else getString(R.string.pl_eta_ahead, ItineraryViews.duration(this@LiveTripActivity, margin), vehicle)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                val color = getColor(
                    when {
                        missed -> TtR.color.tt_offline_strike
                        margin < ItineraryViews.TIGHT_MS -> TtR.color.tt_late
                        else -> TtR.color.tt_live
                    },
                )
                setTextColor(color)
                if (missed || margin < ItineraryViews.TIGHT_MS) {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(TtR.drawable.tt_ic_warning, 0, 0, 0)
                    compoundDrawableTintList = ColorStateList.valueOf(color)
                    compoundDrawablePadding = dp(6)
                }
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(4), dp(16), dp(6))
            },
        )
    }

    /** "200 m · be there by 18:49", or just by when. */
    private fun walkText(leg: Leg, by: Long): String {
        val distance = leg.distance.takeIf { it > 0 }?.let { ItineraryViews.distance(this, it) }
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

        /** OsmAnd is asked every tick; what it said longer ago than this is gone (it stopped answering). */
        private const val POSITION_FRESH_MS = 3 * TICK_MS

        /** This far along a walk before leaving time, it's being walked already. */
        private const val STARTED = 0.1f

        /** The trip being taken: from its notification, OsmAnd's widget, the Trips tab, or GO. */
        fun intent(context: Context): Intent = Intent(context, LiveTripActivity::class.java)
    }
}
