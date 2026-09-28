package dev.maksim.companion.timetable

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

/**
 * One stop's piece of a trip's line diagram: the line coming from the stop before, the stop's dot, and the
 * line going on to the next one. Stacked row under row they draw the whole route. The part the vehicle has
 * already covered is grey, and the vehicle itself sits on the line where it should be now.
 */
class TripLineView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Stop { FIRST, MIDDLE, LAST }

    var kind = Stop.MIDDLE
    var color = Color.GRAY
    /** The stop you came from, drawn larger with a halo. */
    var emphasized = false
    var passed = false

    /** How much of the line into this stop (top half) and out of it (bottom half) is behind the vehicle. */
    var passedIn = 0f
    var passedOut = 0f

    /** Where the vehicle is along this row, 0 at the top and 1 at the bottom; null when it's not here. */
    var vehicleAt: Float? = null
    var vehicleIcon: Drawable? = null
        set(value) {
            field = value?.mutate()?.apply { setTint(Color.WHITE) }
        }

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = LINE_DP * density }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val passedColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val h = height.toFloat()
        if (kind != Stop.FIRST) segment(canvas, cx, 0f, cy, passedIn)
        if (kind != Stop.LAST) segment(canvas, cx, cy, h, passedOut)

        val stopColor = if (passed && !emphasized) passedColor else color
        val r = dp(if (emphasized || kind != Stop.MIDDLE) BIG_DOT_DP else DOT_DP)
        if (emphasized) circle(canvas, cx, cy, dp(HALO_DP), ColorUtils.setAlphaComponent(color, 0x40))
        circle(canvas, cx, cy, r, stopColor)
        // Ends and your stop are solid; the ones in between are rings, like on a printed line map.
        val inner = if (emphasized || kind != Stop.MIDDLE) dp(DOT_CORE_DP) else r - dp(RING_DP)
        circle(canvas, cx, cy, inner, if (emphasized || kind != Stop.MIDDLE) Color.WHITE else surface)

        vehicleAt?.let { at ->
            // It may reach into the row above or below; the row carrying it is drawn over its neighbors.
            val vr = dp(VEHICLE_DP)
            val vy = at * h
            circle(canvas, cx, vy, vr + dp(RING_DP), surface)
            circle(canvas, cx, vy, vr, color)
            vehicleIcon?.let {
                val ir = (vr * ICON_SCALE).toInt()
                it.setBounds((cx - ir).toInt(), (vy - ir).toInt(), (cx + ir).toInt(), (vy + ir).toInt())
                it.draw(canvas)
            }
        }
    }

    /** A line from [top] to [bottom], grey for the first [passedPart] of it. */
    private fun segment(canvas: Canvas, x: Float, top: Float, bottom: Float, passedPart: Float) {
        val split = top + (bottom - top) * passedPart.coerceIn(0f, 1f)
        line.color = passedColor
        if (split > top) canvas.drawLine(x, top, x, split, line)
        line.color = color
        if (bottom > split) canvas.drawLine(x, split, x, bottom, line)
    }

    private fun circle(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        fill.color = color
        canvas.drawCircle(x, y, r, fill)
    }

    private fun dp(value: Float) = value * density

    fun setMode(mode: Mode) {
        color = mode.color
        vehicleIcon = ContextCompat.getDrawable(context, mode.icon)
    }

    private companion object {
        const val LINE_DP = 5f
        const val DOT_DP = 7f
        const val BIG_DOT_DP = 9f
        const val DOT_CORE_DP = 3.5f
        const val RING_DP = 2.5f
        const val HALO_DP = 15f
        const val VEHICLE_DP = 13f
        const val ICON_SCALE = 0.62f
    }
}
