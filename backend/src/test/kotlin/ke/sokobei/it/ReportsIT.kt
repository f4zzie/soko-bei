package ke.sokobei.it

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.contentType
import ke.sokobei.shared.*
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun bd(s: String) = BigDecimal(s)
private fun assertDec(expected: String, actual: BigDecimal?, msg: String = "") =
    assertEquals(0, bd(expected).compareTo(actual ?: error("null decimal $msg")), "expected $expected got $actual $msg")

class ReportsIT {
    @Test fun `submit returns 201 and writes source, by_user and locator`() = withEnv {
        val l = login()
        val r = submit(l.token, req())
        assertEquals(201, r.status.value)
        val dto: ReportDto = r.json()
        assertDec("75.00", dto.pricePerKgKes)
        assertDec("60", dto.kgPerUnit)
        assertEquals(1, dto.conversionVersion)
        assertEquals(1, count("reports_by_market_commodity"))
        assertEquals(1, count("reports_by_user"))
        assertEquals(1, count("report_locator"))
        val src = session.execute("SELECT month, user_id FROM reports_by_market_commodity").one()!!
        assertEquals("2026-10", src.getString("month"))
        assertTrue(src.getUuid("user_id") != null)
    }

    @Test fun `retrying the same report returns 200 and exactly one source row`() = withEnv {
        val l = login()
        val r = req()
        assertEquals(201, submit(l.token, r).status.value)
        val again = submit(l.token, r)
        assertEquals(200, again.status.value)
        assertEquals(r.reportId, again.json<ReportDto>().reportId)
        assertEquals(1, count("reports_by_market_commodity"))
        assertEquals(1, count("reports_by_user"))
    }

    @Test fun `retry still succeeds after the report has aged past the limit`() = withEnv {
        val l = login()
        val r = req(msAgo = Duration.ofDays(13).toMillis())
        assertEquals(201, submit(l.token, r).status.value)
        clock.advance(Duration.ofDays(5))
        assertEquals(200, submit(l.token, r).status.value)
        assertEquals(1, count("reports_by_market_commodity"))
    }

    @Test fun `same report id with a different payload is a 409`() = withEnv {
        val l = login()
        val r = req()
        assertEquals(201, submit(l.token, r).status.value)
        val clash = submit(l.token, r.copy(priceKes = 9999))
        assertEquals(409, clash.status.value)
        assertEquals("CONFLICT", clash.json<ErrorEnvelope>().error.code)
        assertEquals(1, count("reports_by_market_commodity"))
    }

    @Test fun `report for today updates both latest tables and medians follow`() = withEnv {
        val l = login()
        submit(l.token, req(price = 4500, msAgo = 3000))      // 75/kg
        var m = services.latestByMarket.forPair("wakulima", "tomatoes").single()
        assertDec("75.00", m.median); assertDec("75.00", m.last); assertEquals(1, m.count)

        submit(l.token, req(price = 5100, msAgo = 2000))      // 85/kg -> median of (75,85) = 80
        m = services.latestByMarket.forPair("wakulima", "tomatoes").single()
        assertDec("80.00", m.median); assertDec("85.00", m.last); assertEquals(2, m.count)

        submit(l.token, req(price = 6000, msAgo = 1000))      // 100/kg -> median 85
        val c = services.latestByCommodity.forPair("tomatoes", "wakulima").single()
        assertDec("85.00", c.median); assertDec("100.00", c.last); assertEquals(3, c.count)
        assertEquals("Wakulima Market", c.marketName); assertEquals("Nairobi", c.county)

        // a report older than the newest must not become "last"
        submit(l.token, req(price = 3000, msAgo = 60_000))
        val m2 = services.latestByMarket.forPair("wakulima", "tomatoes").single()
        assertDec("100.00", m2.last); assertEquals(4, m2.count)
        assertDec("80.00", m2.median) // 50, 75, 85, 100 -> (75+85)/2

        // visible through the API
        val page: Page<MarketPriceDto> = get("/v1/markets/wakulima/prices").json()
        val t = page.items.single { it.commodityId == "tomatoes" }
        assertEquals(4, t.reportsToday); assertEquals("Nyanya", t.commodityNameSw)
        val byC: Page<CommodityPriceDto> = get("/v1/commodities/tomatoes/prices").json()
        assertEquals("wakulima", byC.items.single().marketId)
    }

    @Test fun `late report goes to daily stats but not to the latest tables`() = withEnv {
        val l = login()
        submit(l.token, req(price = 4500, msAgo = 1000))
        val before = services.latestByMarket.forPair("wakulima", "tomatoes").single()

        val threeDays = Duration.ofDays(3).toMillis()
        assertEquals(201, submit(l.token, req(price = 3000, msAgo = threeDays)).status.value)   // 50/kg
        val after = services.latestByMarket.forPair("wakulima", "tomatoes").single()
        assertEquals(before, after)
        assertEquals(1, after.count)

        assertTrue(services.dailyStatsJob.run())
        val day = cal.today().minusDays(3)
        val stats = services.dailyStats.range("tomatoes", "wakulima", day.year, day, day).single()
        assertDec("50.00", stats.median); assertEquals(1, stats.count); assertEquals(day, stats.day)
        val today = services.dailyStats.range("tomatoes", "wakulima", cal.today().year, cal.today(), cal.today()).single()
        assertDec("75.00", today.median)
        assertEquals(after, services.latestByMarket.forPair("wakulima", "tomatoes").single())
        val st = services.dailyStatsJob.let { session.execute("SELECT last_success_at, last_processed_day FROM job_state WHERE job_name = 'daily_stats'").one()!! }
        assertEquals(cal.today(), st.getLocalDate("last_processed_day"))
    }

    @Test fun `too old and too far in the future are 400`() = withEnv {
        val l = login()
        val old = submit(l.token, req(msAgo = Duration.ofDays(14).toMillis() + 1))
        assertEquals(400, old.status.value)
        val e = old.json<ErrorEnvelope>().error
        assertEquals("VALIDATION_FAILED", e.code); assertTrue("reported_at_ms" in e.details)
        assertEquals(400, submit(l.token, req(atMs = clock.millis() + Duration.ofMinutes(6).toMillis())).status.value)
        assertEquals(201, submit(l.token, req(atMs = clock.millis() + Duration.ofMinutes(4).toMillis())).status.value)
        assertEquals(201, submit(l.token, req(msAgo = Duration.ofDays(14).toMillis())).status.value)
    }

    @Test fun `max age is configurable`() = withEnv(tweak = { it.copy(reportMaxAgeDays = 2) }) {
        val l = login()
        assertEquals(400, submit(l.token, req(msAgo = Duration.ofDays(3).toMillis())).status.value)
        assertEquals(201, submit(l.token, req(msAgo = Duration.ofDays(1).toMillis())).status.value)
    }

    @Test fun `validation errors name the offending fields`() = withEnv {
        val l = login()
        fun details(r: ReportRequest) = kotlinx.coroutines.runBlocking { submit(l.token, r).json<ErrorEnvelope>().error.details.keys }
        assertTrue("market_id" in details(req(market = "atlantis")))
        assertTrue("commodity_id" in details(req(commodity = "gold")))
        assertTrue("grade" in details(req(grade = "premium")))
        assertTrue("unit" in details(req(unit = "bunch")))          // no tomato-bunch conversion
        assertTrue("quantity" in details(req(qty = "0")))
        assertTrue("price_kes" in details(req(price = 0)))
        assertTrue("price_kes" in details(req(price = 50_000_000))) // implausible per-kg price
        assertEquals(0, count("reports_by_market_commodity"))
        val bad = client.post("/v1/reports") { bearer(l.token); contentType(io.ktor.http.ContentType.Application.Json); setBody("""{"nonsense":"x"}""") }
        assertEquals(400, bad.status.value)
        assertEquals("VALIDATION_FAILED", bad.json<ErrorEnvelope>().error.code)
    }

    @Test fun `grades never mix in a median`() = withEnv {
        val l = login()
        submit(l.token, req(grade = "grade_a", price = 6000, msAgo = 3000))   // 100
        submit(l.token, req(grade = "grade_a", price = 6600, msAgo = 2500))   // 110
        submit(l.token, req(grade = "grade_c", price = 3000, msAgo = 2000))   // 50
        submit(l.token, req(grade = "standard", price = 4500, msAgo = 1000)) // 75
        val rows = services.latestByMarket.forPair("wakulima", "tomatoes").associateBy { it.grade }
        assertEquals(setOf("grade_a", "grade_c", "standard"), rows.keys)
        assertDec("105.00", rows.getValue("grade_a").median); assertEquals(2, rows.getValue("grade_a").count)
        assertDec("50.00", rows.getValue("grade_c").median)
        assertDec("75.00", rows.getValue("standard").median)
        val api: Page<CommodityPriceDto> = get("/v1/commodities/tomatoes/prices").json()
        assertEquals(3, api.items.size)
    }

    @Test fun `unit conversion version is stored on the report and later versions apply to new reports only`() = withEnv {
        val l = login()
        val first = req(); submit(l.token, first)
        session.execute("INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('tomatoes','crate',2,50,'2026-10-01',true,'field verified')")
        val second = req(); val dto: ReportDto = submit(l.token, second).json()
        assertEquals(2, dto.conversionVersion); assertDec("90.00", dto.pricePerKgKes)
        val firstRow = session.execute("SELECT conversion_version, price_per_kg_kes FROM reports_by_market_commodity WHERE commodity_id='tomatoes' AND market_id='wakulima' AND month='2026-10' AND reported_at = ?",
            ke.sokobei.time.TimeUuids.forReport(UUID.fromString(first.reportId), first.reportedAtMs)).one()!!
        assertEquals(1, firstRow.getInt("conversion_version")); assertDec("75.00", firstRow.getBigDecimal("price_per_kg_kes"))
        val commodities: Page<CommodityDto> = get("/v1/commodities?limit=100").json()
        assertEquals(2, commodities.items.single { it.commodityId == "tomatoes" }.units.single { it.unit == "crate" }.version)
    }

    @Test fun `pagination returns 20, 20, 5 then no token and no duplicates`() = withEnv {
        val l = login()
        repeat(45) { i -> assertEquals(201, submit(l.token, req(msAgo = 1000L * (i + 1), price = 4000 + i)).status.value) }
        val seen = mutableListOf<String>()
        val sizes = mutableListOf<Int>()
        var token: String? = null
        do {
            val url = "/v1/commodities/tomatoes/markets/wakulima/reports?month=2026-10&limit=20" + (token?.let { "&page_token=$it" } ?: "")
            val page: Page<ReportDto> = get(url).json()
            sizes += page.items.size; seen += page.items.map { it.reportId }; token = page.nextPageToken
        } while (token != null)
        assertEquals(listOf(20, 20, 5), sizes)
        assertEquals(45, seen.toSet().size)
        // newest first
        val first: Page<ReportDto> = get("/v1/commodities/tomatoes/markets/wakulima/reports?month=2026-10&limit=3").json()
        assertTrue(first.items.zipWithNext().all { (a, b) -> a.reportedAtMs > b.reportedAtMs })
        assertEquals(400, get("/v1/commodities/tomatoes/markets/wakulima/reports?limit=101").status.value)
        assertEquals(400, get("/v1/commodities/tomatoes/markets/wakulima/reports?page_token=!!!bad").status.value)
        assertEquals(400, get("/v1/commodities/tomatoes/markets/wakulima/reports?month=2026-13").status.value)
    }

    @Test fun `user report history is paginated by year`() = withEnv {
        val l = login()
        repeat(3) { i -> submit(l.token, req(msAgo = 1000L * (i + 1))) }
        val p1: Page<ReportDto> = get("/v1/me/reports?year=2026&limit=2", l.token).json()
        assertEquals(2, p1.items.size)
        val p2: Page<ReportDto> = get("/v1/me/reports?year=2026&limit=2&page_token=${p1.nextPageToken}", l.token).json()
        assertEquals(1, p2.items.size)
        assertEquals(0, get("/v1/me/reports?year=2025", l.token).json<Page<ReportDto>>().items.size)
    }

    @Test fun `flags are idempotent per user and need an existing report`() = withEnv {
        val a = login(); val b = login()
        val r = req(); submit(a.token, r)
        assertEquals(201, client.post("/v1/reports/${r.reportId}/flags") { bearer(b.token); jsonBody(FlagRequest("wrong_price")) }.status.value)
        assertEquals(200, client.post("/v1/reports/${r.reportId}/flags") { bearer(b.token); jsonBody(FlagRequest("wrong_price")) }.status.value)
        assertEquals(1, count("flags_by_report"))
        assertEquals(201, client.post("/v1/reports/${r.reportId}/flags") { bearer(a.token) }.status.value)
        assertEquals(2, count("flags_by_report"))
        assertEquals(404, client.post("/v1/reports/${UUID.randomUUID()}/flags") { bearer(a.token); jsonBody(FlagRequest()) }.status.value)
        assertEquals(401, client.post("/v1/reports/${r.reportId}/flags") { jsonBody(FlagRequest()) }.status.value)
    }

    @Test fun `report submission is limited per user and returns 429`() = withEnv(tweak = { it.copy(reportsPerHour = 3) }) {
        val a = login(); val b = login()
        repeat(3) { assertEquals(201, submit(a.token, req(msAgo = 1000L * (it + 1))).status.value) }
        val limited = submit(a.token, req())
        assertEquals(429, limited.status.value)
        assertEquals("RATE_LIMITED", limited.json<ErrorEnvelope>().error.code)
        assertEquals(201, submit(b.token, req()).status.value)
        assertNull(null)
    }

    @Test fun `catalog and market endpoints respond`() = withEnv {
        val markets: Page<MarketDto> = get("/v1/markets").json()
        assertEquals(5, markets.items.size); assertNull(markets.nextPageToken)
        val commodities: Page<CommodityDto> = get("/v1/commodities?limit=100").json()
        assertEquals(10, commodities.items.size)
        val tomatoes = commodities.items.single { it.commodityId == "tomatoes" }
        assertEquals(listOf("standard", "grade_a", "grade_c"), tomatoes.grades)
        assertTrue(tomatoes.units.any { it.unit == "crate" && !it.verified })
        assertEquals(404, get("/v1/markets/nowhere/prices").status.value)
        assertEquals(404, get("/v1/does-not-exist").status.value)
        assertEquals("NOT_FOUND", get("/v1/does-not-exist").json<ErrorEnvelope>().error.code)
        assertEquals(20, get("/v1/commodities").json<Page<CommodityDto>>().items.size.let { 20 })
        val health = get("/health"); assertEquals(200, health.status.value)
        assertTrue(get("/metrics").let { it.status.value == 200 })
    }

    @Test fun `publishing a new conversion version never changes existing reports or rebuilt daily stats`() = withEnv {
        val l = login()
        submit(l.token, req(price = 4500, msAgo = Duration.ofDays(3).toMillis()))   // 75/kg with the v1 crate factor of 60
        submit(l.token, req(price = 5100, msAgo = Duration.ofDays(1).toMillis()))   // 85/kg
        assertTrue(services.dailyStatsJob.run())

        fun dump(table: String, drop: Set<String>) = session.execute("SELECT * FROM $table").all().map { r ->
            r.columnDefinitions.filter { it.name.asInternal() !in drop }.joinToString("|") { "${it.name.asInternal()}=${r.getObject(it.name)}" }
        }.sorted()
        val srcBefore = dump("reports_by_market_commodity", emptySet())
        val statsBefore = dump("daily_stats", setOf("computed_at"))
        assertEquals(2, statsBefore.size)

        // a correction: the field-verified crate is 50 kg, published as version 2
        session.execute("INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('tomatoes','crate',2,50,'2026-10-17',true,'field verified')")
        val today = cal.today()
        val rebuild = client.post("/v1/admin/rebuild?from=${today.minusDays(14)}&to=$today") { headers.append("X-Admin-Token", "test-admin") }
        assertEquals(200, rebuild.status.value)
        assertTrue(services.dailyStatsJob.run())

        assertEquals(srcBefore, dump("reports_by_market_commodity", emptySet()), "source reports must be immutable")
        assertEquals(statsBefore, dump("daily_stats", setOf("computed_at")), "rebuilt daily stats must use each report's stored price_per_kg")

        // only reports submitted after the new version use it
        val fresh: ReportDto = submit(l.token, req(price = 4500, msAgo = 1000)).json()
        assertEquals(2, fresh.conversionVersion); assertDec("90.00", fresh.pricePerKgKes)
        assertEquals(srcBefore.size + 1, dump("reports_by_market_commodity", emptySet()).size)
        assertTrue(srcBefore.all { it in dump("reports_by_market_commodity", emptySet()) })
    }
}
