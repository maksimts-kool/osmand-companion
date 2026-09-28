package dev.maksim.companion.timetable

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import dev.maksim.companion.timetable.databinding.TtHeaderBinding
import dev.maksim.companion.timetable.databinding.TtItemFactBinding
import dev.maksim.companion.timetable.databinding.TtItemRowBinding
import dev.maksim.companion.timetable.databinding.TtItemSectionBinding

/** Small builders for the timetable screens, which are short lists laid out top to bottom. */
internal object Rows {

    fun section(parent: ViewGroup, text: CharSequence) {
        TtItemSectionBinding.inflate(LayoutInflater.from(parent.context), parent, true).root.text = text
    }

    fun row(parent: ViewGroup): TtItemRowBinding =
        TtItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, true)

    fun badge(view: TextView, route: String, mode: String?) {
        view.text = route
        view.backgroundTintList = ColorStateList.valueOf(Mode.of(mode).color)
        view.isVisible = true
    }

    /**
     * Fills the shared header of the stop and trip screens. [route] goes next to the vehicle icon in the badge;
     * [facts] are icon and text pairs shown as pills under the title.
     */
    fun header(
        header: TtHeaderBinding,
        mode: Mode,
        route: String?,
        title: CharSequence,
        subtitle: CharSequence?,
        facts: List<Pair<Int, CharSequence>> = emptyList(),
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
        header.facts.isVisible = facts.isNotEmpty()
        for ((icon, text) in facts) TtItemFactBinding.inflate(LayoutInflater.from(card.context), header.facts, true).root.run {
            this.text = text
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        }
    }

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
