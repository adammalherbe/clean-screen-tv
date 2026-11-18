package dk.adam.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Knows when the TV has just booted or woken from standby. Booting is a process
 * start within the first minutes of uptime; waking is a SCREEN_ON broadcast.
 */
object Wake {
    private const val WINDOW_MS = 3 * 60 * 1000L
    private var at = SystemClock.elapsedRealtime().let { if (it < WINDOW_MS) it else -1L }
    private var handled = false
    private var listening = false

    fun listen(context: Context) {
        if (listening) return
        listening = true
        context.applicationContext.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = mark()
        }, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    fun mark() {
        at = SystemClock.elapsedRealtime()
        handled = false
    }

    /** True once per boot or wake, within a few minutes of it. */
    fun take(): Boolean {
        if (handled || at < 0 || SystemClock.elapsedRealtime() - at > WINDOW_MS) return false
        handled = true
        return true
    }
}

/** The "Back to Chromecast?" card, centred under the pill row. */
class ResumePrompt(
    private val context: Context,
    frost: FrostSource,
    private val host: FrameLayout,
    private val onSwitch: () -> Unit,
    private val onClosed: () -> Unit,
) {
    companion object {
        const val RECENT_MS = 18 * 60 * 60 * 1000L
        private const val SHOW_MS = 6000L
    }

    private val card = GlassCard(context, frost, radiusDp = 16f, padDp = 16f, focusable = true).apply {
        orientation = LinearLayout.VERTICAL
        inner(18f, 14f, 20f, 12f)
        visibility = View.GONE
    }
    private val progress = View(context).apply {
        setBackgroundColor(0xFFFFFFFF.toInt())
        pivotX = 0f
    }

    val showing get() = card.visibility == View.VISIBLE

    init {
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_cast)
            alpha = 0.9f
        }, LinearLayout.LayoutParams(context.dp(26f), context.dp(26f)).apply { rightMargin = context.dp(14f) })
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(context.homeText(17f, "sans-serif").apply { text = "Back to Chromecast?" })
        texts.addView(context.homeText(12f, alpha = 0.6f).apply {
            text = "OK to switch · Back to dismiss"
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(4f) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        card.addView(progress, LinearLayout.LayoutParams(-1, context.dp(1.5f)).apply { topMargin = context.dp(12f) })
        card.setOnClickListener { switch() }
        // Fixed width: the full-width progress line would otherwise stretch a wrap_content card.
        host.addView(card, FrameLayout.LayoutParams(
            (context.dp(330f) + 2 * card.pad).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
    }

    fun show() {
        card.fadeIn(500)
        card.requestFocus()
        progress.scaleX = 1f
        progress.alpha = 0.55f
        progress.animate().scaleX(0f).alpha(0.1f).setDuration(SHOW_MS)
            .setInterpolator(LinearInterpolator())
            .withEndAction { if (showing) dismiss() }
            .start()
    }

    fun dismiss() {
        progress.animate().cancel()
        card.fadeOut(350)
        onClosed()
    }

    /** OK switches; Back or any D-pad move dismisses. Consumes everything while showing. */
    fun handleKey(event: KeyEvent): Boolean {
        if (!showing) return false
        if (event.action != KeyEvent.ACTION_UP) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> switch()
            else -> dismiss()
        }
        return true
    }

    private fun switch() {
        dismiss()
        onSwitch()
    }
}
