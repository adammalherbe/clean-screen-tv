package dk.adam.tv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ICalTest {
    private val tz = TimeZone.getTimeZone("Europe/Berlin")
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = tz }
    private fun t(s: String) = fmt.parse(s)!!.time

    private fun cal(vararg events: String) =
        "BEGIN:VCALENDAR\r\n" + events.joinToString("") { "BEGIN:VEVENT\r\n$it\r\nEND:VEVENT\r\n" } + "END:VCALENDAR\r\n"

    private fun starts(ics: String, from: String, to: String) =
        ICal.occurrences(ics, t(from), t(to), tz).map { fmt.format(it.start) + " " + it.summary + if (it.allDay) " (all-day)" else "" }

    @Test fun singleWithTzid() {
        val ics = cal("UID:1\r\nDTSTART;TZID=Europe/Berlin:20261108T153000\r\nSUMMARY:Lunch")
        assertEquals(listOf("2026-11-08 15:30 Lunch"), starts(ics, "2026-11-08 00:00", "2026-11-10 00:00"))
    }

    @Test fun utcTime() {
        val ics = cal("UID:1\r\nDTSTART:20261108T143000Z\r\nSUMMARY:Call")
        assertEquals(listOf("2026-11-08 15:30 Call"), starts(ics, "2026-11-08 00:00", "2026-11-10 00:00"))
    }

    @Test fun allDayAndFoldedEscapedSummary() {
        val ics = cal("UID:1\r\nDTSTART;VALUE=DATE:20261109\r\nSUMMARY:Mom\\'s birthday\\, cake\r\n  at home")
        assertEquals(listOf("2026-11-09 00:00 Mom\\'s birthday, cake at home (all-day)"),
            starts(ics, "2026-11-08 00:00", "2026-11-11 00:00"))
    }

    @Test fun yearlyBirthdayFromLongAgo() {
        val ics = cal("UID:1\r\nDTSTART;VALUE=DATE:19600109\r\nRRULE:FREQ=YEARLY\r\nSUMMARY:Mom")
        assertEquals(listOf("2027-01-09 00:00 Mom (all-day)"), starts(ics, "2027-01-08 00:00", "2027-01-11 00:00"))
    }

    @Test fun dailyWithExdateAndOverride() {
        val ics = cal(
            "UID:d\r\nDTSTART;TZID=Europe/Berlin:20200101T090000\r\nRRULE:FREQ=DAILY\r\n" +
                "EXDATE;TZID=Europe/Berlin:20261109T090000\r\nSUMMARY:Standup",
            "UID:d\r\nRECURRENCE-ID;TZID=Europe/Berlin:20261110T090000\r\n" +
                "DTSTART;TZID=Europe/Berlin:20261110T100000\r\nSUMMARY:Standup (moved)",
        )
        assertEquals(
            listOf("2026-11-08 09:00 Standup", "2026-11-10 10:00 Standup (moved)", "2026-11-11 09:00 Standup"),
            starts(ics, "2026-11-08 00:00", "2026-11-12 00:00"),
        )
    }

    @Test fun weeklyByDayIntervalAcrossDst() {
        // Every other week Mon+Thu, starting Thu 2026-10-01; DST ends 2026-10-25.
        val ics = cal("UID:w\r\nDTSTART;TZID=Europe/Berlin:20261001T180000\r\n" +
            "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH\r\nSUMMARY:Gym")
        assertEquals(
            listOf("2026-10-01 18:00 Gym", "2026-10-12 18:00 Gym", "2026-10-15 18:00 Gym",
                "2026-10-26 18:00 Gym", "2026-10-29 18:00 Gym"),
            starts(ics, "2026-09-28 00:00", "2026-11-01 00:00"),
        )
    }

    @Test fun countAndUntilStop() {
        val ics = cal(
            "UID:c\r\nDTSTART;TZID=Europe/Berlin:20261105T080000\r\nRRULE:FREQ=DAILY;COUNT=4\r\nSUMMARY:Gym",
            "UID:u\r\nDTSTART;TZID=Europe/Berlin:20261105T200000\r\nRRULE:FREQ=DAILY;UNTIL=20261107T190000Z\r\nSUMMARY:Class",
        )
        assertEquals(
            listOf("2026-11-07 08:00 Gym", "2026-11-07 20:00 Class", "2026-11-08 08:00 Gym"),
            starts(ics, "2026-11-07 00:00", "2026-11-12 00:00"),
        )
    }

    @Test fun cancelledSkipped() {
        val ics = cal("UID:x\r\nDTSTART:20261108T143000Z\r\nSTATUS:CANCELLED\r\nSUMMARY:Gone")
        assertEquals(emptyList<String>(), starts(ics, "2026-11-08 00:00", "2026-11-10 00:00"))
    }
}
