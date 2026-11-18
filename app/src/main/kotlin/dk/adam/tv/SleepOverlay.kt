package dk.adam.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The end of the sleep timer: dims the screen over 3 s, counts down 60 s, then asks
 * [Standby] to turn the TV off. Any key cancels. If standby isn't possible, falls
 * back to a black screen with a dim clock until a key is pressed.
 */
class SleepOverlay(
    private val context: Context,
    root: FrameLayout,
    private val onCancelled: () -> Unit,
) {
    companion object {
        private const val COUNTDOWN_S = 60
    }

    private val main = Handler(Looper.getMainLooper())
    private val dim = View(context).apply { setBackgroundColor(0xFF000000.toInt()) }
    private val count = context.homeText(72f, "sans-serif-thin")
    private val message = context.homeText(20f, "sans-serif-light", 0.8f).apply {
        text = "Going to sleep… press any button to cancel"
    }
    private val prompt = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val nightClock: TextView = context.homeText(120f, "sans-serif-thin", 0.22f)
    private val layer = FrameLayout(context).apply { visibility = View.GONE }
    private var secondsLeft = 0
    private var night = false

    val showing get() = layer.visibility == View.VISIBLE

    private val tick = object : Runnable {
        override fun run() {
            secondsLeft--
            count.text = secondsLeft.toString()
            if (secondsLeft <= 0) goToSleep() else main.postDelayed(this, 1000)
        }
    }

    init {
        layer.addView(dim, FrameLayout.LayoutParams(-1, -1))
        count.gravity = Gravity.CENTER_HORIZONTAL
        prompt.addView(count, LinearLayout.LayoutParams(-1, -2))
        prompt.addView(message, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(12f) })
        layer.addView(prompt, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        layer.addView(nightClock, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
    }

    fun start() {
        if (showing && !night) return
        night = false
        secondsLeft = COUNTDOWN_S
        count.text = secondsLeft.toString()
        nightClock.visibility = View.GONE
        prompt.visibility = View.VISIBLE
        prompt.alpha = 0f
        dim.alpha = 0f
        layer.visibility = View.VISIBLE
        // "Dim to 30%": 70% black over the whole screen.
        dim.animate().alpha(0.7f).setDuration(3000).start()
        prompt.animate().alpha(1f).setStartDelay(800).setDuration(1500).start()
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1000)
    }

    /** Keeps the night clock current; call every minute. */
    fun setTime(time: String) {
        nightClock.text = time
    }

    /** Any key cancels the countdown or leaves the night screen. */
    fun handleKey(event: KeyEvent): Boolean {
        if (!showing) return false
        // Power isn't "a button to cancel": it's what turning the TV off sends.
        if (event.keyCode == KeyEvent.KEYCODE_POWER) return true
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) stop()
        return true
    }

    private fun stop() {
        main.removeCallbacks(tick)
        layer.animate().cancel()
        layer.visibility = View.GONE
        layer.alpha = 1f
        night = false
        onCancelled()
    }

    private fun goToSleep() {
        prompt.animate().alpha(0f).setDuration(600).start()
        Thread {
            val method = Standby.sleep(context)
            android.util.Log.i("CleanScreenTV", "sleep timer: standby via ${method ?: "nothing (night screen)"}")
            main.post {
                if (method != null) {
                    // The TV is off; be back to normal when it's turned on again.
                    main.postDelayed({ if (showing) stop() }, 3000)
                } else {
                    showNight()
                }
            }
        }.start()
    }

    private fun showNight() {
        night = true
        prompt.visibility = View.GONE
        dim.animate().alpha(1f).setDuration(2000).start()
        nightClock.alpha = 0f
        nightClock.visibility = View.VISIBLE
        nightClock.animate().alpha(0.22f).setStartDelay(1500).setDuration(2000).start()
    }
}
