package dev.maksim.companion.timetable

import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.google.android.material.color.MaterialColors
import dev.maksim.companion.timetable.databinding.TtHeaderBinding
import dev.maksim.companion.timetable.databinding.TtItemFactBinding
import dev.maksim.companion.timetable.databinding.TtItemLineBinding
import dev.maksim.companion.timetable.databinding.TtItemLiveFactBinding
import dev.maksim.companion.timetable.databinding.TtItemOfflineFactBinding
import dev.maksim.companion.timetable.databinding.TtItemRowBinding
import dev.maksim.companion.timetable.databinding.TtItemSectionBinding

/** Small builders for the timetable screens, which are short lists laid out top to bottom. */
object Rows {

    fun section(parent: ViewGroup, text: CharSequence) {
        TtItemSectionBinding.inflate(LayoutInflater.from(parent.context), parent, true).root.text = text
    }

    fun row(parent: ViewGroup): TtItemRowBinding =
        TtItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, true)

    /**
     * The mark before a departure time that's live, a dot sending out waves ([R.raw.tt_anim_live], from
     * LottieFiles, recolored to [color]); hidden when it's from the timetable.
     */
    fun liveMark(view: LottieAnimationView, live: Boolean, @ColorInt color: Int) {
        view.isVisible = live
        if (!live) {
            view.pauseAnimation()
            return
        }
        val filter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        view.addValueCallback(KeyPath("**"), LottieProperty.COLOR_FILTER) { filter }
        view.resumeAnimation()
    }

    fun badge(view: TextView, route: String, mode: String?) {
        view.text = route
        view.backgroundTintList = ColorStateList.valueOf(Mode.of(mode).color)
        view.isVisible = true
    }

    /**
     * Fills the shared header of the stop and trip screens. [route] goes next to the vehicle icon in the badge;
     * [facts] are icon and text pairs shown as pills under the title. A stop shows its [lines] there instead, big
     * and in their own colors, as they're what you look for first; [onLine] makes them tappable, and those not in
     * [running] are dimmed. A trip says first whether its vehicle gives its times, once for all its stops: [live],
     * or with false that it doesn't (null for neither).
     */
    fun header(
        header: TtHeaderBinding,
        mode: Mode,
        route: String?,
        title: CharSequence,
        subtitle: CharSequence?,
        facts: List<Pair<Int, CharSequence>> = emptyList(),
        lines: List<Line> = emptyList(),
        running: Set<String>? = null,
        onLine: ((Line) -> Unit)? = null,
        live: Boolean? = null,
    ) {
        val card = header.card
        card.setCardBackgroundColor(
            ColorUtils.compositeColors(
                ColorUtils.setAlphaComponent(mode.color, 0x24),
                MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurfaceContainerLow),
            ),
        )
        with(header.badge) {
            isVisible = true
            contentDescription = listOfNotNull(context.getString(mode.label), route).joinToString(" ")
            text = route
            backgroundTintList = ColorStateList.valueOf(mode.color)
            setCompoundDrawablesRelativeWithIntrinsicBounds(mode.icon, 0, 0, 0)
            compoundDrawablePadding = if (route.isNullOrEmpty()) 0 else resources.getDimensionPixelSize(R.dimen.tt_badge_gap)
        }
        header.title.text = title
        header.subtitle.text = subtitle
        header.subtitle.isVisible = !subtitle.isNullOrEmpty()
        header.facts.removeAllViews()
        header.facts.isVisible = facts.isNotEmpty() || lines.isNotEmpty() || live != null
        val inflater = LayoutInflater.from(card.context)
        if (live == true) TtItemLiveFactBinding.inflate(inflater, header.facts, true).run {
            val green = card.context.getColor(R.color.tt_live)
            root.backgroundTintList = ColorStateList.valueOf(green).withAlpha(LIVE_FACT_ALPHA)
            text.text = card.context.getString(R.string.tt_live).replaceFirstChar { it.titlecase() }
            text.setTextColor(green)
            liveMark(this.live, true, green)
        }
        if (live == false) TtItemOfflineFactBinding.inflate(inflater, header.facts, true).run {
            val grey = MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnSurfaceVariant)
            root.backgroundTintList = ColorStateList.valueOf(grey).withAlpha(OFFLINE_FACT_ALPHA)
            text.text = card.context.getString(R.string.tt_offline).replaceFirstChar { it.titlecase() }
            text.setTextColor(grey)
            // The live mark with both its waves showing, still.
            mark.setFrame(OFFLINE_MARK_FRAME)
            val filter = PorterDuffColorFilter(grey, PorterDuff.Mode.SRC_ATOP)
            mark.addValueCallback(KeyPath("**"), LottieProperty.COLOR_FILTER) { filter }
            strike.strike()
        }
        for ((icon, text) in facts) TtItemFactBinding.inflate(inflater, header.facts, true).root.run {
            this.text = text
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        }
        for (line in lines) TtItemLineBinding.inflate(inflater, header.facts, true).root.run {
            text = line.name
            contentDescription = context.getString(R.string.tt_route_line, line.name)
            backgroundTintList = ColorStateList.valueOf(Mode.of(line.mode).color)
            val runs = running == null || line.name in running
            alpha = if (runs) 1f else NOT_RUNNING_ALPHA
            if (onLine != null && runs) setOnClickListener { onLine(line) } else isClickable = false
        }
    }

    /** A route with no departures on the chosen day. */
    private const val NOT_RUNNING_ALPHA = 0.4f

    /** The live pill's green, behind its green text; the offline one's grey. */
    private const val LIVE_FACT_ALPHA = 0x29
    private const val OFFLINE_FACT_ALPHA = 0x1F

    /** A frame of tt_anim_live where both waves are fully in (38 to 48). */
    private const val OFFLINE_MARK_FRAME = 43

    /** A stop in a list: vehicle icon, name, and what serves it. */
    fun stop(parent: ViewGroup, stop: Stop, note: String?, onClick: () -> Unit) {
        val row = row(parent)
        val mode = Mode.of(stop.mode)
        row.time.isVisible = false
        row.badge.isVisible = false
        row.icon.isVisible = true
        row.icon.setImageResource(mode.icon)
        row.icon.setBackgroundResource(R.drawable.tt_badge_bg)
        row.icon.backgroundTintList = ColorStateList.valueOf(mode.color)
        row.title.text = stop.name
        val served = stop.routes.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: stop.departures.map { it.route }.distinct().sortedWith(RouteOrder).joinToString(", ")
        val context = parent.context
        row.subtitle.text = listOfNotNull(context.getString(mode.label), served.ifEmpty { null }, stop.code)
            .joinToString(" · ")
        row.subtitle.isVisible = true
        row.note.text = note
        row.root.setOnClickListener { onClick() }
    }
}
