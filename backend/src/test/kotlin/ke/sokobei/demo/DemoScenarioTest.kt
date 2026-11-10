package ke.sokobei.demo

import ke.sokobei.config.AppConfig
import ke.sokobei.domain.Commodity
import ke.sokobei.domain.Market
import ke.sokobei.domain.ReportValidator
import ke.sokobei.domain.UnitFactor
import ke.sokobei.time.BusinessCalendar
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DemoScenarioTest {
    private val markets = (listOf("wakulima", "marikiti", "kongowea", "kibuye", "nakuru-main").map {
        Market(it, it, "Demo", null, null)
    } + DemoCatalog.markets).sortedBy { it.marketId }
    private val commodities = DemoScenario.BASES.keys.sorted().map {
        DemoCatalog.commodities.find { demo -> demo.commodity.commodityId == it }?.commodity
            ?: Commodity(it, it, it, "kg", listOf("standard", "grade_a", "grade_c"))
    }
    private val factors = commodities.associate { c -> c.commodityId to (
        DemoCatalog.commodities.find { it.commodity.commodityId == c.commodityId }?.factors
            ?: mapOf("kg" to BigDecimal.ONE, "debe" to BigDecimal(18), "bag_50kg" to BigDecimal(50))
        ).map { (unit, kg) -> UnitFactor(c.commodityId, unit, 1, kg, false) } }
    private fun scenario(seed: Long = 42) = DemoScenario(seed, markets, commodities, factors)

    @Test fun `every CLI action refuses production before connecting to a database`() {
        for (action in listOf("demo-catalog", "demo-backfill", "demo-plan", "demo-jobs")) {
            val error = assertFailsWith<IllegalArgumentException> { DemoCli.run(arrayOf(action), AppConfig(devMode = false)) }
            assertTrue(error.message!!.contains("DEV_MODE=true"))
        }
    }

    @Test fun `90 days have bounded realistic volume valid mixed reports and deterministic identities`() {
        val from = LocalDate.of(2026, 8, 7)
        val generator = scenario()
        val days = (0L..89).associate { from.plusDays(it) to generator.day(from.plusDays(it)) }
        val reports = days.values.flatten()
        assertTrue(reports.size in 60_000..100_000, "volume=${reports.size}")
        assertEquals(reports.size, reports.map { it.report.reportId }.toSet().size)
        assertEquals(days.getValue(from), scenario().day(from))
        assertNotEquals(days.getValue(from), scenario(43).day(from))
        assertTrue(reports.count { it.outlier }.toDouble() / reports.size in .007.. .013)
        assertEquals(setOf("standard", "grade_a", "grade_c"), reports.map { it.report.grade }.toSet())
        assertTrue(reports.map { it.report.unit }.toSet().containsAll(setOf("kg", "crate", "debe", "gorogoro", "bag_50kg", "piece", "bunch")))
        val volume = reports.groupingBy { it.report.marketId }.eachCount()
        assertTrue(volume.getValue("wakulima") > volume.getValue("karatina") * 4)
        assertTrue(days.any { (_, events) -> events.none { it.report.marketId == "karatina" } })
        assertTrue(days.values.map { it.size }.distinct().size > 50)
        val validator = ReportValidator(100)
        val now = from.plusDays(90).atStartOfDay(BusinessCalendar.EAT).toInstant()
        for ((day, events) in days) for (event in events) {
            val r = event.report
            assertEquals(day, java.time.Instant.ofEpochMilli(r.reportedAtMs).atZone(BusinessCalendar.EAT).toLocalDate())
            assertTrue(event.userIndex in 0 until 40)
            val valid = validator.validate(r, markets.single { it.marketId == r.marketId },
                commodities.single { it.commodityId == r.commodityId }, factors.getValue(r.commodityId).single { it.unit == r.unit }, now)
            assertTrue(valid.pricePerKgKes >= BigDecimal.ONE)
        }
    }

    @Test fun `opening board covers every pair and leaves hourly quota for live trickle`() {
        val day = LocalDate.of(2026, 11, 4)
        val s = scenario()
        val opening = s.opening(day)
        assertEquals(270, opening.size)
        assertEquals(270, opening.map { it.report.marketId to it.report.commodityId }.toSet().size)
        assertTrue(opening.all { it.report.grade == "standard" && !it.outlier })
        val today = (opening + s.day(day)).sortedBy { it.report.reportedAtMs }.take(1000)
        assertTrue(today.groupingBy { it.userIndex }.eachCount().values.all { it <= 25 })
        assertTrue(DemoCatalog.commodities.flatMap { it.factors.values }.all { it > BigDecimal.ZERO })
        assertFalse(opening.map { it.report.reportId }.toSet().intersect(s.day(day).map { it.report.reportId }.toSet()).isNotEmpty())
    }
}
