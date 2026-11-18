package dk.adam.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The one-line "Next · 15:30 Lunch" under the date, from the iCloud calendar in
 * config.local.json. Fetches on start and every 15 minutes, caches the last good feed
 * for offline use, and never shows errors (they go to the log).
 */
class NextEvent(context: Context, private val onChanged: () -> Unit) {

    companion object {
        private const val TAG = "CleanScreenTV"
        private const val INTERVAL_MS = 15 * 60 * 1000L
        private const val LOOKAHEAD_MS = 36 * 60 * 60 * 1000L

        /** webcal:// is just https:// for our purposes. */
        fun httpsUrl(url: String) = url.trim().replaceFirst(Regex("^webcal://", RegexOption.IGNORE_CASE), "https://")

        /**
         * Used only while config.local.json has no calendarUrl, so the line can be seen.
         * Once a real URL is set (and the app rebuilt), real events replace it.
         */
        private fun sampleFeed(): String {
            val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Calendar.getInstance().time)
            return "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:adamtv-sample\nDTSTART:${day}T170000\n" +
                "DTEND:${day}T180000\nRRULE:FREQ=DAILY\nSUMMARY:Gym\nEND:VEVENT\nEND:VCALENDAR\n"
        }
    }

    private val url = httpsUrl(BuildConfig.CALENDAR_URL)
    private val cache = File(context.filesDir, "calendar.ics")
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tick = Runnable { refresh() }
    private var occurrences: List<ICal.Occurrence> = emptyList()
    private var nextDue = 0L
    private var running = false
    private var released = false

    fun start() {
        running = true
        main.removeCallbacks(tick)
        val wait = nextDue - SystemClock.elapsedRealtime()
        if (wait <= 0) refresh() else main.postDelayed(tick, wait)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
    }

    fun release() {
        released = true
        stop()
        io.shutdownNow()
    }

    /** The line for right now, or null to hide it. Cheap: call every minute. */
    fun line(now: Long = System.currentTimeMillis()): String? {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val today = startOfDay(cal, 0)
        val tomorrow = startOfDay(cal, 1)
        val dayAfter = startOfDay(cal, 2)
        val time = SimpleDateFormat("HH:mm", Locale.US)

        occurrences.firstOrNull { !it.allDay && it.start >= now && it.start < tomorrow }
            ?.let { return "Next · ${time.format(it.start)} ${it.summary}" }
        occurrences.firstOrNull { it.allDay && it.start == today }
            ?.let { return "Today · ${it.summary}" }
        // Tomorrow's first event, all-day ones first, if it's within 36 hours.
        val tmrw = occurrences.filter { it.start >= tomorrow && it.start < dayAfter && it.start <= now + LOOKAHEAD_MS }
        tmrw.firstOrNull { it.allDay }?.let { return "Tomorrow · ${it.summary}" }
        tmrw.firstOrNull()?.let { return "Tomorrow · ${time.format(it.start)} ${it.summary}" }
        return null
    }

    private fun refresh() {
        io.execute {
            val feed = when {
                url.isEmpty() -> sampleFeed()
                else -> try {
                    download().also { cache.writeText(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "Calendar fetch failed; using cache", e)
                    if (cache.exists()) cache.readText() else null
                }
            }
            val parsed = try {
                feed?.let { expand(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Calendar parse failed", e)
                null
            }
            main.post {
                if (released) return@post
                parsed?.let { occurrences = it }
                nextDue = SystemClock.elapsedRealtime() + INTERVAL_MS
                main.removeCallbacks(tick)
                if (running) main.postDelayed(tick, INTERVAL_MS)
                onChanged()
            }
        }
    }

    /** Everything from the start of today until two days out; [line] picks from it. */
    private fun expand(ics: String): List<ICal.Occurrence> {
        val cal = Calendar.getInstance()
        return ICal.occurrences(ics, startOfDay(cal, 0), startOfDay(cal, 3))
    }

    private fun download(): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun startOfDay(now: Calendar, plusDays: Int): Long = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, plusDays)
    }.timeInMillis
}
