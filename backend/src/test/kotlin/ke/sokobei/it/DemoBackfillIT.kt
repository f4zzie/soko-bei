package ke.sokobei.it

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import ke.sokobei.Services
import ke.sokobei.demo.DemoBackfill
import ke.sokobei.demo.DemoCatalog
import ke.sokobei.demo.DemoScenario
import ke.sokobei.demo.DemoUsers
import ke.sokobei.repository.Db
import ke.sokobei.repository.ReportLocatorRepository
import ke.sokobei.shared.ReportDto
import ke.sokobei.time.TimeUuids
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DemoBackfillIT {
    @Test fun `additive three day backfill matches API rows and statistics and is exactly idempotent`() = withEnv {
        val originalUser = login()
        assertEquals(201, submit(originalUser.token, req()).status.value)
        fun dump(table: String, excluded: Set<String> = emptySet()) = session.execute("SELECT * FROM $table").all().map { row ->
            row.columnDefinitions.filter { it.name.asInternal() !in excluded }.joinToString("|") { "${it.name.asInternal()}=${row.getObject(it.name)}" }
        }.sorted()
        val existingCatalog = listOf("markets", "commodities", "commodity_units").associateWith { dump(it) }
        assertEquals(43, DemoCatalog.apply(services))
        assertEquals(15, count("markets"))
        assertEquals(18, count("commodities"))
        assertEquals(61, count("commodity_units"))
        val extendedCatalog = existingCatalog.keys.associateWith { dump(it) }
        assertEquals(0, DemoCatalog.apply(services))
        assertEquals(extendedCatalog, existingCatalog.keys.associateWith { dump(it) })
        existingCatalog.forEach { (table, before) -> assertTrue(extendedCatalog.getValue(table).containsAll(before)) }
        for (commodity in DemoCatalog.commodities) assertTrue(services.units.latestForCommodity(commodity.commodity.commodityId).all { !it.verified })

        val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        try {
            val backfillServices = Services(config.copy(reportMaxAgeDays = 100), Db(session), mongo, cal, sms, metrics,
                deferReportRecompute = true, preserveDerivedRows = true)
            val from = cal.today().minusDays(21)
            val through = from.plusDays(2)
            val markets = setOf("wakulima", "karatina")
            val scenario = DemoScenario.load(backfillServices, 42)
            val events = (0L..2).flatMap { scenario.day(from.plusDays(it)) }.filter { it.report.marketId in markets }
            val event = events.first { it.report.commodityId == "tomatoes" && it.report.unit == "crate" && !it.outlier }
            DemoUsers.ensure(backfillServices)
            val apiUser = login(DemoScenario.PHONES[event.userIndex])
            assertEquals(400, submit(apiUser.token, event.report).status.value, "the HTTP API must keep its 14-day age limit")
            val apiRequest = event.report.copy(reportId = UUID.randomUUID().toString(), reportedAtMs = cal.now().minus(Duration.ofDays(2)).toEpochMilli())
            val apiResponse = submit(apiUser.token, apiRequest)
            assertEquals(201, apiResponse.status.value)
            val apiDto = apiResponse.json<ReportDto>()
            val sourceTables = listOf("reports_by_market_commodity", "reports_by_user", "report_locator")
            val before = sourceTables.associateWith { dump(it) }

            val summary = DemoBackfill(backfillServices) {}.load(42, from, through, markets, concurrency = 2, batchSize = 32)
            assertEquals(events.size, summary.created)
            assertEquals(0, summary.existing)
            assertEquals(markets, summary.byMarket.keys)
            sourceTables.forEach { assertEquals(events.size + 2L, count(it)) }
            before.forEach { (table, rows) -> assertTrue(dump(table).containsAll(rows), "preserve $table") }
            assertEquals(41, mcount("users"))
            assertEquals(40, mcount("demo_large_users"))
            assertTrue(sms.sent.isEmpty())
            assertTrue(mdocs("users").all { it.getInteger("consent_version") == config.consentVersion })

            val locator = ReportLocatorRepository(Db(session))
            val stored = events.map { e ->
                val id = UUID.fromString(e.report.reportId)
                val loc = locator.get(id)!!
                val row = services.reports.get(loc.commodityId, loc.marketId, loc.month, loc.reportedAt)!!
                assertEquals(TimeUuids.forReport(id, e.report.reportedAtMs), row.reportedAt)
                assertEquals(cal.monthKey(java.time.Instant.ofEpochMilli(e.report.reportedAtMs)), row.month)
                assertEquals(e.report.priceKes, row.priceKes)
                assertEquals(e.report.quantity, row.quantity)
                assertEquals(1, row.conversionVersion)
                assertEquals(row.priceKes.toBigDecimal().divide(row.quantity.multiply(row.kgPerUnit), 2, RoundingMode.HALF_UP), row.pricePerKgKes)
                val index = services.byUser.all(row.userId!!, from.year).single { it.getUuid("report_id") == id }
                assertEquals(row.reportedAt, index.getUuid("reported_at"))
                assertEquals(row.priceKes, index.getInt("price_kes"))
                row
            }
            val twin = stored.single { it.reportId.toString() == event.report.reportId }
            assertEquals(apiDto.pricePerKgKes, twin.pricePerKgKes)
            assertEquals(apiDto.kgPerUnit, twin.kgPerUnit)
            assertEquals(apiDto.conversionVersion, twin.conversionVersion)
            assertEquals(apiDto.grade, twin.grade)
            assertEquals(apiDto.unit, twin.unit)
            assertEquals(apiDto.quantity, twin.quantity)
            val groups = stored.groupBy { Triple(it.commodityId, it.marketId, cal.dayOf(java.time.Instant.ofEpochMilli(TimeUuids.timestampMs(it.reportedAt)))) }
            for ((key, rows) in groups) {
                val (commodity, market, day) = key
                val stats = services.dailyStats.range(commodity, market, day.year, day, day).associateBy { it.grade }
                assertEquals(rows.map { it.grade }.toSet(), stats.keys)
                for ((grade, gradeRows) in rows.groupBy { it.grade }) {
                    val prices = gradeRows.map { it.pricePerKgKes }.sorted()
                    val median = if (prices.size % 2 == 1) prices[prices.size / 2] else
                        (prices[prices.size / 2 - 1] + prices[prices.size / 2]).divide(BigDecimal(2), 2, RoundingMode.HALF_UP)
                    val stat = stats.getValue(grade)
                    assertEquals(prices.size, stat.count)
                    assertEquals(prices.first(), stat.min)
                    assertEquals(prices.last(), stat.max)
                    assertEquals(0, median.compareTo(stat.median))
                }
            }
            val loaded = sourceTables.associateWith { dump(it) }
            val stats = dump("daily_stats", setOf("computed_at"))
            val flags = dump("flags_by_report")
            val accounts = mdocs("users")
            clock.advance(Duration.ofMinutes(5))
            val replay = DemoBackfill(backfillServices) {}.load(42, from, through, markets, concurrency = 1)
            assertEquals(0, replay.created)
            assertEquals(events.size, replay.existing)
            assertEquals(0, replay.flagged)
            assertEquals(loaded, sourceTables.associateWith { dump(it) })
            assertEquals(stats, dump("daily_stats", setOf("computed_at")))
            assertEquals(flags, dump("flags_by_report"))
            assertEquals(accounts, mdocs("users"))
            assertFailsWith<IllegalArgumentException> { DemoBackfill(backfillServices) {}.load(42, cal.today(), cal.today()) }
        } finally {
            metrics.close()
        }
    }

    @Test fun `demo account pool refuses an existing unmarked account without adopting or changing it`() = withEnv {
        login(DemoScenario.PHONES.first())
        val before = mdocs("users")
        assertFailsWith<IllegalStateException> { DemoUsers.ensure(services) }
        assertEquals(before, mdocs("users"))
        assertEquals(0, mcount("demo_large_users"))
        assertEquals(0, count("report_locator"))
        assertFailsWith<IllegalArgumentException> {
            Services(config.copy(devMode = false), Db(session), mongo, cal, sms, services.metrics, deferReportRecompute = true)
        }
    }
}
