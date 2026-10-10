package dev.maksim.companion.timetable

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/**
 * A stop's routes in its header: in as many rows as they need, as even as can be (15 in three rows are 5, 5, 5,
 * not 6, 6, 3), each row's badges widened alike so that every row, the last too, reaches the right end. A row of
 * only a few doesn't: its badges are widened to [widest] at most, and it ends short of the edge.
 */
class JustifiedRows @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    /** Between badges, and between rows. */
    val gap: Int = (GAP_DP * resources.displayMetrics.density).roundToInt()

    /** How wide a badge is widened to at most (one whose number is wider than that stays as wide as it is). */
    val widest: Int = (WIDEST_DP * resources.displayMetrics.density).roundToInt()

    /** A badge as laid out: where it starts in its row (from the padding) and how wide it is. */
    class Placed(val view: View, val x: Int, val width: Int)

    /** The rows as last measured, top to bottom. */
    var rows: List<List<Placed>> = emptyList()
        private set

    private var rowHeight = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = width - paddingLeft - paddingRight
        val views = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }
        // Their natural width, at the height their layout gives them (36dp for a route).
        views.forEach {
            val height = it.layoutParams.height
            it.measure(
                MeasureSpec.UNSPECIFIED,
                if (height > 0) MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY) else MeasureSpec.UNSPECIFIED,
            )
        }
        val natural = views.map { it.measuredWidth }
        rowHeight = views.maxOfOrNull { it.measuredHeight } ?: 0
        rows = if (inner <= 0 || views.isEmpty()) emptyList() else split(natural, inner).map { range ->
            stretch(views.subList(range.first, range.last + 1), natural.subList(range.first, range.last + 1), inner)
        }
        for (row in rows) for (placed in row) {
            placed.view.measure(
                MeasureSpec.makeMeasureSpec(placed.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY),
            )
        }
        val height = paddingTop + paddingBottom + rows.size * rowHeight + (rows.size - 1).coerceAtLeast(0) * gap
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    /**
     * Which badges go in which row: as many rows as they'd take one after another, with the same number in each
     * (the first rows one more), unless that leaves a row too wide; then one after another.
     */
    private fun split(widths: List<Int>, inner: Int): List<IntRange> {
        val flowing = ArrayList<IntRange>()
        var start = 0
        var x = 0
        for ((i, w) in widths.withIndex()) {
            if (i > start && x + w > inner) {
                flowing += start until i
                start = i
                x = 0
            }
            x += w + gap
        }
        flowing += start until widths.size
        val count = flowing.size
        val even = ArrayList<IntRange>()
        var from = 0
        for (row in 0 until count) {
            val size = widths.size / count + if (row < widths.size % count) 1 else 0
            even += from until from + size
            from += size
        }
        val fits = even.all { range -> range.sumOf { widths[it] } + gap * (range.count() - 1) <= inner }
        return if (fits) even else flowing
    }

    /** A row's badges, each widened by the same share of what's left of [inner], up to [widest]. */
    private fun stretch(views: List<View>, widths: List<Int>, inner: Int): List<Placed> {
        val spare = (inner - widths.sum() - gap * (views.size - 1)).coerceAtLeast(0)
        var x = 0
        return views.mapIndexed { i, view ->
            // Spread so the last one ends right at the edge.
            val extra = spare * (i + 1) / views.size - spare * i / views.size
            val width = minOf(widths[i] + extra, maxOf(widths[i], widest))
            Placed(view, x, width).also { x += it.width + gap }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for ((i, row) in rows.withIndex()) {
            val top = paddingTop + i * (rowHeight + gap)
            for (placed in row) {
                val left = paddingLeft + placed.x
                placed.view.layout(left, top, left + placed.width, top + rowHeight)
            }
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

    private companion object {
        const val GAP_DP = 8

        /** So a row of five reaches the edge on a phone, and one of two or three doesn't turn into bars. */
        const val WIDEST_DP = 72
    }
}
