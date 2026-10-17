package ke.sokobei.time

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The single place where business dates are derived. Everything is Africa/Nairobi (EAT, UTC+3, no DST),
 * never server-local or UTC. Tests control "now" by passing a fixed [Clock].
 */
class BusinessCalendar(private val clock: Clock = Clock.systemUTC()) {
    fun now(): Instant = clock.instant()
    fun today(): LocalDate = dayOf(now())
    fun dayOf(instant: Instant): LocalDate = instant.atZone(EAT).toLocalDate()
    fun dayOfMs(epochMs: Long): LocalDate = dayOf(Instant.ofEpochMilli(epochMs))
    fun monthKey(day: LocalDate): String = YearMonth.from(day).toString()
    fun monthKey(instant: Instant): String = monthKey(dayOf(instant))
    fun year(instant: Instant): Int = dayOf(instant).year

    fun startOfDay(day: LocalDate): Instant = day.atStartOfDay(EAT).toInstant()
    fun startOfNextDay(day: LocalDate): Instant = day.plusDays(1).atStartOfDay(EAT).toInstant()

    companion object {
        val EAT: ZoneId = ZoneId.of("Africa/Nairobi")
    }
}
