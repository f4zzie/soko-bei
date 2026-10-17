package ke.sokobei.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import ke.sokobei.app.data.db.ReportStatus
import ke.sokobei.app.data.db.SokoBeiDatabase
import ke.sokobei.app.data.sync.ReportSyncer
import ke.sokobei.app.data.sync.SyncOutcome
import ke.sokobei.app.support.FakeSettings
import ke.sokobei.app.support.FakeTokenStore
import ke.sokobei.app.support.FixedClock
import ke.sokobei.app.support.MockBackend
import ke.sokobei.app.support.errorJson
import ke.sokobei.app.support.inMemoryDb
import ke.sokobei.app.support.json
import ke.sokobei.app.support.pending
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class ReportSyncerTest {
    private lateinit var db: SokoBeiDatabase
    private val clock = FixedClock()
    private val day = 24L * 60 * 60 * 1000

    @Before fun setUp() { db = inMemoryDb(ApplicationProvider.getApplicationContext<Context>()) }
    @After fun tearDown() { db.close() }

    private fun syncer(backend: MockBackend, settings: FakeSettings = FakeSettings(consent = 1)) =
        ReportSyncer(backend.api, db.reportDao(), backend.tokens, settings, clock)

    private fun reportPosts(b: MockBackend) = b.requests.filter { it.method == HttpMethod.Post && it.url.encodedPath == "/v1/reports" }
    private fun bodyOf(req: io.ktor.client.request.HttpRequestData): JsonObject =
        Json.parseToJsonElement((req.body as TextContent).text).jsonObject

    @Test fun created_201_marks_synced_and_sends_stored_id_and_timestamp() = runBlocking {
        val at = clock.now - 3 * 60 * 60 * 1000
        db.reportDao().insert(pending("rid-1", at))
        val backend = MockBackend(FakeTokenStore("acc", "ref")) { json(HttpStatusCode.Created, """{"report_id":"rid-1"}""") }

        assertEquals(SyncOutcome.DONE, syncer(backend).syncAll())

        assertEquals(ReportStatus.SYNCED, db.reportDao().get("rid-1")!!.status)
        val sent = bodyOf(reportPosts(backend).single())
        assertEquals("rid-1", sent["report_id"]!!.jsonPrimitive.content)
        assertEquals(at.toString(), sent["reported_at_ms"]!!.jsonPrimitive.content)
        assertEquals("sack_90kg", sent["unit"]!!.jsonPrimitive.content)
        assertEquals("4500", sent["price_kes"]!!.jsonPrimitive.content)
        assertEquals("2", sent["quantity"]!!.jsonPrimitive.content) // JSON number, not string
        assertEquals("Bearer acc", reportPosts(backend).single().headers[HttpHeaders.Authorization])
    }

    @Test fun already_exists_200_is_treated_as_synced() = runBlocking {
        db.reportDao().insert(pending("rid-2", clock.now - 1000))
        val backend = MockBackend(FakeTokenStore("acc", "ref")) { json(HttpStatusCode.OK, """{"report_id":"rid-2"}""") }
        assertEquals(SyncOutcome.DONE, syncer(backend).syncAll())
        assertEquals(ReportStatus.SYNCED, db.reportDao().get("rid-2")!!.status)
    }

    @Test fun conflict_409_is_rejected_with_reason_and_never_retried() = runBlocking {
        db.reportDao().insert(pending("rid-3", clock.now - 1000))
        val backend = MockBackend(FakeTokenStore("acc", "ref")) { errorJson(HttpStatusCode.Conflict, "CONFLICT", "report_id already used with a different payload") }

        assertEquals(SyncOutcome.DONE, syncer(backend).syncAll())
        val r = db.reportDao().get("rid-3")!!
        assertEquals(ReportStatus.REJECTED, r.status)
        assertEquals("report_id already used with a different payload", r.rejectReason)

        syncer(backend).syncAll() // second pass must not resend
        assertEquals(1, reportPosts(backend).size)
    }

    @Test fun validation_400_is_rejected() = runBlocking {
        db.reportDao().insert(pending("rid-4", clock.now - 1000))
        val backend = MockBackend(FakeTokenStore("acc", "ref")) { errorJson(HttpStatusCode.BadRequest, "VALIDATION_FAILED", "price out of range") }
        assertEquals(SyncOutcome.DONE, syncer(backend).syncAll())
        val r = db.reportDao().get("rid-4")!!
        assertEquals(ReportStatus.REJECTED, r.status)
        assertEquals("price out of range", r.rejectReason)
    }

    @Test fun server_error_500_keeps_pending_and_requests_retry() = runBlocking {
        db.reportDao().insert(pending("rid-5", clock.now - 1000))
        val backend = MockBackend(FakeTokenStore("acc", "ref")) { errorJson(HttpStatusCode.InternalServerError, "INTERNAL_ERROR", "boom") }
        assertEquals(SyncOutcome.RETRY, syncer(backend).syncAll())
        val r = db.reportDao().get("rid-5")!!
        assertEquals(ReportStatus.PENDING, r.status)
        assertEquals(1, r.attempts)
    }

    @Test fun io_error_keeps_pending_and_requests_retry() = runBlocking {
        db.reportDao().insert(pending("rid-6", clock.now - 1000))
        val backend = MockBackend(FakeTokenStore("acc", "ref")) { throw IOException("no route to host") }
        assertEquals(SyncOutcome.RETRY, syncer(backend).syncAll())
        assertEquals(ReportStatus.PENDING, db.reportDao().get("rid-6")!!.status)
    }

    @Test fun rate_limited_429_is_retried_not_rejected() = runBlocking {
        db.reportDao().insert(pending("rid-7", clock.now - 1000))
        val backend = MockBackend(FakeTokenStore("acc", "ref")) { errorJson(HttpStatusCode.TooManyRequests, "RATE_LIMITED", "slow down") }
        assertEquals(SyncOutcome.RETRY, syncer(backend).syncAll())
        assertEquals(ReportStatus.PENDING, db.reportDao().get("rid-7")!!.status)
    }

    @Test fun expired_401_refreshes_token_once_then_succeeds() = runBlocking {
        db.reportDao().insert(pending("rid-8", clock.now - 1000))
        val tokens = FakeTokenStore("old-access", "refresh-1")
        val backend = MockBackend(tokens) { req ->
            when (req.url.encodedPath) {
                "/v1/auth/refresh" -> json(HttpStatusCode.OK, """{"access_token":"new-access","expires_in":900}""")
                else ->
                    if (req.headers[HttpHeaders.Authorization] == "Bearer new-access") json(HttpStatusCode.Created, "{}")
                    else errorJson(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "token expired")
            }
        }
        assertEquals(SyncOutcome.DONE, syncer(backend).syncAll())

        assertEquals(ReportStatus.SYNCED, db.reportDao().get("rid-8")!!.status)
        assertEquals(1, backend.requests.count { it.url.encodedPath == "/v1/auth/refresh" })
        assertEquals(2, reportPosts(backend).size)
        assertEquals("new-access", tokens.access())
        assertEquals("refresh-1", tokens.refresh())
    }

    @Test fun refresh_rejected_keeps_pending_clears_session_and_waits_for_login() = runBlocking {
        db.reportDao().insert(pending("rid-9", clock.now - 1000))
        val tokens = FakeTokenStore("old-access", "dead-refresh")
        val backend = MockBackend(tokens) { req ->
            if (req.url.encodedPath == "/v1/auth/refresh") errorJson(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "refresh expired")
            else errorJson(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "token expired")
        }
        assertEquals(SyncOutcome.DONE, syncer(backend).syncAll())
        assertEquals(ReportStatus.PENDING, db.reportDao().get("rid-9")!!.status)
        assertNull(tokens.refresh())
        assertEquals(1, backend.requests.count { it.url.encodedPath == "/v1/auth/refresh" })
    }

    @Test fun refresh_failing_with_server_error_retries_and_keeps_session() = runBlocking {
        db.reportDao().insert(pending("rid-10", clock.now - 1000))
        val tokens = FakeTokenStore("old-access", "refresh-1")
        val backend = MockBackend(tokens) { req ->
            if (req.url.encodedPath == "/v1/auth/refresh") errorJson(HttpStatusCode.InternalServerError, "INTERNAL_ERROR", "x")
            else errorJson(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "token expired")
        }
        assertEquals(SyncOutcome.RETRY, syncer(backend).syncAll())
        assertEquals(ReportStatus.PENDING, db.reportDao().get("rid-10")!!.status)
        assertNotNull(tokens.refresh())
    }

    @Test fun not_logged_in_leaves_pending_without_retry_storm() = runBlocking {
        db.reportDao().insert(pending("rid-11", clock.now - 1000))
        val backend = MockBackend(FakeTokenStore()) { errorJson(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "login required") }
        assertEquals(SyncOutcome.DONE, syncer(backend).syncAll())
        assertEquals(ReportStatus.PENDING, db.reportDao().get("rid-11")!!.status)
        assertTrue(backend.requests.none { it.url.encodedPath == "/v1/auth/refresh" })
    }

    @Test fun consent_required_without_local_consent_stays_pending_and_is_not_rejected() = runBlocking {
        db.reportDao().insert(pending("rid-12", clock.now - 1000))
        val backend = MockBackend(FakeTokenStore("acc", "ref")) { errorJson(HttpStatusCode.Forbidden, "CONSENT_REQUIRED", "consent first") }
        assertEquals(SyncOutcome.DONE, syncer(backend, FakeSettings(consent = null)).syncAll())
        val r = db.reportDao().get("rid-12")!!
        assertEquals(ReportStatus.PENDING, r.status)
        assertNull(r.rejectReason)
        assertTrue(backend.requests.none { it.url.encodedPath == "/v1/me/consent" })
    }

    @Test fun consent_required_with_local_consent_posts_consent_then_resends() = runBlocking {
        db.reportDao().insert(pending("rid-13", clock.now - 1000))
        var consented = false
        val backend = MockBackend(FakeTokenStore("acc", "ref")) { req ->
            when {
                req.url.encodedPath == "/v1/me/consent" -> { consented = true; json(HttpStatusCode.OK, "{}") }
                consented -> json(HttpStatusCode.Created, "{}")
                else -> errorJson(HttpStatusCode.Forbidden, "CONSENT_REQUIRED", "consent first")
            }
        }
        assertEquals(SyncOutcome.DONE, syncer(backend).syncAll())
        assertEquals(ReportStatus.SYNCED, db.reportDao().get("rid-13")!!.status)
    }

    @Test fun reports_older_than_14_days_expire_and_are_not_sent() = runBlocking {
        db.reportDao().insert(pending("old", clock.now - 15 * day))
        db.reportDao().insert(pending("fresh", clock.now - 13 * day))
        val backend = MockBackend(FakeTokenStore("acc", "ref")) { json(HttpStatusCode.Created, "{}") }

        assertEquals(SyncOutcome.DONE, syncer(backend).syncAll())

        assertEquals(ReportStatus.EXPIRED, db.reportDao().get("old")!!.status)
        assertEquals(ReportStatus.SYNCED, db.reportDao().get("fresh")!!.status)
        val sentIds = reportPosts(backend).map { bodyOf(it)["report_id"]!!.jsonPrimitive.content }
        assertEquals(listOf("fresh"), sentIds)
    }

    @Test fun expiry_applies_even_when_offline() = runBlocking {
        db.reportDao().insert(pending("old", clock.now - 20 * day))
        val backend = MockBackend(FakeTokenStore("acc", "ref")) { throw IOException("offline") }
        assertEquals(SyncOutcome.DONE, syncer(backend).syncAll())
        assertEquals(ReportStatus.EXPIRED, db.reportDao().get("old")!!.status)
        assertTrue(backend.requests.isEmpty())
    }

    @Test fun reports_are_sent_oldest_first_and_stop_at_first_transient_failure() = runBlocking {
        db.reportDao().insert(pending("b", clock.now - 1000))
        db.reportDao().insert(pending("a", clock.now - 5000))
        var n = 0
        val backend = MockBackend(FakeTokenStore("acc", "ref")) {
            if (n++ == 0) json(HttpStatusCode.Created, "{}") else errorJson(HttpStatusCode.ServiceUnavailable, "INTERNAL_ERROR", "down")
        }
        assertEquals(SyncOutcome.RETRY, syncer(backend).syncAll())
        assertEquals(ReportStatus.SYNCED, db.reportDao().get("a")!!.status)
        assertEquals(ReportStatus.PENDING, db.reportDao().get("b")!!.status)
    }
}
