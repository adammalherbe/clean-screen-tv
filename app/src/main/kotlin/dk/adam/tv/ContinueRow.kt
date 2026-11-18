package dk.adam.tv

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * The Continue chips: up to four apps used in the past week, newest first, launched
 * from Clean Screen TV or seen in usage stats. The Chromecast has its own pill, so it isn't
 * repeated here. [PillRow] shows as many as fit.
 */
class ContinueRow(
    private val context: Context,
    private val store: Store,
    private val onItems: (List<Item>) -> Unit,
) {
    class Item(val key: String, val label: String, val at: Long, val icon: Drawable?)

    companion object {
        private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
        private const val MAX = 4
        // Not "things I watch": stores, settings, search, Sony's input viewer.
        private val EXCLUDED = setOf(
            "com.android.tv.settings", "com.android.vending", "com.google.android.gms",
            "com.google.android.katniss", "com.google.android.leanbacklauncher", "com.sony.dtv.tvx",
            // Not wanted here.
            "com.amazon.amazonvideo.livingroom", "com.amazon.aiv.eu",
        )
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Rebuilds the list in the background; cheap enough to call on every return home. */
    fun refresh() {
        io.execute {
            val items = try {
                collect()
            } catch (e: Exception) {
                Log.w("CleanScreenTV", "continue row failed", e)
                emptyList()
            }
            main.post { onItems(items) }
        }
    }

    fun release() = io.shutdownNow()

    private fun collect(): List<Item> {
        val now = System.currentTimeMillis()
        val since = now - WEEK_MS
        val latest = HashMap<String, Long>()
        fun note(key: String, at: Long) {
            if (at >= since && at > (latest[key] ?: 0L)) latest[key] = at
        }

        store.visits.filter { it.key !in EXCLUDED && it.key != Store.HDMI }.forEach { note(it.key, it.at) }

        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        // Empty unless usage access has been granted (adb appops; see tools/install.sh).
        usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, since, now)
            ?.filter { it.totalTimeInForeground > 0 && it.lastTimeUsed > 0 }
            ?.forEach { if (isWatchable(it.packageName)) note(it.packageName, it.lastTimeUsed) }

        val pm = context.packageManager
        return latest.entries.sortedByDescending { it.value }.mapNotNull { (key, at) ->
            val info = try {
                pm.getApplicationInfo(key, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                return@mapNotNull null
            }
            Item(key, pm.getApplicationLabel(info).toString(), at, Apps.icon(context, key))
        }.take(MAX)
    }

    /**
     * A TV app I'd go back to: it has a TV launcher entry and isn't a launcher or one of
     * the system tools above. Not filtered on FLAG_SYSTEM: YouTube is a system app here.
     */
    private fun isWatchable(pkg: String): Boolean {
        if (pkg == context.packageName || pkg in EXCLUDED) return false
        val pm = context.packageManager
        if (pm.getLeanbackLaunchIntentForPackage(pkg) == null) return false
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg)
        return pm.queryIntentActivities(home, 0).isEmpty()
    }
}
