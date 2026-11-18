package dk.adam.tv

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * The sleep timer. An exact alarm, so it keeps counting while YouTube, Netflix or
 * HDMI is in front; when it fires, [SleepReceiver] brings Clean Screen TV forward for the
 * final 60-second countdown.
 */
class SleepTimer(private val context: Context, private val store: Store) {

    companion object {
        val STEPS = listOf(30, 60, 90)
        const val ACTION_SLEEP = "dk.adam.tv.SLEEP"
    }

    /** Minutes left, rounded up, or null when off. */
    val remainingMinutes: Int?
        get() {
            val at = store.sleepAt
            if (at == 0L) return null
            val left = at - System.currentTimeMillis()
            return if (left <= 0) null else ((left + 59_999) / 60_000).toInt()
        }

    /** 30 → 60 → 90 → off. Returns the new setting in minutes, 0 for off. */
    fun cycle(): Int {
        val next = STEPS.getOrNull(STEPS.indexOf(store.sleepStep) + 1) ?: 0
        if (next == 0) cancel() else set(next)
        return next
    }

    fun cancel() {
        store.sleepAt = 0
        store.sleepStep = 0
        alarms().cancel(pending())
    }

    /** Re-arms after a process restart; clears a timer that expired while we weren't looking. */
    fun restore() {
        val at = store.sleepAt
        if (at == 0L) return
        if (at < System.currentTimeMillis() - 60_000) cancel() else arm(at)
    }

    private fun set(minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        store.sleepStep = minutes
        store.sleepAt = at
        arm(at)
    }

    private fun arm(at: Long) {
        if (Build.VERSION.SDK_INT >= 23) alarms().setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending())
        else alarms().setExact(AlarmManager.RTC_WAKEUP, at, pending())
    }

    private fun alarms() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, SleepReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0),
    )
}

/** The timer reached zero: bring Clean Screen TV to the front for the final countdown. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store(context).sleepAt = 0
        context.startActivity(Intent(context, MainActivity::class.java)
            .setAction(SleepTimer.ACTION_SLEEP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Lets Clean Screen TV lock (turn off) the screen. Activated over adb; asks for nothing else. */
class SleepAdmin : DeviceAdminReceiver()

/**
 * Puts the TV in standby. Tested on this Sony (Android 7.0):
 * (a) `input keyevent POWER` from the app does nothing useful: without INJECT_EVENTS
 *     the key only reaches Clean Screen TV's own window. Kept for the record, not used.
 * (b) DevicePolicyManager.lockNow(): the TV lacks android.software.device_admin.
 * (c) the same key event through the TV's own network ADB: works, once the TV has
 *     authorized Clean Screen TV's key, and only while no computer is connected over ADB.
 * Each attempt is checked by whether the screen actually turned off.
 */
object Standby {
    private const val TAG = "CleanScreenTV"

    /** Blocks for a few seconds; call off the main thread. Returns the method that worked, or null. */
    fun sleep(context: Context): String? {
        if (tryLockNow(context)) return "lockNow"
        if (tryAdb(context)) return "adb"
        return null
    }

    fun tryKeyEvent(context: Context): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("input", "keyevent", "26"))
        val err = p.errorStream.bufferedReader().readText()
        val code = p.waitFor()
        Log.i(TAG, "standby keyevent: exit=$code ${err.lineSequence().firstOrNull().orEmpty()}")
        screenWentOff(context)
    } catch (e: Exception) {
        Log.i(TAG, "standby keyevent failed: $e")
        false
    }

    fun tryLockNow(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, SleepAdmin::class.java)
        if (!dpm.isAdminActive(admin)) {
            Log.i(TAG, "standby lockNow: Clean Screen TV is not a device admin")
            return false
        }
        return try {
            dpm.lockNow()
            screenWentOff(context).also { Log.i(TAG, "standby lockNow: screen off=$it") }
        } catch (e: SecurityException) {
            Log.i(TAG, "standby lockNow failed: $e")
            false
        }
    }

    /** [authWaitMs]: how long to wait for the TV's one-time "Allow USB debugging?" prompt. */
    fun tryAdb(context: Context, authWaitMs: Int = 3000): Boolean {
        val out = AdbLocal(context).shell("input keyevent 26", authWaitMs) ?: return false
        Store(context).adbReady = true
        Log.i(TAG, "standby adb: ran (${out.trim()})")
        return screenWentOff(context)
    }

    private fun screenWentOff(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        repeat(6) {
            if (!pm.isInteractive) return true
            Thread.sleep(500)
        }
        return !pm.isInteractive
    }
}
