package dev.maksim.companion.timetable

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * Lays out an hour's minutes in equal cells, wrapping to further rows. Every hour of a route has the same
 * width, so the minutes line up in columns down the whole timetable.
 */
class MinuteGrid(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    private val minCell = (MIN_CELL_DP * resources.displayMetrics.density).toInt()
    private val gap = (GAP_DP * resources.displayMetrics.density).toInt()
    private var columns = 1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        columns = max(1, width / minCell)
        val cell = MeasureSpec.makeMeasureSpec(width / columns, MeasureSpec.EXACTLY)
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(cell, getChildMeasureSpec(heightMeasureSpec, 0, child.layoutParams.height))
            rowHeight = max(rowHeight, child.measuredHeight)
        }
        val rows = (childCount + columns - 1) / columns
        setMeasuredDimension(width, max(0, rows * rowHeight + (rows - 1) * gap))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child: View = getChildAt(i)
            val x = i % columns * child.measuredWidth
            val y = i / columns * (child.measuredHeight + gap)
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    private companion object {
        const val MIN_CELL_DP = 44
        const val GAP_DP = 4
    }
}
