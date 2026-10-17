package ke.sokobei.it

import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import ke.sokobei.jobs.JobScheduler
import ke.sokobei.shared.*
import ke.sokobei.time.TimeUuids
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobsIT {
    private fun assertDec(expected: String, actual: BigDecimal?) =
        assertEquals(0, BigDecimal(expected).compareTo(actual ?: error("null")), "expected $expected got $actual")

    @Test fun `reconciliation fixes a corrupted latest row and removes rows with no reports today`() = withEnv {
        val l = login()
        submit(l.token, req(price = 4500, msAgo = 2000)); submit(l.token, req(price = 5100, msAgo = 1000))
        session.execute("UPDATE latest_price_by_market SET median_today_per_kg = 999, reports_today = 42 WHERE market_id='wakulima' AND commodity_id='tomatoes' AND grade='standard'")
        session.execute("UPDATE latest_price_by_commodity SET median_today_per_kg = 1, last_price_per_kg = 1 WHERE commodity_id='tomatoes' AND market_id='wakulima' AND grade='standard'")
        // a stale row for a pair that has no reports today
        session.execute("INSERT INTO latest_price_by_market (market_id, commodity_id, grade, median_today_per_kg, reports_today) VALUES ('kongowea','maize','standard',30,5)")
        session.execute("INSERT INTO latest_price_by_commodity (commodity_id, market_id, grade, median_today_per_kg, reports_today) VALUES ('maize','kongowea','standard',30,5)")

        assertTrue(services.reconciliationJob.run())
        val m = services.latestByMarket.forPair("wakulima", "tomatoes").single()
        assertDec("80.00", m.median); assertDec("85.00", m.last); assertEquals(2, m.count)
        val c = services.latestByCommodity.forPair("tomatoes", "wakulima").single()
        assertDec("80.00", c.median); assertDec("85.00", c.last); assertEquals(2, c.count)
        assertTrue(services.latestByMarket.forPair("kongowea", "maize").isEmpty())
        assertTrue(services.latestByCommodity.forPair("maize", "kongowea").isEmpty())
    }

    @Test fun `after midnight EAT yesterday's latest values disappear`() = withEnv {
        val l = login()
        submit(l.token, req(price = 4500))
        assertEquals(1, services.latestByMarket.forPair("wakulima", "tomatoes").size)
        clock.set(Instant.parse("2026-10-17T21:00:30Z")) // 00:00:30 EAT on the 18th
        assertTrue(services.reconciliationJob.run())
        assertTrue(services.latestByMarket.forPair("wakulima", "tomatoes").isEmpty())
        assertTrue(services.latestByCommodity.forPair("tomatoes", "wakulima").isEmpty())
        // the raw report is untouched
        assertEquals(1, count("reports_by_market_commodity"))
    }

    @Test fun `eat day boundaries slice correctly with minTimeuuid`() = withEnv {
        val l = login()
        val endOfDay = Instant.parse("2026-10-17T20:59:59.999Z").toEpochMilli()   // 23:59:59.999 EAT on 17th
        val startOfNext = Instant.parse("2026-10-17T21:00:00Z").toEpochMilli()     // 00:00:00.000 EAT on 18th
        clock.set(Instant.parse("2026-10-18T09:00:00Z"))
        repeat(20) { i ->  // many ids so every sub-millisecond offset path is exercised
            assertEquals(201, submit(l.token, req(atMs = endOfDay, price = 4500 + i, id = UUID.randomUUID())).status.value)
        }
        assertEquals(201, submit(l.token, req(atMs = startOfNext, price = 9000)).status.value)
        val d17 = services.recompute.readDay("tomatoes", "wakulima", java.time.LocalDate.parse("2026-10-17"))
        val d18 = services.recompute.readDay("tomatoes", "wakulima", java.time.LocalDate.parse("2026-10-18"))
        assertEquals(20, d17.size); assertEquals(1, d18.size)
        assertEquals(startOfNext, TimeUuids.timestampMs(d18.single().reportedAt))
        // today's latest only counts the report from the 18th
        assertEquals(1, services.latestByMarket.forPair("wakulima", "tomatoes").single().count)
    }

    @Test fun `trend stitches year partitions in order and respects EAT days`() = withEnv(clockStart = Instant.parse("2027-01-05T09:00:00Z")) {
        val l = login()
        submit(l.token, req(atMs = Instant.parse("2026-12-31T09:00:00Z").toEpochMilli(), price = 3000))   // 31 Dec EAT
        submit(l.token, req(atMs = Instant.parse("2026-12-31T21:30:00Z").toEpochMilli(), price = 4500))   // 1 Jan EAT (00:30)
        submit(l.token, req(atMs = Instant.parse("2027-01-02T09:00:00Z").toEpochMilli(), price = 6000))
        submit(l.token, req(atMs = Instant.parse("2027-01-02T10:00:00Z").toEpochMilli(), price = 7200))
        assertTrue(services.dailyStatsJob.run())

        assertEquals(1, count("daily_stats", "WHERE commodity_id='tomatoes' AND market_id='wakulima' AND year=2026"))
        assertEquals(2, count("daily_stats", "WHERE commodity_id='tomatoes' AND market_id='wakulima' AND year=2027"))

        val t: TrendResponse = get("/v1/commodities/tomatoes/markets/wakulima/trend?days=10&grade=standard").json()
        assertEquals(listOf("2026-12-31", "2027-01-01", "2027-01-02"), t.points.map { it.day })
        assertDec("50.00", t.points[0].medianPerKg); assertDec("75.00", t.points[1].medianPerKg)
        assertDec("110.00", t.points[2].medianPerKg)               // (100 + 120) / 2
        assertDec("100.00", t.points[2].minPerKg); assertDec("120.00", t.points[2].maxPerKg)
        assertEquals(2, t.points[2].reportCount)
        assertEquals(listOf("2027-01-01", "2027-01-02"), get("/v1/commodities/tomatoes/markets/wakulima/trend?days=5").json<TrendResponse>().points.map { it.day })
        assertEquals(400, get("/v1/commodities/tomatoes/markets/wakulima/trend?days=366").status.value)
        assertEquals(400, get("/v1/commodities/tomatoes/markets/wakulima/trend?days=0").status.value)
        assertEquals(0, get("/v1/commodities/tomatoes/markets/wakulima/trend?days=30&grade=grade_a").json<TrendResponse>().points.size)
    }

    @Test fun `daily stats job covers a month boundary within the late-report window`() = withEnv(clockStart = Instant.parse("2026-11-03T09:00:00Z")) {
        val l = login()
        submit(l.token, req(atMs = Instant.parse("2026-10-30T09:00:00Z").toEpochMilli(), price = 3000))
        submit(l.token, req(atMs = Instant.parse("2026-11-02T09:00:00Z").toEpochMilli(), price = 3600))
        assertTrue(services.dailyStatsJob.run())
        val days = session.execute("SELECT day FROM daily_stats").all().map { it.getLocalDate("day")!!.toString() }.sorted()
        assertEquals(listOf("2026-10-30", "2026-11-02"), days)
    }

    @Test fun `daily stats job is idempotent and a failure is recorded`() = withEnv {
        val l = login()
        submit(l.token, req(msAgo = Duration.ofDays(2).toMillis()))
        assertTrue(services.dailyStatsJob.run()); assertTrue(services.dailyStatsJob.run())
        assertEquals(1, count("daily_stats"))
        val s = session.execute("SELECT last_success_at, last_error FROM job_state WHERE job_name='daily_stats'").one()!!
        assertEquals(clock.instant(), s.getInstant("last_success_at")); assertEquals(null, s.getString("last_error"))
        // metrics expose the job
        services.jobRunner.register("daily_stats")
        val metrics = get("/metrics").let { kotlinx.coroutines.runBlocking { it.bodyAsText() } }
        assertTrue(metrics.contains("soko_job_last_success_timestamp_seconds"), metrics.take(200))
        assertTrue(metrics.contains("soko_job_lag_seconds"))
    }

    @Test fun `admin rebuild recreates truncated derived tables identically`() = withEnv {
        val l = login()
        submit(l.token, req(price = 4500, msAgo = 3000)); submit(l.token, req(price = 5100, msAgo = 2000))
        submit(l.token, req(price = 6000, grade = "grade_a", msAgo = 1000))
        submit(l.token, req(commodity = "maize", unit = "debe", price = 1800, market = "marikiti", msAgo = 500))
        submit(l.token, req(price = 3000, msAgo = Duration.ofDays(3).toMillis()))
        submit(l.token, req(price = 3300, market = "kibuye", msAgo = Duration.ofDays(9).toMillis()))
        assertTrue(services.dailyStatsJob.run())

        val derived = listOf("latest_price_by_commodity", "latest_price_by_market", "daily_stats")
        fun dump(t: String) = session.execute("SELECT * FROM $t").all().map { r ->
            r.columnDefinitions.filter { it.name.asInternal() !in setOf("updated_at", "computed_at") }
                .joinToString("|") { "${it.name.asInternal()}=${r.getObject(it.name)}" }
        }.sorted()
        val before = derived.associateWith(::dump)
        assertTrue(before.values.all { it.isNotEmpty() })

        derived.forEach { session.execute("TRUNCATE $it") }
        assertTrue(derived.all { count(it) == 0L })

        val today = cal.today()
        assertEquals(403, client.post("/v1/admin/rebuild?from=${today.minusDays(14)}&to=$today").status.value)
        assertEquals(403, client.post("/v1/admin/rebuild?from=${today.minusDays(14)}&to=$today") { headers.append("X-Admin-Token", "wrong") }.status.value)
        assertEquals(400, client.post("/v1/admin/rebuild?from=nope&to=$today") { headers.append("X-Admin-Token", "test-admin") }.status.value)
        val ok = client.post("/v1/admin/rebuild?from=${today.minusDays(14)}&to=$today") { headers.append("X-Admin-Token", "test-admin") }
        assertEquals(200, ok.status.value)
        assertEquals(15, ok.json<RebuildResult>().days)

        derived.forEach { assertEquals(before.getValue(it), dump(it), "table $it differs after rebuild") }
    }

    @Test fun `scheduler computes the next 0015 EAT run`() {
        // 21:00Z = 00:00 EAT -> 15 minutes
        assertEquals(Duration.ofMinutes(15).toMillis(), JobScheduler.millisUntilNextRun(Instant.parse("2026-10-17T21:00:00Z")))
        // just after 00:15 EAT -> almost a day
        assertEquals(Duration.ofHours(24).minusSeconds(1).toMillis(), JobScheduler.millisUntilNextRun(Instant.parse("2026-10-17T21:15:01Z")))
        // exactly 00:15 -> next day
        assertEquals(Duration.ofHours(24).toMillis(), JobScheduler.millisUntilNextRun(Instant.parse("2026-10-17T21:15:00Z")))
        assertFalse(JobScheduler.millisUntilNextRun(Instant.parse("2026-12-31T20:00:00Z")) <= 0)
    }
}
