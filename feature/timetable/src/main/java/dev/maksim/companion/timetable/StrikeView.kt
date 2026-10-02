package dev.maksim.companion.timetable

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * A red line struck through whatever is under it, from top left to bottom right: "not working", over the live mark
 * of a trip without live times. [strike] draws it in.
 */
class StrikeView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.tt_offline_strike)
        strokeWidth = STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
    }

    /** How much of the line is drawn, 0 to 1. */
    private var drawn = 1f
    private var animator: ValueAnimator? = null

    /** Draws the line in from its top end, after a moment so the eye catches it. */
    fun strike() {
        animator?.cancel()
        drawn = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = DELAY_MS
            duration = DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                drawn = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (drawn <= 0f) return
        val inset = INSET_DP * density
        val start = inset
        val end = width - inset
        val length = (end - start) * drawn
        canvas.drawLine(start, start, start + length, start + length * height / width, paint)
    }

    private companion object {
        const val STROKE_DP = 2f
        const val INSET_DP = 2f
        const val DELAY_MS = 150L
        const val DURATION_MS = 350L
    }
}
