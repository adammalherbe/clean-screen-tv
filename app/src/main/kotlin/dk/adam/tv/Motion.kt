package dk.adam.tv

import android.content.Context

/**
 * Recording mode, for screen recordings on this TV, which captures only ~6 fps while
 * things move. When on: focus and fades are instant, the background scene and tint are
 * frozen, and ambient mode has no Ken Burns pan. The splash and ambient fades stay.
 * Stored, so it survives the app restart at the start of a take.
 * adb: `--es test record_on` / `--es test record_off`.
 */
object Motion {
    var recording = false
        private set
    /** The scene and tint frozen while recording. */
    var frozenScene: String? = null
        private set
    var frozenTint = 0
        private set

    fun load(context: Context) {
        val p = prefs(context)
        recording = p.getBoolean("recording", false)
        frozenScene = p.getString("frozen_scene", null)
        frozenTint = p.getInt("frozen_tint", 0)
    }

    fun set(context: Context, on: Boolean, scene: String?, tint: Int) {
        recording = on
        frozenScene = if (on) scene else null
        frozenTint = if (on) tint else 0
        prefs(context).edit()
            .putBoolean("recording", on)
            .putString("frozen_scene", frozenScene)
            .putInt("frozen_tint", frozenTint)
            .apply()
    }

    /** Animation duration: as given, or instant while recording. */
    fun ms(normal: Long): Long = if (recording) 0 else normal

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("adam_tv_motion", Context.MODE_PRIVATE)
}
