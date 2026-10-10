package dev.maksim.companion.timetable

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.google.android.material.color.MaterialColors
import java.lang.ref.WeakReference
import kotlin.math.min
import java.util.WeakHashMap

/**
 * The "→" of "Bus 8 → Väike-Õismäe" as chevrons ([R.raw.tt_anim_to], after "Composition 1" from LottieFiles) in the
 * text's own color. A screen's arrows move together: they fade in, one by one, as it opens, then every
 * [SHINE_EVERY_MS] they take the theme's accent one after another, left to right, and go back to the text's color.
 */
object Arrows {

    private const val ARROW = '→'

    /** Sets [text] on [view] with its arrows as chevrons. */
    fun set(view: TextView, text: CharSequence?) {
        if (text == null || ARROW !in text) {
            view.text = text
            return
        }
        val spannable = SpannableString(text)
        for ((i, c) in text.withIndex()) {
            if (c == ARROW) spannable.setSpan(ArrowSpan(view), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = spannable
        if (view.isAttachedToWindow) join(view) else view.addOnAttachStateChangeListener(Attach)
    }

    /** One window's arrows, the animation they share, and the shine that runs through them all at once. */
    private class Screen(root: View, val drawable: LottieDrawable) : Drawable.Callback {
        val views = WeakHashMap<TextView, Unit>()

        /** How far through the chevrons the shine is, 0 to 1; null between shines. */
        var shine: Float? = null

        private val window = WeakReference(root)

        private val sweep = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SHINE_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                shine = it.animatedValue as Float
                invalidateDrawable(drawable)
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        shine = null
                        invalidateDrawable(drawable)
                        schedule()
                    }
                },
            )
        }

        /** Shines if the arrows are on screen; then again later, until the window is gone. */
        private val next = Runnable {
            val root = window.get()?.takeIf { it.isAttachedToWindow } ?: return@Runnable
            if (root.windowVisibility == View.VISIBLE && views.keys.any { it.isShown }) sweep.start() else schedule()
        }

        /** Starts the shining, once the arrows are in. Not at all with animations off. */
        fun shineLater() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()) schedule()
        }

        private fun schedule() {
            window.get()?.postDelayed(next, SHINE_EVERY_MS)
        }

        override fun invalidateDrawable(who: Drawable) {
            for (view in views.keys) view.invalidate()
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
        override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
    }

    /** By the window's root view. */
    private val screens = WeakHashMap<View, Screen>()
    private val joined = WeakHashMap<TextView, Screen>()

    /** [view]'s arrows into its window's: the first ones there start fading in. */
    private fun join(view: TextView) {
        val root = view.rootView
        val screen = screens[root] ?: run {
            val composition = LottieCompositionFactory.fromRawResSync(view.context.applicationContext, R.raw.tt_anim_to).value
                ?: return
            val drawable = LottieDrawable().apply {
                setComposition(composition)
                setBounds(0, 0, composition.bounds.width(), composition.bounds.height())
            }
            Screen(root, drawable).also {
                drawable.callback = it
                screens[root] = it
                drawable.addAnimatorListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) = it.shineLater()
                    },
                )
                drawable.playAnimation()
            }
        }
        screen.views[view] = Unit
        joined[view] = screen
        view.invalidate()
    }

    private object Attach : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.removeOnAttachStateChangeListener(this)
            join(v as TextView)
        }

        override fun onViewDetachedFromWindow(v: View) = Unit
    }

    /**
     * Takes the arrow's place: chevrons a little wider than it, centered on the line, in the text's color, with the
     * shine going through them in the theme's primary (its tertiary on text that's already primary).
     */
    private class ArrowSpan(view: TextView) : ReplacementSpan() {

        private val view = WeakReference(view)
        private val primary = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary)
        private val tertiary = MaterialColors.getColor(view, com.google.android.material.R.attr.colorTertiary)

        private val shinePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            (paint.textSize * WIDTH_EM).toInt()

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
        ) {
            val screen = view.get()?.let { joined[it] } ?: return
            val drawable = screen.drawable
            val bounds = drawable.bounds
            val width = paint.textSize * WIDTH_EM
            val scale = width / bounds.width()
            val height = bounds.height() * scale
            val metrics = paint.fontMetrics
            val centerY = y + (metrics.ascent + metrics.descent) / 2
            val left = x
            val topY = centerY - height / 2
            val shine = screen.shine
            // The shine only colors the chevrons, so they're drawn into a layer of their own first.
            if (shine != null) canvas.saveLayer(left, topY, left + width, topY + height, null)
            layer.colorFilter = PorterDuffColorFilter(paint.color, PorterDuff.Mode.SRC_IN)
            canvas.saveLayer(left, topY, left + width, topY + height, layer)
            canvas.translate(left, topY)
            canvas.scale(scale, scale)
            drawable.draw(canvas)
            canvas.restore()
            if (shine != null) {
                val color = if ((paint.color or ALPHA) == (primary or ALPHA)) tertiary else primary
                canvas.translate(left, topY)
                canvas.scale(scale, scale)
                // Each chevron in turn holds the color for a step, handing it on to the next as it goes back.
                val at = shine * SHINE_MS
                for (i in 0 until CHEVRONS) {
                    val on = ((at - i * SHINE_STEP_MS) / SHINE_HANDOVER_MS).coerceIn(0f, 1f)
                    val off = (((i + 1) * SHINE_STEP_MS + SHINE_HANDOVER_MS - at) / SHINE_HANDOVER_MS).coerceIn(0f, 1f)
                    val t = min(on, off)
                    if (t == 0f) continue
                    shinePaint.color = color
                    shinePaint.alpha = (255 * t * t * (3 - 2 * t)).toInt()
                    val w = bounds.width()
                    val from = if (i == 0) 0f else w * CHEVRON_SPLITS[i - 1]
                    val to = if (i == CHEVRONS - 1) w.toFloat() else w * CHEVRON_SPLITS[i]
                    canvas.drawRect(from, 0f, to, bounds.height().toFloat(), shinePaint)
                }
                canvas.restore()
            }
        }
    }

    private val layer = Paint()

    /** How wide the chevrons are, in ems. */
    private const val WIDTH_EM = 1.5f

    /**
     * tt_anim_to's chevrons, at 28, 50 and 72 hundredths of its width, and where one's room ends and the next's
     * starts, in fractions of it (its drawn size is in pixels for the screen, not its own 100).
     */
    private const val CHEVRONS = 3
    private val CHEVRON_SPLITS = floatArrayOf(0.39f, 0.61f)

    /**
     * The shine: how long each chevron holds the color, how long one takes to hand it to the next, how long it takes
     * to go through them all, and how long they rest between.
     */
    private const val SHINE_STEP_MS = 280f
    private const val SHINE_HANDOVER_MS = 120f
    private const val SHINE_MS = (CHEVRONS * SHINE_STEP_MS + SHINE_HANDOVER_MS).toLong()
    private const val SHINE_EVERY_MS = 2_500L

    private const val ALPHA = 0xFF shl 24
}
