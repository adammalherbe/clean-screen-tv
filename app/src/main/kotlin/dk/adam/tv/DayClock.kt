package dk.adam.tv

import java.util.Calendar
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

enum class Period(val asset: String) { DAWN("dawn"), DAY("day"), DUSK("dusk"), NIGHT("night") }

object DayClock {
    // Your location, from config.local.json. Used for sunrise/sunset and the weather.
    val LAT: Double = BuildConfig.LATITUDE.toDoubleOrNull() ?: 37.33
    val LON: Double = BuildConfig.LONGITUDE.toDoubleOrNull() ?: -122.01

    private val NAME: String = BuildConfig.OWNER_NAME.trim()

    fun greeting(hour: Int): String {
        val base = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
        }
        return if (NAME.isEmpty()) base else "$base, $NAME"
    }

    /** Dawn and dusk are the hour either side of sunrise and sunset. */
    fun period(cal: Calendar): Period {
        val (rise, set) = sunTimes(cal)
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return when {
            m >= rise - 60 && m < rise + 60 -> Period.DAWN
            m >= rise + 60 && m < set - 60 -> Period.DAY
            m >= set - 60 && m < set + 60 -> Period.DUSK
            else -> Period.NIGHT
        }
    }

    /** Sunrise and sunset in local minutes after midnight (NOAA approximation). */
    private fun sunTimes(cal: Calendar): Pair<Double, Double> {
        val g = 2 * PI / 365 * (cal.get(Calendar.DAY_OF_YEAR) - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) -
            0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val lat = Math.toRadians(LAT)
        val cosHa = (cos(Math.toRadians(90.833)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl))
            .coerceIn(-1.0, 1.0)
        val ha = Math.toDegrees(acos(cosHa))
        val tz = cal.timeZone.getOffset(cal.timeInMillis) / 60000.0
        return (720 - 4 * (LON + ha) - eqTime + tz) to (720 - 4 * (LON - ha) - eqTime + tz)
    }
}
