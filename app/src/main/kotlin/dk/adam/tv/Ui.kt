package dk.adam.tv

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.TextView

fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

/** White text in the home screen's style: given family and size, soft shadow, one line. */
fun Context.homeText(sizeSp: Float, family: String = "sans-serif-light", alpha: Float = 1f): TextView =
    TextView(this).apply {
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Typeface.create(family, Typeface.NORMAL)
        setShadowLayer(10f, 0f, 2f, 0x59000000)
        includeFontPadding = false
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        this.alpha = alpha
    }

fun View.fadeIn(ms: Long = 400, to: Float = 1f) {
    animate().cancel()
    if (visibility != View.VISIBLE) {
        alpha = 0f
        visibility = View.VISIBLE
    }
    animate().alpha(to).setStartDelay(0).setDuration(Motion.ms(ms)).start()
}

fun View.fadeOut(ms: Long = 400, gone: Boolean = true) {
    if (visibility != View.VISIBLE) return
    animate().cancel()
    animate().alpha(0f).setStartDelay(0).setDuration(Motion.ms(ms))
        .withEndAction { visibility = if (gone) View.GONE else View.INVISIBLE }
        .start()
}
