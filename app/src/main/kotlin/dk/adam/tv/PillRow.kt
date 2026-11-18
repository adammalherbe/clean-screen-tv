package dk.adam.tv

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The row under the apps, as wide as the tile row: the Chromecast pill (with what's
 * casting), Continue chips as far as they fit, and the round sleep button at the far
 * right. Glass edges line up with the tiles' glass above.
 */
class PillRow(
    private val context: Context,
    private val frost: FrostSource,
    host: FrameLayout,
    onChromecast: () -> Unit,
    private val onLaunch: (String) -> Unit,
    onSleep: () -> Unit,
) {
    companion object {
        /** Transparent glow margin around each pill's glass. */
        const val PAD_DP = 14f
        private const val GAP_DP = 12f
    }

    private val left = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val castText = context.homeText(14f, "sans-serif", 0.92f).apply { maxWidth = context.dp(300f) }
    private val castState = ImageView(context).apply {
        alpha = 0.75f
        visibility = View.GONE
    }
    private val cast = pill(context.getDrawable(R.drawable.ic_cast), castText, 0.9f).apply {
        addView(castState, LinearLayout.LayoutParams(context.dp(12f), context.dp(12f)).apply {
            leftMargin = context.dp(10f)
        })
        setOnClickListener { onChromecast() }
    }
    private val moonText = context.homeText(14f, "sans-serif", 0.92f).apply { visibility = View.GONE }
    private val moon = GlassCard(context, frost, radiusDp = 20f, padDp = PAD_DP, focusable = true, scaleBy = 0.08f).apply {
        gravity = Gravity.CENTER_VERTICAL
        inner(10f, 10f, 10f, 10f)
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_sleep) },
            LinearLayout.LayoutParams(context.dp(20f), context.dp(20f)))
        addView(moonText, LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = context.dp(8f)
            rightMargin = context.dp(6f)
        })
        setOnClickListener { onSleep() }
    }
    private var items: List<ContinueRow.Item> = emptyList()
    private val edge = context.dp(FrostTile.GLOW_DP - PAD_DP)

    init {
        left.addView(cast)
        host.addView(left, FrameLayout.LayoutParams(-2, -2, Gravity.LEFT or Gravity.CENTER_VERTICAL).apply {
            leftMargin = edge
        })
        host.addView(moon, FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply {
            rightMargin = edge
        })
        setCast(null)
        host.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) layoutChips() }
    }

    /** "Chromecast · HDMI 2", plus the current or last cast title when there is one. */
    fun setCast(title: String?, playing: Boolean? = null) {
        castText.text = if (title == null) "Chromecast · HDMI 2" else "Chromecast · HDMI 2 · $title"
        // ▶ while playing, ❚❚ while paused, nothing when idle.
        castState.visibility = if (playing == null) View.GONE else View.VISIBLE
        if (playing != null) castState.setImageResource(if (playing) R.drawable.ic_play else R.drawable.ic_pause)
        layoutChips()
    }

    fun setChips(items: List<ContinueRow.Item>) {
        this.items = items
        layoutChips()
    }

    /** Round when off; shows the minutes left when the timer runs. */
    fun setSleep(minutes: Int?) {
        moonText.text = minutes?.let { "$it min" }.orEmpty()
        moonText.visibility = if (minutes == null) View.GONE else View.VISIBLE
        layoutChips()
    }

    /** Adds Continue chips after the Chromecast pill while they fit before the sleep button. */
    private fun layoutChips() {
        val host = left.parent as? View ?: return
        if (host.width == 0) return
        while (left.childCount > 1) left.removeViewAt(1)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        cast.measure(unspecified, unspecified)
        moon.measure(unspecified, unspecified)
        val overlap = context.dp(2 * PAD_DP - GAP_DP)
        var room = host.width - 2 * edge - moon.measuredWidth - cast.measuredWidth + overlap
        for (item in items) {
            val chip = pill(item.icon, context.homeText(14f, "sans-serif", 0.92f).apply {
                text = item.label
                maxWidth = context.dp(190f)
            }, if (item.key == Store.HDMI) 0.9f else 1f).apply { setOnClickListener { onLaunch(item.key) } }
            chip.measure(unspecified, unspecified)
            val needed = chip.measuredWidth - overlap
            if (needed > room) break
            room -= needed
            left.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = -overlap })
        }
        chainFocus()
    }

    /**
     * Left/right walks the row and stops at its ends. Without this, the default focus
     * search jumps from the last chip up to the Settings tile instead of across the gap
     * to the sleep button.
     */
    private fun chainFocus() {
        val row = (0 until left.childCount).map { left.getChildAt(it) } + moon
        row.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        row.forEachIndexed { i, v ->
            v.nextFocusLeftId = row.getOrElse(i - 1) { v }.id
            v.nextFocusRightId = row.getOrElse(i + 1) { v }.id
        }
    }

    private fun pill(icon: Drawable?, text: TextView, iconAlpha: Float) =
        GlassCard(context, frost, radiusDp = 20f, padDp = PAD_DP, focusable = true, scaleBy = 0.06f).apply {
            gravity = Gravity.CENTER_VERTICAL
            inner(14f, 11f, 18f, 11f)
            addView(ImageView(context).apply {
                setImageDrawable(icon)
                alpha = iconAlpha
            }, LinearLayout.LayoutParams(context.dp(18f), context.dp(18f)).apply { rightMargin = context.dp(10f) })
            addView(text)
        }
}
