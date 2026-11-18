package dk.adam.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Local weather and a 7-day forecast from Open-Meteo (location from config.local.json). Refreshes every 30 minutes;
 * the last good response is cached so it still shows offline. Delivers null only when
 * there's nothing usable at all.
 */
class Weather(context: Context, private val onResult: (Reading?) -> Unit) {

    class Day(val name: String, val icon: Int, val high: Int, val low: Int)

    class Reading(val tempC: Int, val icon: Int, val high: Int, val low: Int, val days: List<Day>)

    companion object {
        private val URL_STRING = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${DayClock.LAT}&longitude=${DayClock.LON}&current=temperature_2m,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=7"
        private const val INTERVAL_MS = 30 * 60 * 1000L
        // Sooner after a failure, e.g. when the network isn't up yet after boot.
        private const val RETRY_MS = 2 * 60 * 1000L
        // Older than this, a cached reading is no longer worth showing.
        private const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L

        /** WMO weather code to icon. */
        fun iconFor(code: Int, day: Boolean): Int = when (code) {
            0, 1 -> if (day) R.drawable.wx_clear_day else R.drawable.wx_clear_night
            2 -> if (day) R.drawable.wx_partly_day else R.drawable.wx_cloudy
            3 -> R.drawable.wx_cloudy
            45, 48 -> R.drawable.wx_fog
            in 71..77, 85, 86 -> R.drawable.wx_snow
            in 95..99 -> R.drawable.wx_thunder
            in 51..67, in 80..82 -> R.drawable.wx_rain
            else -> R.drawable.wx_cloudy
        }
    }

    private val cache = File(context.filesDir, "weather.json")
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tick = Runnable { refresh() }
    private var nextDue = 0L
    private var released = false
    private var running = false
    private var inFlight = false

    /** Fetches now if a refresh is due, otherwise waits for the next one. */
    fun start() {
        running = true
        main.removeCallbacks(tick)
        if (inFlight) return
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

    private fun refresh() {
        inFlight = true
        io.execute {
            var fresh = true
            val reading = try {
                download().let { body -> parse(body).also { cache.writeText(body) } }
            } catch (e: Exception) {
                fresh = false
                Log.i("CleanScreenTV", "weather fetch failed; using cache: $e")
                cached()
            }
            main.post {
                inFlight = false
                if (released) return@post
                nextDue = SystemClock.elapsedRealtime() + if (fresh) INTERVAL_MS else RETRY_MS
                main.removeCallbacks(tick)
                if (running) main.postDelayed(tick, nextDue - SystemClock.elapsedRealtime())
                onResult(reading)
            }
        }
    }

    private fun cached(): Reading? = try {
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS) {
            parse(cache.readText())
        } else null
    } catch (e: Exception) {
        null
    }

    private fun download(): String {
        val conn = URL(URL_STRING).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): Reading {
        val json = JSONObject(body)
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val highs = daily.getJSONArray("temperature_2m_max")
        val lows = daily.getJSONArray("temperature_2m_min")
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayName = SimpleDateFormat("EEE", Locale.US)
        val days = (0 until dates.length()).map {
            Day(
                dayName.format(iso.parse(dates.getString(it))!!),
                iconFor(codes.getInt(it), true),
                Math.round(highs.getDouble(it)).toInt(),
                Math.round(lows.getDouble(it)).toInt(),
            )
        }
        return Reading(
            Math.round(current.getDouble("temperature_2m")).toInt(),
            iconFor(current.getInt("weather_code"), current.optInt("is_day", 1) == 1),
            days.firstOrNull()?.high ?: 0,
            days.firstOrNull()?.low ?: 0,
            days,
        )
    }
}
