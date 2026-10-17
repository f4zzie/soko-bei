package ke.sokobei.time

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class BusinessCalendarTest {
    private val cal = BusinessCalendar(Clock.fixed(Instant.parse("2026-10-17T20:59:59Z"), ZoneOffset.UTC))

    @Test fun `2359 EAT is still the same EAT day even though UTC is 2059`() {
        assertEquals(LocalDate.parse("2026-10-17"), cal.today())
        assertEquals(LocalDate.parse("2026-10-17"), cal.dayOf(Instant.parse("2026-10-17T20:59:59Z")))
    }

    @Test fun `0001 EAT is the next day while UTC is still the previous day`() {
        assertEquals(LocalDate.parse("2026-10-18"), cal.dayOf(Instant.parse("2026-10-17T21:01:00Z")))
        assertEquals(LocalDate.parse("2026-10-18"), cal.dayOf(Instant.parse("2026-10-17T21:00:00Z")))
    }

    @Test fun `month boundary uses EAT`() {
        assertEquals("2026-10", cal.monthKey(Instant.parse("2026-10-31T20:59:59Z")))
        assertEquals("2026-11", cal.monthKey(Instant.parse("2026-10-31T21:00:00Z")))
    }

    @Test fun `year boundary uses EAT`() {
        assertEquals(2026, cal.year(Instant.parse("2026-12-31T20:59:59Z")))
        assertEquals(2027, cal.year(Instant.parse("2026-12-31T21:00:00Z")))
        assertEquals("2027-01", cal.monthKey(Instant.parse("2026-12-31T21:30:00Z")))
    }

    @Test fun `day bounds are EAT midnights`() {
        val d = LocalDate.parse("2026-03-01")
        assertEquals(Instant.parse("2026-02-28T21:00:00Z"), cal.startOfDay(d))
        assertEquals(Instant.parse("2026-03-01T21:00:00Z"), cal.startOfNextDay(d))
    }

    @Test fun `leap day`() {
        assertEquals(LocalDate.parse("2028-02-29"), cal.dayOf(Instant.parse("2028-02-28T21:00:00Z")))
        assertEquals("2028-02", cal.monthKey(LocalDate.parse("2028-02-29")))
    }
}
