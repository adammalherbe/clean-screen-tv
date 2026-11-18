package dk.adam.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * What the Chromecast is playing: polled every 10 s while Clean Screen TV is in front. The
 * Chromecast pill shows the title; the last title is remembered for the Continue chips.
 */
class NowPlaying(
    context: Context,
    private val store: Store,
    private val onChanged: () -> Unit,
) {
    companion object {
        private const val TAG = "CleanScreenTV"
        private const val POLL_MS = 10_000L
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val finder = CastFinder(context) { ip ->
        store.chromecastIp = ip
        poll()
    }
    private var running = false
    private var released = false
    private var failures = 0
    private var searched = false
    private val tick = Runnable { poll() }

    var status: CastStatus? = null
        private set

    /** Something is actually playing (not paused): ambient mode waits. */
    val playing get() = status?.playing == true

    /** The title to show, or the app's name when it reports no media. */
    val title get() = status?.let { it.title ?: it.app }

    fun start() {
        running = true
        if (!searched) {
            searched = true
            finder.find()
        }
        poll()
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        finder.stop()
    }

    fun release() {
        released = true
        stop()
        io.shutdownNow()
    }

    private fun ip(): String? = store.chromecastIp ?: BuildConfig.CHROMECAST_IP.ifEmpty { null }

    private fun poll() {
        main.removeCallbacks(tick)
        val ip = ip()
        if (ip == null) {
            finder.find()
            return
        }
        io.execute {
            var reached = true
            val result = try {
                CastClient.status(ip)
            } catch (e: Exception) {
                reached = false
                Log.i(TAG, "cast: $ip unreachable: $e")
                null
            }
            main.post {
                if (released) return@post
                failures = if (reached) 0 else failures + 1
                // The Chromecast may have a new address: look again, and fall back to config.
                if (failures == 3) {
                    store.chromecastIp = null
                    finder.find()
                }
                val changed = result?.title != status?.title || result?.state != status?.state ||
                    (result == null) != (status == null)
                status = result
                result?.title?.let { store.lastCast = Store.Cast(it, result.app, System.currentTimeMillis()) }
                if (changed) onChanged()
                if (running) main.postDelayed(tick, POLL_MS)
            }
        }
    }
}
