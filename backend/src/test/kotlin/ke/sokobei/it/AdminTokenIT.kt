package ke.sokobei.it

import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import ke.sokobei.shared.ErrorEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminTokenIT {
    private val url = "/v1/admin/rebuild?from=2026-10-10&to=2026-10-17"
    private suspend fun Env.call(header: String?): HttpResponse =
        client.post(url) { header?.let { headers.append("X-Admin-Token", it) } }

    @Test fun `empty config closes the admin API even for an empty header`() = withEnv(tweak = { it.copy(adminToken = "") }) {
        assertEquals(403, call(null).status.value)
        assertEquals(403, call("").status.value)
    }

    @Test fun `empty config rejects any header value`() = withEnv(tweak = { it.copy(adminToken = "") }) {
        listOf("anything", " ", "null", "dev-admin-token", "0".repeat(64)).forEach { assertEquals(403, call(it).status.value, it) }
        assertEquals(403, client.post("/v1/admin/jobs/daily-stats") { headers.append("X-Admin-Token", "x") }.status.value)
    }

    private val token = "t".repeat(40)

    @Test fun `wrong token is rejected, correct token works`() = withEnv(tweak = { it.copy(adminToken = token) }) {
        assertEquals(403, call(null).status.value)
        assertEquals(403, call("").status.value)
        assertEquals(403, call("wrong").status.value)
        assertEquals(403, call(token.dropLast(1)).status.value)   // prefix of the real token
        assertEquals(403, call(token + "x").status.value)         // longer than the real token
        assertEquals(403, call(token.uppercase()).status.value)
        val bad = call("wrong")
        assertEquals("FORBIDDEN", kotlinx.coroutines.runBlocking { bad.json<ErrorEnvelope>().error.code })
        assertEquals(200, call(token).status.value)
        assertEquals(200, client.post("/v1/admin/jobs/daily-stats") { headers.append("X-Admin-Token", token) }.status.value)
    }
}
