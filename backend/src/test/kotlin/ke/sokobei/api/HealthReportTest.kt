package ke.sokobei.api

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HealthReportTest {
    private fun HealthReport.field(k: String) = body[k]?.jsonPrimitive?.content

    @Test
    fun `both stores up gives 200 and ok`() = runTest {
        val r = healthReport({ "5.0.9" }, { })
        assertEquals(HttpStatusCode.OK, r.httpStatus)
        assertEquals("ok", r.field("status"))
        assertEquals("up", r.field("cassandra"))
        assertEquals("up", r.field("mongodb"))
        assertEquals("5.0.9", r.field("cassandra_version"))
    }

    @Test
    fun `cassandra down gives 503 with a JSON body naming it`() = runTest {
        val r = healthReport({ error("no host available") }, { })
        assertEquals(HttpStatusCode.ServiceUnavailable, r.httpStatus)
        assertEquals("degraded", r.field("status"))
        assertEquals("down", r.field("cassandra"))
        assertEquals("up", r.field("mongodb"))
        assertFalse("cassandra_version" in r.body)
    }

    @Test
    fun `mongodb down gives 503 with a JSON body naming it`() = runTest {
        val r = healthReport({ "5.0.9" }, { error("timeout") })
        assertEquals(HttpStatusCode.ServiceUnavailable, r.httpStatus)
        assertEquals("up", r.field("cassandra"))
        assertEquals("down", r.field("mongodb"))
    }

    @Test
    fun `both down gives 503`() = runTest {
        val r = healthReport({ error("x") }, { error("y") })
        assertEquals(HttpStatusCode.ServiceUnavailable, r.httpStatus)
        assertEquals("down", r.field("cassandra"))
        assertEquals("down", r.field("mongodb"))
    }
}
