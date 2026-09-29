package dev.maksim.companion.timetable

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

/**
 * One stop's piece of a trip's line diagram: the line coming from the stop before, the stop's dot, and the
 * line going on to the next one. Stacked row under row they draw the whole route. The part the vehicle has
 * already covered is grey; the vehicle itself is a [VehicleMarker] drawn over the rows.
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
    }

    private companion object {
        const val LINE_DP = 5f
        const val DOT_DP = 7f
        const val BIG_DOT_DP = 9f
        const val DOT_CORE_DP = 3.5f
        const val RING_DP = 2.5f
        const val HALO_DP = 15f
    }
}

/**
 * The vehicle on a trip's line diagram: the mode's icon in a circle of its color, ringed in the background color
 * so it stands out from the line. It usually sits between two stops' rows, so it's drawn over all of them (in
 * their parent's overlay) rather than by one row, which would have the other row cover half of it.
 */
class VehicleMarker(context: Context, mode: Mode) : Drawable() {

    private val density = context.resources.displayMetrics.density
    private val color = mode.color
    private val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE)
    private val icon = ContextCompat.getDrawable(context, mode.icon)!!.mutate().apply { setTint(Color.WHITE) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Its size across, ring included. */
    val size = ((RADIUS_DP + RING_DP) * 2 * density).toInt()

    /** Centers it on [x], [y] in the overlay's view. */
    fun moveTo(x: Float, y: Float) {
        setBounds((x - size / 2f).toInt(), (y - size / 2f).toInt(), (x + size / 2f).toInt(), (y + size / 2f).toInt())
    }

    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val r = RADIUS_DP * density
        fill.color = surface
        canvas.drawCircle(cx, cy, r + RING_DP * density, fill)
        fill.color = color
        canvas.drawCircle(cx, cy, r, fill)
        val ir = (r * ICON_SCALE).toInt()
        icon.setBounds(cx.toInt() - ir, cy.toInt() - ir, cx.toInt() + ir, cy.toInt() + ir)
        icon.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val RADIUS_DP = 13f
        const val RING_DP = 2.5f
        const val ICON_SCALE = 0.62f
    }
}
