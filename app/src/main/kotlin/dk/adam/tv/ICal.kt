package dk.adam.tv

import java.util.Calendar
import java.util.TimeZone

/**
 * A minimal iCalendar reader: VEVENTs with DTSTART/DTEND/SUMMARY, all-day dates,
 * TZID/UTC/floating times, EXDATE, RECURRENCE-ID overrides, and RRULE with
 * FREQ=DAILY/WEEKLY (plus simple MONTHLY/YEARLY, for birthdays), INTERVAL, COUNT,
 * UNTIL and weekly BYDAY.
 */
object ICal {

    class Occurrence(val summary: String, val start: Long, val allDay: Boolean)

    private class Time(val y: Int, val m: Int, val d: Int, val h: Int, val min: Int, val s: Int,
                       val allDay: Boolean, val tz: TimeZone) {
        val millis: Long get() = toMillis(tz, y, m, d, h, min, s)
        val epochDay: Long get() = epochDay(y, m, d)
    }

    private class Event {
        var uid = ""
        var summary = ""
        var start: Time? = null
        var cancelled = false
        var rrule: Map<String, String>? = null
        val exdates = HashSet<Long>()
        var recurrenceId: Long? = null
    }

    private val WEEKDAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
    private const val MAX_STEPS = 100_000

    /** Occurrences that start in [from, to), sorted by start. */
    fun occurrences(ics: String, from: Long, to: Long, local: TimeZone = TimeZone.getDefault()): List<Occurrence> {
        val events = parse(ics, local)
        // Instances moved or edited individually replace the master's occurrence.
        val overridden = HashSet<String>()
        events.forEach { e -> e.recurrenceId?.let { overridden += "${e.uid}@$it" } }

        val out = ArrayList<Occurrence>()
        for (e in events) {
            val start = e.start ?: continue
            if (e.cancelled) continue
            val rule = e.rrule
            if (rule == null || e.recurrenceId != null) {
                val t = start.millis
                if (t in from until to) out += Occurrence(e.summary, t, start.allDay)
                continue
            }
            expand(e, start, rule, from, to, local) { t ->
                if (t !in e.exdates && "${e.uid}@$t" !in overridden) out += Occurrence(e.summary, t, start.allDay)
            }
        }
        return out.sortedBy { it.start }
    }

    private fun expand(e: Event, start: Time, rule: Map<String, String>, from: Long, to: Long,
                       local: TimeZone, emit: (Long) -> Unit) {
        val freq = rule["FREQ"] ?: return
        val interval = rule["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val count = rule["COUNT"]?.toIntOrNull()
        val until = rule["UNTIL"]?.let { parseTime(it, emptyMap(), local)?.millis }
        val tz = start.tz
        val toDay = epochDay(tz, to) + 1
        val fromDay = epochDay(tz, from) - 1

        fun at(day: Long): Long {
            val (y, m, d) = civil(day)
            return toMillis(tz, y, m, d, start.h, start.min, start.s)
        }

        var seen = 0
        // Done when past the window, the count, or the end date.
        fun consider(day: Long): Boolean {
            if (day < start.epochDay) return false
            val t = at(day)
            if (until != null && t > until) return true
            seen++
            if (count != null && seen > count) return true
            if (t in from until to) emit(t)
            return day > toDay
        }

        when (freq) {
            "DAILY" -> {
                // Without COUNT we can jump straight to the window.
                var k = if (count == null) ((fromDay - start.epochDay) / interval).coerceAtLeast(0) else 0
                var steps = 0
                while (steps++ < MAX_STEPS && !consider(start.epochDay + k * interval)) k++
            }
            "WEEKLY" -> {
                val byDay = rule["BYDAY"]?.split(',')?.mapNotNull { WEEKDAYS.indexOf(it.takeLast(2)).takeIf { i -> i >= 0 } }
                    ?.sorted()
                    ?: listOf(weekdayIndex(start.epochDay))
                val week0 = start.epochDay - weekdayIndex(start.epochDay)
                var w = if (count == null) ((fromDay - week0) / 7 / interval).coerceAtLeast(0) else 0
                var steps = 0
                outer@ while (steps++ < MAX_STEPS) {
                    for (offset in byDay) if (consider(week0 + (w * interval) * 7 + offset)) break@outer
                    w++
                }
            }
            "MONTHLY", "YEARLY" -> {
                val months = if (freq == "MONTHLY") interval else 12 * interval
                var k = 0L
                var steps = 0
                while (steps++ < MAX_STEPS) {
                    val total = start.y * 12L + (start.m - 1) + k * months
                    val y = (total / 12).toInt()
                    val m = (total % 12).toInt() + 1
                    k++
                    if (start.d > daysInMonth(y, m)) continue
                    if (consider(epochDay(y, m, start.d))) break
                }
            }
            else -> {
                val t = start.millis
                if (t in from until to) emit(t)
            }
        }
    }

    private fun parse(ics: String, local: TimeZone): List<Event> {
        val text = ics.replace("\r\n", "\n").replace("\r", "\n").replace("\n ", "").replace("\n\t", "")
        val events = ArrayList<Event>()
        var current: Event? = null
        for (line in text.split('\n')) {
            when {
                line == "BEGIN:VEVENT" -> current = Event()
                line == "END:VEVENT" -> current?.let { events += it }.also { current = null }
                current != null -> {
                    val e = current!!
                    val colon = valueStart(line)
                    if (colon < 0) continue
                    val head = line.substring(0, colon).split(';')
                    val value = line.substring(colon + 1)
                    val params = head.drop(1).mapNotNull {
                        val i = it.indexOf('=')
                        if (i > 0) it.substring(0, i).uppercase() to it.substring(i + 1).trim('"') else null
                    }.toMap()
                    when (head[0].uppercase()) {
                        "UID" -> e.uid = value
                        "SUMMARY" -> e.summary = unescape(value)
                        "DTSTART" -> e.start = parseTime(value, params, local)
                        "STATUS" -> e.cancelled = value.equals("CANCELLED", true)
                        "RRULE" -> e.rrule = value.split(';').mapNotNull {
                            val i = it.indexOf('=')
                            if (i > 0) it.substring(0, i).uppercase() to it.substring(i + 1) else null
                        }.toMap()
                        "EXDATE" -> value.split(',').forEach { v -> parseTime(v, params, local)?.let { e.exdates += it.millis } }
                        "RECURRENCE-ID" -> e.recurrenceId = parseTime(value, params, local)?.millis
                    }
                }
            }
        }
        return events
    }

    /** The colon that ends the property name and parameters, ignoring colons in quotes. */
    private fun valueStart(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ':' -> if (!quoted) return i
            }
        }
        return -1
    }

    private fun unescape(v: String) =
        v.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private fun parseTime(value: String, params: Map<String, String>, local: TimeZone): Time? {
        val v = value.trim()
        if (v.length < 8) return null
        val y = v.substring(0, 4).toIntOrNull() ?: return null
        val m = v.substring(4, 6).toIntOrNull() ?: return null
        val d = v.substring(6, 8).toIntOrNull() ?: return null
        if (params["VALUE"] == "DATE" || v.length == 8) return Time(y, m, d, 0, 0, 0, true, local)
        if (v.length < 15 || v[8] != 'T') return null
        val h = v.substring(9, 11).toIntOrNull() ?: return null
        val min = v.substring(11, 13).toIntOrNull() ?: return null
        val s = v.substring(13, 15).toIntOrNull() ?: 0
        val tz = when {
            v.endsWith("Z") -> TimeZone.getTimeZone("UTC")
            params["TZID"] != null -> zone(params.getValue("TZID"), local)
            else -> local
        }
        return Time(y, m, d, h, min, s, false, tz)
    }

    /** Unknown zone IDs come back from Java as GMT; fall back to local time instead. */
    private fun zone(id: String, local: TimeZone): TimeZone {
        val tz = TimeZone.getTimeZone(id)
        return if (tz.id == "GMT" && !id.equals("GMT", true)) local else tz
    }

    private fun toMillis(tz: TimeZone, y: Int, m: Int, d: Int, h: Int, min: Int, s: Int): Long =
        Calendar.getInstance(tz).apply {
            clear()
            set(y, m - 1, d, h, min, s)
        }.timeInMillis

    private fun epochDay(tz: TimeZone, millis: Long): Long {
        val c = Calendar.getInstance(tz).apply { timeInMillis = millis }
        return epochDay(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
    private fun epochDay(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }

    private fun civil(day: Long): Triple<Int, Int, Int> {
        val z = day + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return Triple(y, m, d)
    }

    /** 0 = Monday. 1970-01-01 was a Thursday. */
    private fun weekdayIndex(day: Long): Int = (((day + 3) % 7 + 7) % 7).toInt()

    private fun daysInMonth(y: Int, m: Int): Int = when (m) {
        2 -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
}
