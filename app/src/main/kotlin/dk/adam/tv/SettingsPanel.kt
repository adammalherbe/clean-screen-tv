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

/** Clean Screen TV's own hidden settings, opened by long-pressing OK on the Settings tile. */
class SettingsPanel(
    private val context: Context,
    frost: FrostSource,
    root: FrameLayout,
    private val store: Store,
    private val onClosed: () -> Unit,
) {
    private val overlay = FrameLayout(context).apply {
        setBackgroundColor(0x99000000.toInt())
        visibility = View.GONE
    }
    private val main = Handler(Looper.getMainLooper())
    private val resumeValue: TextView = context.homeText(16f, "sans-serif", 0.85f)
    private val standbyValue: TextView = context.homeText(16f, "sans-serif", 0.85f)
    private var standbyStatus: String? = null
    private val standbyRow = GlassCard(context, frost, radiusDp = 12f, padDp = 12f, focusable = true).apply {
        gravity = Gravity.CENTER_VERTICAL
        inner(18f, 14f, 18f, 14f)
    }
    private val resumeRow = GlassCard(context, frost, radiusDp = 12f, padDp = 12f, focusable = true).apply {
        gravity = Gravity.CENTER_VERTICAL
        inner(18f, 14f, 18f, 14f)
    }

    val showing get() = overlay.visibility == View.VISIBLE

    init {
        val panel = GlassCard(context, frost, radiusDp = 20f, padDp = 0f, focusable = false).apply {
            orientation = LinearLayout.VERTICAL
            inner(28f, 24f, 28f, 20f)
        }
        panel.addView(context.homeText(26f, "sans-serif-thin").apply { text = "Clean Screen TV" })
        resumeRow.addView(context.homeText(16f, "sans-serif").apply {
            text = "Resume Chromecast automatically"
        }, LinearLayout.LayoutParams(0, -2, 1f))
        resumeRow.addView(resumeValue, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.dp(32f) })
        resumeRow.setOnClickListener {
            store.autoResume = !store.autoResume
            refresh()
        }
        panel.addView(resumeRow, LinearLayout.LayoutParams(context.dp(460f), -2).apply {
            topMargin = context.dp(12f)
            leftMargin = -context.dp(12f)
            rightMargin = -context.dp(12f)
        })
        panel.addView(context.homeText(12f, alpha = 0.5f).apply {
            text = "When on, Clean Screen TV switches to the Chromecast by itself after the TV starts."
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(4f) })

        standbyRow.addView(context.homeText(16f, "sans-serif").apply {
            text = "Let the sleep timer turn off the TV"
        }, LinearLayout.LayoutParams(0, -2, 1f))
        standbyRow.addView(standbyValue, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.dp(32f) })
        standbyRow.setOnClickListener { setUpStandby() }
        panel.addView(standbyRow, LinearLayout.LayoutParams(context.dp(460f), -2).apply {
            topMargin = context.dp(10f)
            leftMargin = -context.dp(12f)
            rightMargin = -context.dp(12f)
        })
        panel.addView(context.homeText(12f, alpha = 0.5f).apply {
            text = "One-time: press OK, then choose Always allow on the TV's prompt."
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(4f) })
        panel.addView(context.homeText(12f, alpha = 0.5f).apply { text = "Back to close" },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(18f) })
        overlay.addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    }

    fun show() {
        refresh()
        overlay.fadeIn(250)
        resumeRow.requestFocus()
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (!showing) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                overlay.fadeOut(250)
                onClosed()
            }
            return true
        }
        return false
    }

    /** Connects to the TV's own ADB once so it asks to authorize Clean Screen TV's key. */
    private fun setUpStandby() {
        standbyStatus = "Waiting…"
        refresh()
        Thread {
            val ok = AdbLocal(context).shell("echo ok")?.trim() == "ok"
            if (ok) store.adbReady = true
            main.post {
                standbyStatus = if (ok) null else "Not allowed"
                refresh()
            }
        }.start()
    }

    private fun refresh() {
        resumeValue.text = if (store.autoResume) "On" else "Off"
        standbyValue.text = standbyStatus ?: if (store.adbReady) "Ready" else "Set up"
    }
}
