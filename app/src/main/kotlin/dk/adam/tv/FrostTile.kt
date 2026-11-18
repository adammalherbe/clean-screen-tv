package dk.adam.tv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View

/**
 * A square frosted-glass app tile (see [GlassPainter]) with the app's real icon and a
 * small label underneath. Focus scales it up and fades in a pre-rendered glow. An app
 * that isn't installed shows its store icon dimmed, and "Get it on Play Store" on focus.
 */
@SuppressLint("ViewConstructor")
class FrostTile(context: Context, frost: FrostSource, val app: TileApp) : View(context) {

    companion object {
        const val GLASS_DP = 112
        const val GLOW_DP = 22
        /** Room under the glass for the label. */
        const val LABEL_DP = 34
        private const val ICON_DP = 64
        private const val FOCUS_SCALE = 0.1f
        private const val DIMMED = 0.45f
    }

    private val density = resources.displayMetrics.density
    private val pad = GLOW_DP * density
    private val glass = RectF()
    private val iconBounds = Rect()
    private val painter = GlassPainter(this, frost, 24 * density)
    private val focus = FocusAnimator(this, FOCUS_SCALE) {
        invalidate()
    }

    private val sp = resources.displayMetrics.scaledDensity
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setShadowLayer(10f, 0f, 2f, 0x59000000)
    }

    private var icon: Drawable? = null
    private var installed = true

    init {
        isFocusable = true
        isClickable = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        frost.attach(this)
        refreshIcon()
    }

    fun refreshIcon() {
        if (app.pkg == null) {
            icon = context.getDrawable(app.iconRes)
            installed = true
        } else {
            val real = Apps.icon(context, app.pkg)
            installed = real != null
            icon = real ?: app.iconRes.takeIf { it != 0 }?.let { context.getDrawable(it) }
        }
        alpha = if (installed) 1f else DIMMED
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = GLASS_DP * density
        glass.set(pad, pad, pad + size, pad + size)
        val iconSize = (ICON_DP * density).toInt()
        val cx = glass.centerX().toInt()
        val cy = glass.centerY().toInt()
        iconBounds.set(cx - iconSize / 2, cy - iconSize / 2, cx + iconSize / 2, cy + iconSize / 2)
        // Scale around the glass, not the label underneath.
        pivotX = glass.centerX()
        pivotY = glass.centerY()
        painter.layout(w, (pad * 2 + size).toInt(), glass, pad)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        focus.set(gainFocus)
        // A dimmed tile brightens while focused so its Play Store hint is readable.
        if (!installed) animate().alpha(if (gainFocus) 1f else DIMMED).setDuration(220).start()
    }

    override fun onDraw(canvas: Canvas) {
        painter.draw(canvas, glass, focus.amount)
        icon?.let {
            it.bounds = iconBounds
            it.draw(canvas)
        }
        val hint = !installed && isFocused
        labelPaint.textSize = (if (hint) 12 else 14) * sp
        labelPaint.alpha = if (hint) 220 else 230
        canvas.drawText(
            if (hint) "Get it on Play Store" else app.label,
            glass.centerX(), glass.bottom + 22 * density, labelPaint,
        )
    }
}
