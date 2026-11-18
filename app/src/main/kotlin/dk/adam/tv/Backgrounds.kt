package dk.adam.tv

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * The pre-blurred backdrop that frosted tiles sample from. During a background
 * change it holds both images and the mix between them.
 */
class FrostSource {
    var base: Bitmap? = null
        private set
    var incoming: Bitmap? = null
        private set
    var mix = 0f
        private set
    /** The scene's time-of-day tint, also laid over the glass so it matches the background. */
    var tint = 0
        private set

    private val views = ArrayList<View>()

    fun attach(view: View) {
        views += view
    }

    fun set(bitmap: Bitmap) {
        base = bitmap
        incoming = null
        invalidate()
    }

    fun begin(bitmap: Bitmap) {
        incoming = bitmap
        mix = 0f
        invalidate()
    }

    fun progress(f: Float) {
        mix = f
        invalidate()
    }

    fun tint(color: Int) {
        tint = color
        invalidate()
    }

    fun finish() {
        incoming?.let { base = it }
        incoming = null
        mix = 0f
        invalidate()
    }

    private fun invalidate() = views.forEach { it.invalidate() }
}

/** Decodes backgrounds off the main thread and cross-fades between them. */
class Backgrounds(
    private val assets: AssetManager,
    private val bottom: ImageView,
    private val top: ImageView,
    private val tintView: View,
    private val frost: FrostSource,
) {
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var shown: String? = null
    private var pending: String? = null
    private var fade: ValueAnimator? = null
    private var tintAnim: ValueAnimator? = null
    private var tint = 0

    var onFirstImage: (() -> Unit)? = null
    val ready get() = shown != null

    /** Shows bg/[asset].jpg (and its pre-blurred frost/[asset].jpg) with a 2 s cross-fade. */
    fun show(asset: String) {
        if (asset == shown || asset == pending) return
        pending = asset
        loader.execute {
            val image = decode("bg/$asset.jpg")
            val blurred = decode("frost/$asset.jpg")
            main.post { if (pending == asset) apply(asset, image, blurred) }
        }
    }

    /** Fades the time-of-day tint over the same 2 s. */
    fun tint(color: Int) {
        if (color == tint) return
        tintAnim?.cancel()
        val from = tint
        tint = color
        tintAnim = ValueAnimator.ofObject(ArgbEvaluator(), from, color).apply {
            duration = if (shown == null) 0 else Motion.ms(2000)
            addUpdateListener {
                val c = it.animatedValue as Int
                tintView.setBackgroundColor(c)
                frost.tint(c)
            }
            start()
        }
    }

    fun release() {
        loader.shutdownNow()
        fade?.cancel()
    }

    private fun apply(asset: String, image: Bitmap, blurred: Bitmap) {
        pending = null
        val first = shown == null
        shown = asset
        if (first) {
            bottom.setImageBitmap(image)
            frost.set(blurred)
            onFirstImage?.invoke()
            return
        }
        fade?.end()
        top.setImageBitmap(image)
        top.alpha = 0f
        frost.begin(blurred)
        fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.ms(2000)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                top.alpha = f
                frost.progress(f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bottom.setImageBitmap(image)
                    top.alpha = 0f
                    top.setImageDrawable(null)
                    frost.finish()
                    fade = null
                }
            })
            start()
        }
    }

    private fun decode(path: String): Bitmap = assets.open(path).use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })!!
    }
}
