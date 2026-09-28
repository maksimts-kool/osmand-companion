package dev.maksim.companion.timetable

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
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
