package dk.adam.tv

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import kotlin.math.max

/**
 * The frosted glass shared by tiles, cards and chips: a crop of the pre-blurred
 * backdrop mapped to where the view sits on screen, dark smoked shade, hairline edge,
 * and a pre-rendered glow when focused. No real-time blur.
 */
class GlassPainter(private val view: View, private val frost: FrostSource, private val radius: Float) {

    companion object {
        // Keyed by size and radius; tiles, cards and chips each have their own.
        private val glowCache = HashMap<String, Bitmap>()
    }

    private val density = view.resources.displayMetrics.density
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density * 0.75f)
    }
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // One shader per frost bitmap; each view owns its own so the local matrix is private.
    private val shaders = HashMap<Bitmap, BitmapShader>(4)
    private val toBitmap = Matrix()
    private val toLocal = Matrix()
    private var glow: Bitmap? = null

    /** Call from onSizeChanged with the glass rect inside a glow margin of [pad]. */
    fun layout(w: Int, h: Int, glass: RectF, pad: Float) {
        if (w == 0 || h == 0) return
        // No margin, no glow (and BlurMaskFilter rejects a zero radius).
        if (pad < 1f) {
            glow = null
            return
        }
        val key = "$w:$h:$radius"
        glow = glowCache.getOrPut(key) {
            // ARGB, not ALPHA_8: Android 7's renderer draws ALPHA_8 glows as a dark box.
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                maskFilter = BlurMaskFilter(pad * 0.45f, BlurMaskFilter.Blur.NORMAL)
            }
            val inset = pad * 0.1f
            Canvas(bmp).drawRoundRect(
                RectF(glass.left - inset, glass.top - inset, glass.right + inset, glass.bottom + inset),
                radius, radius, paint,
            )
            bmp
        }
    }

    fun draw(canvas: Canvas, glass: RectF, focus: Float) {
        glow?.let {
            if (focus > 0.01f) {
                glowPaint.alpha = (focus * 110).toInt()
                canvas.drawBitmap(it, 0f, 0f, glowPaint)
            }
        }
        frost.base?.let { drawFrost(canvas, glass, it, 255) }
        frost.incoming?.let { drawFrost(canvas, glass, it, (frost.mix * 255).toInt()) }

        if (frost.tint != 0) {
            tintPaint.color = frost.tint
            canvas.drawRoundRect(glass, radius, radius, tintPaint)
            tintPaint.color = Color.WHITE
        }
        // Dark smoked glass; focus lifts it slightly.
        shadePaint.alpha = (120 - 45 * focus).toInt()
        canvas.drawRoundRect(glass, radius, radius, shadePaint)
        tintPaint.alpha = (18 * focus).toInt()
        canvas.drawRoundRect(glass, radius, radius, tintPaint)
        edgePaint.alpha = (28 + 90 * focus).toInt()
        canvas.drawRoundRect(glass, radius, radius, edgePaint)
    }

    /**
     * Maps the blurred backdrop so each glass pixel shows what lies behind it on
     * screen, undoing the view's own focus scale so the glass stays put as it grows.
     */
    private fun drawFrost(canvas: Canvas, glass: RectF, bmp: Bitmap, alpha: Int) {
        val root = view.rootView
        if (root.width == 0) return
        var x = 0f
        var y = 0f
        var v: View = view
        while (v !== root) {
            x += v.left
            y += v.top
            v = v.parent as? View ?: break
        }
        // centerCrop placement of the backdrop on screen
        val s = max(root.width / bmp.width.toFloat(), root.height / bmp.height.toFloat())
        val dx = (root.width - bmp.width * s) / 2
        val dy = (root.height - bmp.height * s) / 2

        toBitmap.setTranslate(-view.pivotX, -view.pivotY)
        toBitmap.postScale(view.scaleX, view.scaleY)
        toBitmap.postTranslate(x + view.pivotX - dx, y + view.pivotY - dy)
        toBitmap.postScale(1 / s, 1 / s)
        toBitmap.invert(toLocal)

        val shader = shaders.getOrPut(bmp) {
            if (shaders.size >= 3) shaders.clear()
            BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        shader.setLocalMatrix(toLocal)
        glassPaint.shader = shader
        glassPaint.alpha = alpha
        canvas.drawRoundRect(glass, radius, radius, glassPaint)
    }
}

/** Animates a 0..1 focus amount and the matching scale, the way tiles do. */
class FocusAnimator(private val view: View, private val scaleBy: Float, private val onChange: () -> Unit) {
    var amount = 0f
        private set
    private var anim: ValueAnimator? = null

    fun set(focused: Boolean) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(amount, if (focused) 1f else 0f).apply {
            duration = Motion.ms(220)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                amount = it.animatedValue as Float
                val s = 1f + scaleBy * amount
                view.scaleX = s
                view.scaleY = s
                onChange()
            }
            start()
        }
    }
}

/**
 * A glass panel that hosts ordinary child views: cards, chips, pills. [pad] is the
 * transparent glow margin around the glass; content padding goes inside the glass.
 */
@SuppressLint("ViewConstructor")
class GlassCard(
    context: Context,
    frost: FrostSource,
    radiusDp: Float,
    padDp: Float,
    focusable: Boolean,
    private val scaleBy: Float = 0.04f,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    val pad = padDp * density
    private val glass = RectF()
    private val painter = GlassPainter(this, frost, radiusDp * density)
    private val focus = FocusAnimator(this, scaleBy) { invalidate() }

    init {
        setWillNotDraw(false)
        isFocusable = focusable
        isClickable = focusable
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        frost.attach(this)
    }

    /** Padding inside the glass, in dp. */
    fun inner(leftDp: Float, topDp: Float, rightDp: Float, bottomDp: Float) {
        setPadding(
            (pad + leftDp * density).toInt(), (pad + topDp * density).toInt(),
            (pad + rightDp * density).toInt(), (pad + bottomDp * density).toInt(),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        glass.set(pad, pad, w - pad, h - pad)
        painter.layout(w, h, glass, pad)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        focus.set(gainFocus)
    }

    override fun dispatchDraw(canvas: Canvas) {
        painter.draw(canvas, glass, focus.amount)
        super.dispatchDraw(canvas)
    }
}
