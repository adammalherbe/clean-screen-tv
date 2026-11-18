package dk.adam.tv

/**
 * The background rotation: five CC0 scenes (sources in CREDITS.md), one every 20
 * minutes, each tinted for the time of day.
 */
object Scenes {

    const val ROTATE_MS = 20 * 60 * 1000L

    private val ASSETS = listOf("mountain", "forest", "coast", "desert", "aurora")

    /** Test hook (adb `--es test next_scene`): skips ahead this many scenes. */
    var offset = 0

    /** Same answer on every device and after restarts: it only depends on the clock. */
    fun at(now: Long): String =
        Motion.frozenScene ?: ASSETS[((now / ROTATE_MS + offset) % ASSETS.size).toInt()]

    /** ARGB laid over the photo: warm at dawn, none by day, rose at dusk, deep blue at night. */
    fun tint(period: Period): Int = if (Motion.recording) Motion.frozenTint else when (period) {
        Period.DAWN -> 0x33FF9A5C
        Period.DAY -> 0x00000000
        Period.DUSK -> 0x47A8406A
        Period.NIGHT -> 0x8C060B22.toInt()
    }
}
