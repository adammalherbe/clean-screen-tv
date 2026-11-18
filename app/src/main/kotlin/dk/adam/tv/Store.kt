package dk.adam.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small persistent state: where I went from Clean Screen TV, settings, sleep timer, last cast. */
class Store(context: Context) {

    /** A place Clean Screen TV can send me: an app package, or [HDMI] for the Chromecast input. */
    class Visit(val key: String, val at: Long)

    companion object {
        const val HDMI = "hdmi"
        private const val MAX_VISITS = 20
    }

    private val prefs = context.applicationContext.getSharedPreferences("adam_tv", Context.MODE_PRIVATE)

    /** Newest first, one entry per destination. */
    val visits: List<Visit>
        get() {
            val arr = try {
                JSONArray(prefs.getString("visits", "[]"))
            } catch (e: Exception) {
                JSONArray()
            }
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Visit(o.getString("k"), o.getLong("t"))
            }
        }

    fun recordVisit(key: String, at: Long = System.currentTimeMillis()) {
        val list = listOf(Visit(key, at)) + visits.filter { it.key != key }
        val arr = JSONArray()
        list.take(MAX_VISITS).forEach { arr.put(JSONObject().put("k", it.key).put("t", it.at)) }
        prefs.edit().putString("visits", arr.toString()).apply()
    }

    val lastVisit: Visit? get() = visits.firstOrNull()

    fun clearHistory() {
        prefs.edit().remove("visits").remove("cast_title").remove("cast_app").remove("cast_at").apply()
    }

    var autoResume: Boolean
        get() = prefs.getBoolean("auto_resume", false)
        set(value) = prefs.edit().putBoolean("auto_resume", value).apply()

    /** Wall-clock time the sleep timer fires, or 0 when off. */
    var sleepAt: Long
        get() = prefs.getLong("sleep_at", 0)
        set(value) = prefs.edit().putLong("sleep_at", value).apply()

    /** The step the sleep tile last selected: 0 off, else minutes. */
    var sleepStep: Int
        get() = prefs.getInt("sleep_step", 0)
        set(value) = prefs.edit().putInt("sleep_step", value).apply()

    class Cast(val title: String, val app: String, val at: Long)

    var lastCast: Cast?
        get() {
            val title = prefs.getString("cast_title", null) ?: return null
            return Cast(title, prefs.getString("cast_app", "").orEmpty(), prefs.getLong("cast_at", 0))
        }
        set(value) {
            val e = prefs.edit()
            if (value == null) e.remove("cast_title").remove("cast_app").remove("cast_at")
            else e.putString("cast_title", value.title).putString("cast_app", value.app).putLong("cast_at", value.at)
            e.apply()
        }

    /** Set once Clean Screen TV's key has been accepted by the TV's ADB (sleep timer standby). */
    var adbReady: Boolean
        get() = prefs.getBoolean("adb_ready", false)
        set(value) = prefs.edit().putBoolean("adb_ready", value).apply()

    var chromecastIp: String?
        get() = prefs.getString("cast_ip", null)
        set(value) = prefs.edit().putString("cast_ip", value).apply()
}
