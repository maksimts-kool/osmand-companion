package dev.maksim.companion.timetable

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.annotation.RawRes
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.google.android.material.color.MaterialColors
import dev.maksim.companion.timetable.databinding.TtStateBinding

/**
 * A screen's whole content while there's nothing else to show: loading, nothing leaves, or couldn't load. Each is
 * an animation from LottieFiles above a line of text, recolored to fit the screen (see [tint]).
 */
object States {

    fun loading(parent: ViewGroup, @ColorInt accent: Int, text: CharSequence = parent.context.getString(R.string.tt_loading)) =
        show(parent, R.raw.tt_anim_loading, text, accent)

    /** Nothing to show, with the clock or another [animation]. */
    fun empty(parent: ViewGroup, text: CharSequence, @RawRes animation: Int = R.raw.tt_anim_empty) =
        show(parent, animation, text, null)

    /** Couldn't load; [retry] tries again. */
    fun error(parent: ViewGroup, text: CharSequence, retry: () -> Unit) =
        show(parent, R.raw.tt_anim_offline, text, null).run {
            action.setText(R.string.tt_retry)
            action.isVisible = true
            action.setOnClickListener { retry() }
        }

    /** Replaces what's in [parent] with the state, fading it in. */
    private fun show(parent: ViewGroup, @RawRes animation: Int, text: CharSequence, @ColorInt accent: Int?): TtStateBinding {
        parent.removeAllViews()
        val state = TtStateBinding.inflate(LayoutInflater.from(parent.context), parent, true)
        state.message.text = text
        with(state.animation) {
            setAnimation(animation)
            tint(this, accent ?: MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            playAnimation()
        }
        state.root.alpha = 0f
        state.root.animate().alpha(1f).setDuration(FADE_MS).start()
        return state
    }

    /**
     * The animations are drawn in greys on white. Greys become shades between [color] and the screen's background,
     * so they look right in dark mode too; colored parts (the red "offline" badge) stay as they are.
     */
    private fun tint(view: LottieAnimationView, @ColorInt color: Int) {
        val background = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface)
        val recolor = { original: Int ->
            val hsl = FloatArray(3).also { ColorUtils.colorToHSL(original, it) }
            if (hsl[1] > GREY_SATURATION) original
            else ColorUtils.blendARGB(color, background, hsl[2]).let { ColorUtils.setAlphaComponent(it, Color.alpha(original)) }
        }
        view.addValueCallback(KeyPath("**"), LottieProperty.COLOR) { recolor(it.startValue) }
        view.addValueCallback(KeyPath("**"), LottieProperty.STROKE_COLOR) { recolor(it.startValue) }
    }

    private const val FADE_MS = 200L

    /** Below this HSL saturation a color counts as grey. */
    private const val GREY_SATURATION = 0.15f
}
