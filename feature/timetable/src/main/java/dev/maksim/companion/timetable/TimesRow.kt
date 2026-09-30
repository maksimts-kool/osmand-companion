package dev.maksim.companion.timetable

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import kotlin.math.max

/**
 * One line of departure times, left to right: as many as fit whole, the rest left out. So a route's next few
 * departures take a single line on any screen width.
 */
class TimesRow(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    private val gap = (GAP_DP * resources.displayMetrics.density).toInt()

    /** How many children fit, as of the last measure. */
    private var shown = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var used = 0
        var height = 0
        shown = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(unspecified, getChildMeasureSpec(heightMeasureSpec, 0, child.layoutParams.height))
            val start = if (shown == 0) 0 else used + gap
            // The first one always shows, if need be cut short.
            if (shown > 0 && start + child.measuredWidth > width) break
            used = start + child.measuredWidth
            height = max(height, child.measuredHeight)
            shown++
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (i < shown) {
                child.layout(x, 0, minOf(x + child.measuredWidth, width), child.measuredHeight)
                x += child.measuredWidth + gap
            } else {
                child.layout(0, 0, 0, 0)
            }
        }
    }

    private companion object {
        const val GAP_DP = 6
    }
}
