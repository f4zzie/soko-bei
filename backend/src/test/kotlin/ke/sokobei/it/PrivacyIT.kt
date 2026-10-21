package ke.sokobei.it

import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import ke.sokobei.shared.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrivacyIT {
    // One user's phone number in every format a client or a log might show it.
    private val local = "0718273645"
    private val forms = listOf(local, "+254718273645", "254718273645")

    @Test fun `no read endpoint returns another user's phone number`() = withEnv {
        val first = login(local)
        val second = login("0729384756")
        val mine = req(price = 4500)
        assertEquals(201, submit(first.token, mine).status.value)
        assertEquals(201, submit(second.token, req(price = 4800)).status.value)
        assertEquals(201, client.post("/v1/reports/${mine.reportId}/flags") { bearer(second.token); jsonBody(FlagRequest()) }.status.value)
        val month = clock.instant().toString().take(7)

        val paths = listOf(
            "/health", "/metrics", "/v1/markets", "/v1/commodities",
            "/v1/markets/wakulima/prices", "/v1/commodities/tomatoes/prices",
            "/v1/commodities/tomatoes/markets/wakulima/reports?month=$month",
            "/v1/commodities/tomatoes/markets/wakulima/trend",
            "/v1/commodities/nope/prices", "/v1/markets/nope/prices", // error bodies too
        )
        val authenticated = listOf("/v1/me", "/v1/me/reports?year=${month.take(4)}")

        var checked = 0
        fun assertClean(path: String, who: String, body: String) {
            forms.forEach { assertTrue(it !in body, "$who GET $path leaked $it: $body") }
            checked++
        }
        for (p in paths) {
            assertClean(p, "anonymous", get(p).bodyAsText())
            assertClean(p, "second user", get(p, second.token).bodyAsText())
        }
        for (p in authenticated) {
            val r = get(p, second.token)
            assertEquals(200, r.status.value, p)
            assertClean(p, "second user", r.bodyAsText())
        }
        // the report list must actually contain the first user's report, or the check above proves nothing
        val list = get("/v1/commodities/tomatoes/markets/wakulima/reports?month=$month", second.token).json<Page<ReportDto>>()
        assertTrue(list.items.any { it.reportId == mine.reportId })
        assertEquals(paths.size * 2 + authenticated.size, checked)
    }
}
