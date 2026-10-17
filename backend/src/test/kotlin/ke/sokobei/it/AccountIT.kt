package ke.sokobei.it

import io.ktor.client.request.post
import ke.sokobei.shared.*
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountIT {
    @Test fun `account deletion removes the user but keeps anonymous prices`() = withEnv {
        val me = login("0712345678")
        val other = login("0722222222")
        val r1 = req(price = 4500, msAgo = 1000)
        val r2 = req(price = 3000, msAgo = Duration.ofDays(3).toMillis())
        val r3 = req(price = 5100, msAgo = 500)
        submit(me.token, r1); submit(me.token, r2); submit(other.token, r3)
        client.post("/v1/reports/${r3.reportId}/flags") { bearer(me.token); jsonBody(FlagRequest()) }
        val meId = get("/v1/me", me.token).json<UserDto>().userId
        assertEquals(3, count("reports_by_market_commodity"))

        assertEquals(204, del("/v1/me", me.token).status.value)

        assertEquals(1, mcount("users"))
        assertTrue(mdocs("users").none { it.getString("phone") == "+254712345678" })
        assertEquals(0, count("reports_by_user", "WHERE user_id = ${meId} AND year = 2026"))
        assertEquals(1, count("reports_by_user"))                           // the other user's report remains
        assertTrue(mdocs("refresh_tokens").none { it.getString("user_id") == meId })
        assertEquals(1, mcount("refresh_tokens"))                           // only the other user's token
        assertEquals(0, count("flags_by_report")); assertEquals(0, count("flags_by_user"))

        // prices survive, anonymised
        val rows = session.execute("SELECT report_id, user_id, price_kes, price_per_kg_kes, grade FROM reports_by_market_commodity").all()
        assertEquals(3, rows.size)
        val mine = rows.filter { it.getUuid("report_id").toString() in setOf(r1.reportId, r2.reportId) }
        assertEquals(2, mine.size)
        mine.forEach { assertNull(it.getUuid("user_id")) }
        assertEquals(setOf(4500, 3000), mine.map { it.getInt("price_kes") }.toSet())
        assertEquals(0, BigDecimal("75").compareTo(mine.single { it.getInt("price_kes") == 4500 }.getBigDecimal("price_per_kg_kes")))
        assertTrue(rows.single { it.getUuid("report_id").toString() == r3.reportId }.getUuid("user_id") != null)
        val loc = session.execute("SELECT report_id, user_id FROM report_locator").all().associate { it.getUuid("report_id").toString() to it.getUuid("user_id") }
        assertNull(loc[r1.reportId]); assertNull(loc[r2.reportId]); assertTrue(loc[r3.reportId] != null)

        // derived tables still include the anonymous prices
        assertEquals(2, services.latestByMarket.forPair("wakulima", "tomatoes").single().count)

        // old credentials are dead
        assertEquals(401, get("/v1/me", me.token).status.value)
        assertEquals(401, submit(me.token, req()).status.value)
        assertEquals(401, client.post("/v1/auth/refresh") { jsonBody(RefreshRequest(me.tokens.refreshToken)) }.status.value)

        // the same phone can sign up again as a brand new user with no history
        val again = login("0712345678")
        assertNotEquals(meId, get("/v1/me", again.token).json<UserDto>().userId)
        assertEquals(0, get("/v1/me/reports?year=2026", again.token).json<Page<ReportDto>>().items.size)
    }

    @Test fun `deleting an account whose reports span a year boundary still clears them`() =
        withEnv(clockStart = java.time.Instant.parse("2027-01-03T09:00:00Z")) {
            val me = login()
            submit(me.token, req(atMs = java.time.Instant.parse("2026-12-30T09:00:00Z").toEpochMilli()))
            submit(me.token, req(atMs = java.time.Instant.parse("2027-01-02T09:00:00Z").toEpochMilli()))
            assertEquals(2, count("reports_by_user"))
            assertEquals(204, del("/v1/me", me.token).status.value)
            assertEquals(0, count("reports_by_user"))
            assertEquals(2, session.execute("SELECT user_id FROM reports_by_market_commodity").all().count { it.getUuid("user_id") == null })
        }

    @Test fun `phone numbers are never written to logs in full`() = withEnv(devMode = false) {
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        val root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        root.addAppender(appender)
        try {
            client.post("/v1/auth/otp/request") { jsonBody(OtpRequest("0712345678")) }
            client.post("/v1/auth/otp/verify") { jsonBody(OtpVerifyRequest("0712345678", sms.lastCode())) }
        } finally { root.detachAppender(appender) }
        val text = appender.list.joinToString("\n") { it.formattedMessage }
        assertTrue(!text.contains("712345678"), text)
        assertTrue(!text.contains(sms.lastCode()), "otp code leaked to logs")
        assertTrue(text.contains("678"))
    }

    @Test fun `deletion survives a crash between the two stores and is safe to retry`() = withEnv {
        val me = login("0712345678"); val other = login("0722222222")
        val r1 = req(price = 4500, msAgo = 1000); val r2 = req(price = 3000, msAgo = Duration.ofDays(3).toMillis())
        val r3 = req(price = 5100, msAgo = 500)
        submit(me.token, r1); submit(me.token, r2); submit(other.token, r3)
        client.post("/v1/reports/${r3.reportId}/flags") { bearer(me.token); jsonBody(FlagRequest()) }
        val meId = get("/v1/me", me.token).json<UserDto>().userId

        // crash after the Cassandra phase, before anything in MongoDB is touched
        services.accountDeletionHook = { throw IllegalStateException("simulated crash between stores") }
        assertEquals(500, del("/v1/me", me.token).status.value)

        // Cassandra is already anonymised...
        assertEquals(0, count("reports_by_user", "WHERE user_id = $meId AND year = 2026"))
        assertEquals(0, count("flags_by_user", "WHERE user_id = $meId"))
        assertEquals(0, count("flags_by_report"))
        assertEquals(setOf(null), session.execute("SELECT report_id, user_id FROM reports_by_market_commodity").all()
            .filter { it.getUuid("report_id").toString() in setOf(r1.reportId, r2.reportId) }.map { it.getUuid("user_id") }.toSet())
        // ...while the account still exists, so the client can retry
        assertEquals(2, mcount("users")); assertEquals(2, mcount("refresh_tokens"))
        assertEquals(200, get("/v1/me", me.token).status.value)

        services.accountDeletionHook = {}
        assertEquals(204, del("/v1/me", me.token).status.value)
        assertEquals(204, del("/v1/me", me.token).status.value)   // retry after success: still fine

        // fully deleted, no orphans in either store
        assertEquals(1, mcount("users")); assertEquals(1, mcount("refresh_tokens")); assertEquals(0, mcount("otp_codes"))
        assertTrue(mdocs("users").none { it.getString("_id") == meId })
        assertTrue(mdocs("refresh_tokens").none { it.getString("user_id") == meId })
        val orphanUserIds = listOf(
            "SELECT user_id FROM reports_by_market_commodity", "SELECT user_id FROM report_locator",
            "SELECT user_id FROM reports_by_user", "SELECT user_id FROM flags_by_report", "SELECT user_id FROM flags_by_user",
        ).flatMap { q -> session.execute(q).all().mapNotNull { it.getUuid("user_id")?.toString() } }
        assertTrue(meId !in orphanUserIds, "orphaned rows still reference the deleted user")
        assertEquals(3, count("reports_by_market_commodity"))
        assertEquals(2, services.latestByMarket.forPair("wakulima", "tomatoes").single().count)
    }
}
