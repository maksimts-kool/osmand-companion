package dev.maksim.companion.timetable

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.color.MaterialColors
import dev.maksim.companion.timetable.databinding.TtHeaderBinding

/**
 * The routes in a stop's header ([Rows.header] puts them in its facts): all of them with the timetable at its top,
 * and one row of them ending in "…" once it's scrolled down, so a busy stop's routes don't take half the screen.
 * The "…" goes back to the top.
 *
 * The folding follows the scroll, like a collapsing app bar: over the first [saved] pixels scrolled, the header's
 * bottom edge moves up with the timetable, hiding the lower rows, and the first row's last routes fade into the
 * "…". The header floats over the timetable ([top]), which keeps room for all of it ([content]'s top padding),
 * so folding never moves the timetable, and there's nothing to go out of step when you scroll up and down fast.
 */
internal class HeaderLines(
    private val header: TtHeaderBinding,
    private val top: View,
    private val scroll: ScrollView,
    private val content: View,
) {

    private val facts = header.facts
    private val more = header.more.root

    /** The routes' height with all rows, and with the first only. */
    private var fullHeight = 0
    private var rowHeight = 0
    private val saved get() = fullHeight - rowHeight

    /** The first row's routes whose place the "…" takes, and the routes below the first row. */
    private var replaced: List<View> = emptyList()
    private var below: List<View> = emptyList()

    init {
        more.text = "…"
        more.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(more, com.google.android.material.R.attr.colorSurface))
        more.setTextColor(MaterialColors.getColor(more, com.google.android.material.R.attr.colorOnSurface))
        more.setOnClickListener { scroll.smoothScrollTo(0, 0) }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> apply() }
        // The content starts below the header as it is with every row showing.
        top.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val room = top.height + (fullHeight - facts.height).coerceAtLeast(0)
            if (content.paddingTop != room) content.updatePadding(top = room)
        }
    }

    /** After [Rows.header] has put the routes in (again, e.g. on a reload). */
    fun refresh() {
        facts.doOnLayout {
            measureRows()
            apply()
        }
    }

    /**
     * Works out the rows the way the routes' flow layout lays them out, from their widths, so it doesn't have to
     * show them all first.
     */
    private fun measureRows() {
        val lines = facts.children.toList()
        val inner = facts.width - facts.paddingLeft - facts.paddingRight
        if (lines.isEmpty() || inner <= 0) {
            fullHeight = 0
            rowHeight = 0
            replaced = emptyList()
            below = emptyList()
            return
        }
        val gap = facts.chipSpacingHorizontal
        val first = mutableListOf<Pair<View, Int>>()
        val rest = mutableListOf<View>()
        var x = 0
        var row = 0
        for (line in lines) {
            val width = widthOf(line)
            if (x > 0 && x + width > inner) {
                row++
                x = 0
            }
            if (row == 0) first += line to x else rest += line
            x += width + gap
        }
        facts.measure(
            View.MeasureSpec.makeMeasureSpec(facts.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        fullHeight = facts.measuredHeight
        rowHeight = facts.paddingTop + lines.first().measuredHeight + facts.paddingBottom
        below = rest
        // The routes that end past where the "…" has to start.
        val moreWidth = widthOf(more)
        val kept = first.filter { (line, at) -> at + line.measuredWidth + gap + moreWidth <= inner }
        replaced = if (rest.isEmpty()) emptyList() else first.drop(kept.size).map { it.first }
        val end = kept.lastOrNull()?.let { (line, at) -> at + line.measuredWidth + gap } ?: 0
        more.translationX = (facts.left + facts.paddingLeft + end).toFloat()
        more.translationY = (facts.top + facts.paddingTop).toFloat()
    }

    /**
     * Where to scroll so that [view], in the content, sits [margin] below the header; the header is folded by then
     * unless it's near the top.
     */
    fun scrollTo(view: View, margin: Int): Int =
        (view.top - (content.paddingTop - saved) - margin).coerceAtLeast(0)

    /** Folds as far as the timetable is scrolled. */
    private fun apply() {
        if (below.isEmpty()) {
            more.isVisible = false
            if (facts.layoutParams.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                facts.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                facts.requestLayout()
            }
            return
        }
        val y = scroll.scrollY.coerceIn(0, saved)
        val folded = y.toFloat() / saved
        val height = fullHeight - y
        if (facts.layoutParams.height != height) {
            facts.layoutParams.height = height
            facts.requestLayout()
        }
        below.forEach { it.fade(1 - folded) }
        // The "…" swaps in over the last stretch, once the lower rows are mostly gone.
        val swap = ((folded - SWAP_FROM) / (1 - SWAP_FROM)).coerceIn(0f, 1f)
        replaced.forEach { it.fade(1 - swap) }
        more.isVisible = swap > 0f
        more.alpha = swap
    }

    /** [alpha] of what the view has of its own (a route not running that day is dimmed). */
    private fun View.fade(alpha: Float) {
        val own = getTag(R.id.tt_base_alpha) as? Float ?: this.alpha.also { setTag(R.id.tt_base_alpha, it) }
        this.alpha = own * alpha
        // A faded-out route isn't there to tap; the "…" is on top of the ones it replaces.
        isClickable = alpha > 0.5f && hasOnClickListeners()
    }

    private fun widthOf(view: View): Int {
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        return view.measuredWidth
    }

    private companion object {
        /** How far into the folding the "…" starts to replace the first row's last routes. */
        const val SWAP_FROM = 0.6f
    }
}
