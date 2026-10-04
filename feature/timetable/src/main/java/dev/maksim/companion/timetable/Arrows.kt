package dev.maksim.companion.timetable

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.widget.TextView
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * The "→" of "Bus 8 → Väike-Õismäe" as chevrons ([R.raw.tt_anim_to], after "Composition 1" from LottieFiles) in the
 * text's own color. A screen's arrows move together: in, one by one, as it opens ("in" in the animation), and out,
 * one by one, as it closes ([leave], "out"); still in between.
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

    /**
     * Sends [activity]'s arrows out, then calls [then] (to finish it); false, and nothing done, if it has none on
     * screen. Call it from finish().
     */
    fun leave(activity: Activity, then: () -> Unit): Boolean {
        val screen = screens[activity.window.decorView] ?: return false
        if (screen.leaving) return true
        if (screen.views.keys.none { it.isShown }) return false
        screen.leaving = true
        with(screen.drawable) {
            removeAllAnimatorListeners()
            addAnimatorListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = then()
                },
            )
            setMinAndMaxFrame("out")
            playAnimation()
        }
        return true
    }

    /** One window's arrows, and the animation they share. */
    private class Screen(val drawable: LottieDrawable) : Drawable.Callback {
        val views = WeakHashMap<TextView, Unit>()
        var leaving = false

        override fun invalidateDrawable(who: Drawable) {
            for (view in views.keys) view.invalidate()
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
        override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
    }

    /** By the window's root view. */
    private val screens = WeakHashMap<View, Screen>()
    private val joined = WeakHashMap<TextView, Screen>()

    /** [view]'s arrows into its window's: the first ones there start coming in. */
    private fun join(view: TextView) {
        val root = view.rootView
        val screen = screens[root] ?: run {
            val composition = LottieCompositionFactory.fromRawResSync(view.context.applicationContext, R.raw.tt_anim_to).value
                ?: return
            val drawable = LottieDrawable().apply {
                setComposition(composition)
                setBounds(0, 0, composition.bounds.width(), composition.bounds.height())
            }
            Screen(drawable).also {
                drawable.callback = it
                screens[root] = it
                drawable.setMinAndMaxFrame("in")
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

    /** Takes the arrow's place: chevrons a little wider than it, centered on the line, in the text's color. */
    private class ArrowSpan(view: TextView) : ReplacementSpan() {

        private val view = WeakReference(view)

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            (paint.textSize * WIDTH_EM).toInt()

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
        ) {
            val drawable = view.get()?.let { joined[it] }?.drawable ?: return
            val bounds = drawable.bounds
            val width = paint.textSize * WIDTH_EM
            val scale = width / bounds.width()
            val height = bounds.height() * scale
            val metrics = paint.fontMetrics
            val centerY = y + (metrics.ascent + metrics.descent) / 2
            layer.colorFilter = PorterDuffColorFilter(paint.color, PorterDuff.Mode.SRC_IN)
            canvas.saveLayer(x, centerY - height / 2, x + width, centerY + height / 2, layer)
            canvas.translate(x, centerY - height / 2)
            canvas.scale(scale, scale)
            drawable.draw(canvas)
            canvas.restore()
        }
    }

    private val layer = Paint()

    /** How wide the chevrons are, in ems. */
    private const val WIDTH_EM = 1.5f
}
