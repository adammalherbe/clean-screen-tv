package dk.adam.tv

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val IDLE_MS = 3 * 60 * 1000L
        private const val SPLASH_MS = 2800L
        /** adb test hook: `am start -n dk.adam.tv/.MainActivity --es test wake`. */
        private const val EXTRA_TEST = "test"
        private const val TILE_GAP_DP = 36f
        private val PILL_TOKEN = Any()
        private val ENGLISH = Locale.US
        // Once per process, so returning home doesn't replay it.
        private var splashPlayed = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val frost = FrostSource()
    private lateinit var backgrounds: Backgrounds

    private lateinit var bgContainer: View
    private lateinit var home: View
    private lateinit var center: ViewGroup
    private lateinit var scrimTop: View
    private lateinit var scrimTopRight: View
    private lateinit var greeting: TextView
    private lateinit var nextEvent: TextView
    private lateinit var signature: View
    private lateinit var ambientClock: TextView
    private lateinit var splash: View
    private lateinit var topCard: TopCard
    private lateinit var pillRow: PillRow
    private val tiles = ArrayList<FrostTile>()

    private lateinit var store: Store
    private lateinit var calendar: NextEvent
    private lateinit var weather: Weather
    private lateinit var nowPlaying: NowPlaying
    private lateinit var continueRow: ContinueRow
    private lateinit var resume: ResumePrompt
    private lateinit var settings: SettingsPanel
    private lateinit var sleepTimer: SleepTimer
    private lateinit var sleepOverlay: SleepOverlay
    private var toast: GlassCard? = null
    private var resumeAfterSplash = false

    private var ambient = false
    private var kenBurns: ObjectAnimator? = null
    private var swallowKey = KeyEvent.KEYCODE_UNKNOWN
    private var splashStartedAt = 0L

    private val enterAmbientRunnable = Runnable { enterAmbient() }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateTime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bgContainer = findViewById(R.id.bg_container)
        home = findViewById(R.id.home)
        center = findViewById(R.id.center)
        scrimTop = findViewById(R.id.scrim_top)
        scrimTopRight = findViewById(R.id.scrim_top_right)
        greeting = findViewById(R.id.greeting)
        nextEvent = findViewById(R.id.next_event)
        signature = findViewById(R.id.signature)
        ambientClock = findViewById(R.id.ambient_clock)
        splash = findViewById(R.id.splash)

        store = Store(this)
        Motion.load(this)
        sleepTimer = SleepTimer(this, store).also { it.restore() }
        Wake.listen(this)
        handleTestIntent(intent)

        backgrounds = Backgrounds(
            assets,
            findViewById<ImageView>(R.id.bg_bottom),
            findViewById<ImageView>(R.id.bg_top),
            findViewById(R.id.bg_tint),
            frost,
        )

        topCard = TopCard(this, frost)
        findViewById<FrameLayout>(R.id.top_card).addView(topCard.view, FrameLayout.LayoutParams(dp(440f), -2))
        buildTiles()
        pillRow = PillRow(this, frost, findViewById(R.id.pills),
            onChromecast = { Apps.launch(this, CHROMECAST) },
            onLaunch = { Apps.launchKey(this, it) },
            onSleep = { onSleepButton() })

        calendar = NextEvent(this) { showNextEvent() }
        weather = Weather(this) { topCard.show(it) }
        nowPlaying = NowPlaying(this, store) {
            showCast()
            scheduleAmbient()
        }
        continueRow = ContinueRow(this, store) { pillRow.setChips(it) }

        val root = findViewById<FrameLayout>(R.id.root)
        resume = ResumePrompt(this, frost, findViewById(R.id.resume_host),
            onSwitch = { Apps.launch(this, CHROMECAST) },
            onClosed = {
                showFooter(true)
                tiles.first().requestFocus()
            })
        sleepOverlay = SleepOverlay(this, root, onCancelled = {
            sleepTimer.cancel()
            updateSleepUi()
            scheduleAmbient()
        })
        settings = SettingsPanel(this, frost, root, store, onClosed = {
            center.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            tiles.last().requestFocus()
        })

        if (splashPlayed) {
            splash.visibility = View.GONE
        } else {
            splashPlayed = true
            splashStartedAt = SystemClock.uptimeMillis()
            findViewById<View>(R.id.splash_title).animate()
                .alpha(1f).setStartDelay(150).setDuration(1100).start()
            findViewById<View>(R.id.splash_credit).animate()
                .alpha(0.6f).setStartDelay(850).setDuration(1000).start()
            backgrounds.onFirstImage = { maybeEndSplash() }
            handler.postDelayed({ maybeEndSplash() }, SPLASH_MS)
            // Never hold the splash forever if decoding is unexpectedly slow.
            handler.postDelayed({ endSplash() }, 6000)
        }
        updateTime()
        if (intent?.action == SleepTimer.ACTION_SLEEP) startSleepCountdown()
    }

    private fun buildTiles() {
        val row = findViewById<LinearLayout>(R.id.tiles)
        val glow = dp(FrostTile.GLOW_DP.toFloat())
        val glass = dp(FrostTile.GLASS_DP.toFloat())
        TILE_APPS.forEachIndexed { i, app ->
            val tile = FrostTile(this, frost, app)
            tile.setOnClickListener { Apps.launch(this, app) }
            val lp = LinearLayout.LayoutParams(glass + 2 * glow, glow + glass + dp(FrostTile.LABEL_DP.toFloat()))
            // Glow padding overlaps between neighbours; the glass gap stays TILE_GAP_DP.
            if (i > 0) lp.leftMargin = dp(TILE_GAP_DP) - 2 * glow
            row.addView(tile, lp)
            tiles += tile
        }
        tiles.first().requestFocus()
    }

    private fun onSleepButton() {
        val minutes = sleepTimer.cycle()
        showToast(if (minutes == 0) "Sleep timer off" else "Sleep in $minutes min")
        updateSleepUi()
    }

    /** The moon button's countdown. */
    private fun updateSleepUi() {
        pillRow.setSleep(sleepTimer.remainingMinutes)
        topCard.setSleep(sleepTimer.remainingMinutes)
    }

    private fun startSleepCountdown() {
        if (ambient) exitAmbient(animate = false)
        settings.handleKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        handler.removeCallbacks(enterAmbientRunnable)
        updateSleepUi()
        sleepOverlay.start()
    }

    /** A glass pill where the signature is, for 2 seconds. */
    private fun showToast(text: String) {
        val root = findViewById<FrameLayout>(R.id.root)
        val p = toast ?: GlassCard(this, frost, radiusDp = 17f, padDp = 12f, focusable = false).apply {
            inner(20f, 8f, 20f, 8f)
            addView(homeText(14f, "sans-serif", 0.9f))
            root.addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(18f) })
            toast = this
        }
        (p.getChildAt(0) as TextView).text = text
        handler.removeCallbacksAndMessages(PILL_TOKEN)
        signature.animate().alpha(0f).setStartDelay(0).setDuration(Motion.ms(200)).start()
        p.fadeIn(250)
        handler.postAtTime({
            p.fadeOut(400)
            signature.animate().alpha(0.5f).setStartDelay(Motion.ms(300)).setDuration(Motion.ms(400)).start()
        }, PILL_TOKEN, SystemClock.uptimeMillis() + 2000)
    }

    /** The signature steps aside for the resume card. */
    private fun showFooter(show: Boolean) {
        signature.animate().alpha(if (show) 0.5f else 0f).setStartDelay(0).setDuration(Motion.ms(300)).start()
    }

    /** One Chromecast pill: what's casting now, else the last thing cast. */
    private fun showCast() = pillRow.setCast(
        nowPlaying.title ?: store.lastCast?.title,
        nowPlaying.status?.state?.let { nowPlaying.playing },
    )

    private fun maybeEndSplash() {
        if (splash.visibility != View.VISIBLE) return
        if (backgrounds.ready && SystemClock.uptimeMillis() - splashStartedAt >= SPLASH_MS) endSplash()
    }

    private fun endSplash() {
        if (splash.visibility != View.VISIBLE || splash.tag != null) return
        splash.tag = "ending"
        splash.animate().alpha(0f).setDuration(900)
            .withEndAction {
                splash.visibility = View.GONE
                if (resumeAfterSplash) {
                    resumeAfterSplash = false
                    showResume()
                }
            }
            .start()
    }

    /**
     * Holding OK on the Settings tile. Detected from key repeats rather than the view's
     * long-click timer, so it works for a held remote button and for adb --longpress.
     */
    private fun isSettingsLongPress(event: KeyEvent): Boolean {
        val confirm = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (!confirm || event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount == 0 && !event.isLongPress) return false
        val tile = currentFocus as? FrostTile ?: return false
        return tile.app.pkg == null && tile.app.inputId == null && tile.app.label == "Settings"
    }

    private fun openSettings() {
        center.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        settings.show()
    }

    /** After a boot or wake, offer (or do) the switch back to a recently used Chromecast. */
    private fun checkResume() {
        if (!Wake.take()) return
        val last = store.lastVisit
        android.util.Log.i("CleanScreenTV", "resume: after boot/wake, last destination ${last?.key}")
        if (last == null) return
        if (last.key != Store.HDMI || System.currentTimeMillis() - last.at > ResumePrompt.RECENT_MS) return
        android.util.Log.i("CleanScreenTV", "resume: ${if (store.autoResume) "switching to" else "offering"} Chromecast")
        when {
            store.autoResume -> Apps.launch(this, CHROMECAST)
            splash.visibility == View.VISIBLE -> resumeAfterSplash = true
            else -> showResume()
        }
    }

    private fun showResume() {
        showFooter(false)
        resume.show()
    }

    private fun handleTestIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_TEST)) {
            "wake" -> Wake.mark()
            "sleep" -> handler.postDelayed({ startSleepCountdown() }, 500)
            "sleep_off" -> if (::pillRow.isInitialized) {
                sleepTimer.cancel()
                updateSleepUi()
            }
            "ambient" -> handler.postDelayed({ enterAmbient() }, 300)
            // Recording mode (see Motion): freezes the scene and tint that are showing now.
            "record_on" -> {
                val now = Calendar.getInstance()
                Motion.set(this, true, Scenes.at(now.timeInMillis), Scenes.tint(DayClock.period(now)))
            }
            "record_off" -> {
                Motion.set(this, false, null, 0)
                if (::topCard.isInitialized) updateTime()
            }
            // Forget where I've been and what was cast: empty Continue row, plain Chromecast pill.
            "clear_history" -> {
                store.clearHistory()
                if (::pillRow.isInitialized) {
                    continueRow.refresh()
                    showCast()
                }
            }
            "next_scene" -> {
                Scenes.offset++
                if (::topCard.isInitialized) updateTime()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        updateTime()
        weather.start()
        calendar.start()
        nowPlaying.start()
    }

    override fun onResume() {
        super.onResume()
        tiles.forEach { it.refreshIcon() }
        continueRow.refresh()
        showCast()
        scheduleAmbient()
        checkResume()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(enterAmbientRunnable)
        if (ambient) exitAmbient(animate = false)
    }

    override fun onStop() {
        super.onStop()
        weather.stop()
        calendar.stop()
        nowPlaying.stop()
        unregisterReceiver(timeReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        backgrounds.release()
        weather.release()
        calendar.release()
        nowPlaying.release()
        continueRow.release()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestIntent(intent)
        if (intent.action == SleepTimer.ACTION_SLEEP) {
            startSleepCountdown()
            return
        }
        // HOME pressed while already home.
        if (ambient) exitAmbient(animate = true)
        scheduleAmbient()
    }

    private fun showNextEvent() {
        if (!::calendar.isInitialized) return
        val line = calendar.line()
        if (line == null) {
            nextEvent.fadeOut()
        } else {
            nextEvent.text = line
            nextEvent.fadeIn(to = 0.7f)
        }
    }

    private fun updateTime() {
        val now = Calendar.getInstance()
        val time = SimpleDateFormat("HH:mm", ENGLISH).format(now.time)
        topCard.setTime(time, SimpleDateFormat("EEEE, d MMMM", ENGLISH).format(now.time))
        ambientClock.text = time
        greeting.text = DayClock.greeting(now.get(Calendar.HOUR_OF_DAY))

        backgrounds.show(Scenes.at(now.timeInMillis))
        backgrounds.tint(Scenes.tint(DayClock.period(now)))

        showNextEvent()
        if (::sleepOverlay.isInitialized) {
            sleepOverlay.setTime(time)
            updateSleepUi()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (sleepOverlay.handleKey(event)) return true
        if (ambient) {
            // The press that wakes the screen does nothing else.
            if (event.action == KeyEvent.ACTION_DOWN) {
                swallowKey = event.keyCode
                exitAmbient(animate = true)
            }
            return true
        }
        if (event.keyCode == swallowKey) {
            if (event.action == KeyEvent.ACTION_UP) swallowKey = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        scheduleAmbient()
        if (settings.handleKey(event) || resume.handleKey(event)) return true
        if (isSettingsLongPress(event)) {
            swallowKey = event.keyCode
            openSettings()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && isDefaultHome()) return true
        return super.dispatchKeyEvent(event)
    }

    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == packageName
    }

    private fun scheduleAmbient() {
        handler.removeCallbacks(enterAmbientRunnable)
        // Stay awake while something is playing on the Chromecast.
        if (::nowPlaying.isInitialized && nowPlaying.playing) return
        handler.postDelayed(enterAmbientRunnable, IDLE_MS)
    }

    private fun enterAmbient() {
        if (ambient || splash.visibility == View.VISIBLE) return
        ambient = true
        home.animate().alpha(0f).setDuration(1500).start()
        scrimTop.animate().alpha(0f).setDuration(1500).start()
        scrimTopRight.animate().alpha(0f).setDuration(1500).start()
        ambientClock.animate().alpha(1f).setStartDelay(900).setDuration(1800).start()

        val w = bgContainer.width.toFloat()
        // Recording mode: a still image, only the clock fades in.
        if (Motion.recording) return
        kenBurns = ObjectAnimator.ofPropertyValuesHolder(
            bgContainer,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f),
            // Never pans further than the zoom leaves room for.
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, -w * 0.05f),
        ).apply {
            duration = 70_000
            interpolator = AccelerateDecelerateInterpolator()
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun exitAmbient(animate: Boolean) {
        ambient = false
        kenBurns?.cancel()
        kenBurns = null
        val ms = if (animate) 900L else 0L
        bgContainer.animate().scaleX(1f).scaleY(1f).translationX(0f).setDuration(ms).start()
        home.animate().alpha(1f).setStartDelay(0).setDuration(ms).start()
        scrimTop.animate().alpha(1f).setDuration(ms).start()
        scrimTopRight.animate().alpha(1f).setDuration(ms).start()
        ambientClock.animate().alpha(0f).setStartDelay(0).setDuration(ms / 2).start()
        scheduleAmbient()
    }
}
