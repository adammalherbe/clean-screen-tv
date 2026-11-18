package dk.adam.tv

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The glass card top-right: clock with the date under it, current weather beside
 * them, and a 7-day strip underneath. Weather parts hide when there's no data.
 */
class TopCard(private val context: Context, frost: FrostSource) {

    val view = GlassCard(context, frost, radiusDp = 20f, padDp = 0f, focusable = false).apply {
        orientation = LinearLayout.VERTICAL
        inner(22f, 14f, 22f, 14f)
    }
    private val clock = context.homeText(48f, "sans-serif-thin")
    private val date = context.homeText(15f, alpha = 0.85f)
    private val now = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.RIGHT
    }
    private val nowIcon = ImageView(context).apply { alpha = 0.9f }
    private val nowTemp = context.homeText(30f, "sans-serif-thin", 0.95f)
    private val sleepText = context.homeText(17f, "sans-serif-light", 0.9f)
    private val sleep = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_sleep)
            alpha = 0.85f
        }, LinearLayout.LayoutParams(context.dp(18f), context.dp(18f)).apply { rightMargin = context.dp(6f) })
        addView(sleepText)
    }
    private var reading: Weather.Reading? = null
    private val nowDetail = context.homeText(13f, alpha = 0.75f)
    private val strip = LinearLayout(context)

    init {
        val top = LinearLayout(context).apply { gravity = Gravity.BOTTOM }
        val time = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        time.addView(clock)
        time.addView(date, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(2f) })
        top.addView(time, LinearLayout.LayoutParams(0, -2, 1f))

        val tempRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        // Sleep timer countdown, beside the temperature.
        tempRow.addView(sleep, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = context.dp(20f) })
        tempRow.addView(nowIcon, LinearLayout.LayoutParams(context.dp(26f), context.dp(26f)).apply {
            rightMargin = context.dp(8f)
        })
        tempRow.addView(nowTemp)
        now.addView(tempRow)
        now.addView(nowDetail, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(4f) })
        top.addView(now, LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = context.dp(24f)
            bottomMargin = context.dp(2f)
        })
        view.addView(top, LinearLayout.LayoutParams(-1, -2))
        view.addView(strip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12f) })
        show(null)
    }

    fun setTime(time: String, day: String) {
        clock.text = time
        date.text = day
    }

    /** Minutes left on the sleep timer, or null when it's off. */
    fun setSleep(minutes: Int?) {
        sleep.visibility = if (minutes == null) View.GONE else View.VISIBLE
        sleepText.text = minutes?.let { "$it min" }.orEmpty()
        show(reading)
    }

    fun show(reading: Weather.Reading?) {
        this.reading = reading
        val weather = if (reading == null) View.GONE else View.VISIBLE
        // The weather column also carries the sleep countdown, so it stays while that runs.
        now.visibility = if (reading != null || sleep.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        nowIcon.visibility = weather
        nowTemp.visibility = weather
        nowDetail.visibility = weather
        strip.visibility = weather
        if (reading == null) return
        nowIcon.setImageResource(reading.icon)
        nowTemp.text = "${reading.tempC}°"
        nowDetail.text = (if (BuildConfig.CITY.isBlank()) "" else "${BuildConfig.CITY} · ") + "H ${reading.high}° L ${reading.low}°"
        strip.removeAllViews()
        reading.days.take(7).forEach { day ->
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            col.addView(context.homeText(11f, alpha = 0.7f).apply { text = day.name })
            col.addView(ImageView(context).apply {
                setImageResource(day.icon)
                alpha = 0.85f
            }, LinearLayout.LayoutParams(context.dp(18f), context.dp(18f)).apply {
                topMargin = context.dp(3f)
                bottomMargin = context.dp(3f)
            })
            col.addView(context.homeText(11f, alpha = 0.85f).apply { text = "${day.high}° ${day.low}°" })
            strip.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
}
