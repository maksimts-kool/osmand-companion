package dev.maksim.companion.timetable

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.google.android.material.color.MaterialColors
import kotlin.math.hypot
import kotlin.math.min

/**
 * The card of the stop and trip screens' header (tt_header). Its first row, what you're looking at, sits between the
 * back and action buttons, which it sizes to its own height however many lines it takes; the card's top corners are
 * cut out around them with curved edges, and the rest runs full width under them. A corner is only cut
 * while its button shows, and the first row keeps clear of the cuts.
 */
class HeaderCard(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val cutWidth = CUT_WIDTH_DP * density
    private val outer = OUTER_RADIUS_DP * density
    private val inner = INNER_RADIUS_DP * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerLow, 0)
    }
    private val path = Path()

    /** How far down the cuts go: to the bottom of the first row. */
    private var cutHeight = 0f

    /** Whether the back (start) and action (end) corners are cut. */
    private var cutStart = false
    private var cutEnd = false

    /** Follows the buttons showing and hiding (the trip's action shows once it has loaded). */
    private val watch = ViewTreeObserver.OnGlobalLayoutListener { followButtons() }

    init {
        setWillNotDraw(false)
    }

    fun setCardBackgroundColor(@ColorInt color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(watch)
        followButtons()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalLayoutListener(watch)
        super.onDetachedFromWindow()
    }

    private fun followButtons() {
        val parent = parent as? ViewGroup ?: return
        val start = parent.findViewById<View>(R.id.back)?.isVisible == true
        val end = parent.findViewById<View>(R.id.action)?.isVisible == true
        if (start == cutStart && end == cutEnd) return
        cutStart = start
        cutEnd = end
        getChildAt(0)?.updateLayoutParams<MarginLayoutParams> {
            marginStart = if (start) cutWidth.toInt() else 0
            marginEnd = if (end) cutWidth.toInt() else 0
        }
        outline()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val row = getChildAt(0) ?: return
        val parent = parent as? ViewGroup ?: return
        // The buttons span the row's content, round while it's one line, taller pills beside more; its bottom padding
        // is the gap between them and the card under them. Here, as tt_header measures the buttons after the card.
        val top = (layoutParams as MarginLayoutParams).topMargin + paddingTop + row.paddingTop
        val height = row.measuredHeight - row.paddingTop - row.paddingBottom
        for (id in intArrayOf(R.id.back, R.id.action)) {
            val button = parent.findViewById<View>(id) ?: continue
            val params = button.layoutParams as MarginLayoutParams
            if (params.height != height || params.topMargin != top) {
                params.height = height
                params.topMargin = top
                button.layoutParams = params
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val row = getChildAt(0) ?: return
        val cut = row.bottom.toFloat()
        if (changed || cut != cutHeight) {
            cutHeight = cut
            outline()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, paint)
        super.onDraw(canvas)
    }

    /** The card's edge, clockwise from its bottom left, each corner rounded. */
    private fun outline() {
        path.rewind()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val cutLeft = if (rtl) cutEnd else cutStart
        val cutRight = if (rtl) cutStart else cutEnd
        val corners = buildList {
            add(Corner(0f, h, outer))
            if (cutLeft) {
                add(Corner(0f, cutHeight, outer))
                add(Corner(cutWidth, cutHeight, inner))
                add(Corner(cutWidth, 0f, outer))
            } else {
                add(Corner(0f, 0f, outer))
            }
            if (cutRight) {
                add(Corner(w - cutWidth, 0f, outer))
                add(Corner(w - cutWidth, cutHeight, inner))
                add(Corner(w, cutHeight, outer))
            } else {
                add(Corner(w, 0f, outer))
            }
            add(Corner(w, h, outer))
        }
        for ((i, corner) in corners.withIndex()) {
            val before = corners[(i + corners.size - 1) % corners.size]
            val after = corners[(i + 1) % corners.size]
            val inLength = hypot(corner.x - before.x, corner.y - before.y)
            val outLength = hypot(after.x - corner.x, after.y - corner.y)
            // No more than half a side each, so a short card still closes.
            val r = min(corner.radius, min(inLength, outLength) / 2)
            val dIn = PointF((corner.x - before.x) / inLength, (corner.y - before.y) / inLength)
            val dOut = PointF((after.x - corner.x) / outLength, (after.y - corner.y) / outLength)
            val fromX = corner.x - dIn.x * r
            val fromY = corner.y - dIn.y * r
            val toX = corner.x + dOut.x * r
            val toY = corner.y + dOut.y * r
            if (i == 0) path.moveTo(fromX, fromY) else path.lineTo(fromX, fromY)
            // A quarter circle, near enough.
            path.cubicTo(
                fromX + dIn.x * r * ARC, fromY + dIn.y * r * ARC,
                toX - dOut.x * r * ARC, toY - dOut.y * r * ARC,
                toX, toY,
            )
        }
        path.close()
    }

    private class Corner(val x: Float, val y: Float, val radius: Float)

    private companion object {
        /** A cut's width: a 48dp button (in line with the card's side, in tt_header) and the gap between it and the card. */
        const val CUT_WIDTH_DP = 56f

        /** The card's own corners, and the curve where a cut turns into the card under it. */
        const val OUTER_RADIUS_DP = 24f
        const val INNER_RADIUS_DP = 16f

        /** How far along its sides a cubic's handles go to draw a quarter circle. */
        const val ARC = 0.5523f
    }
}
