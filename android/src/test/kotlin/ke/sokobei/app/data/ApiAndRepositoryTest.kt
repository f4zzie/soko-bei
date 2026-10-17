package ke.sokobei.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import ke.sokobei.app.data.api.ApiResult
import ke.sokobei.app.data.db.ReportStatus
import ke.sokobei.app.data.db.SokoBeiDatabase
import ke.sokobei.app.data.repository.AuthRepositoryImpl
import ke.sokobei.app.data.repository.AuthResult
import ke.sokobei.app.data.repository.CatalogRepositoryImpl
import ke.sokobei.app.data.repository.PriceRepositoryImpl
import ke.sokobei.app.data.repository.RefreshResult
import ke.sokobei.app.data.repository.ReportDraft
import ke.sokobei.app.data.repository.ReportRepositoryImpl
import ke.sokobei.app.support.FakeSettings
import ke.sokobei.app.support.FakeTokenStore
import ke.sokobei.app.support.FixedClock
import ke.sokobei.app.support.MockBackend
import ke.sokobei.app.support.RecordingScheduler
import ke.sokobei.app.support.errorJson
import ke.sokobei.app.support.inMemoryDb
import ke.sokobei.app.support.json
import ke.sokobei.app.support.pending
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.math.BigDecimal
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ApiAndRepositoryTest {
    private lateinit var db: SokoBeiDatabase
    private val clock = FixedClock(5_000)

    @Before fun setUp() { db = inMemoryDb(ApplicationProvider.getApplicationContext<Context>()) }
    @After fun tearDown() { db.close() }

    private val marketJson = { id: String -> """{"market_id":"$id","name":"M $id","county":"Nairobi"}""" }

    @Test fun list_endpoints_follow_next_page_token_until_exhausted() = runBlocking {
        val backend = MockBackend(FakeTokenStore()) { req ->
            when (req.url.parameters["page_token"]) {
                null -> json(HttpStatusCode.OK, """{"items":[${marketJson("a")},${marketJson("b")}],"next_page_token":"t1"}""")
                "t1" -> json(HttpStatusCode.OK, """{"items":[${marketJson("c")}],"next_page_token":"t2"}""")
                "t2" -> json(HttpStatusCode.OK, """{"items":[${marketJson("d")}],"next_page_token":null}""")
                else -> errorJson(HttpStatusCode.BadRequest, "VALIDATION_FAILED", "bad token")
            }
        }
        val r = backend.api.markets() as ApiResult.Success
        assertEquals(listOf("a", "b", "c", "d"), r.value.map { it.marketId })
        assertEquals(3, backend.requests.size)
        assertTrue(backend.requests.all { it.url.parameters["limit"] == "100" })
        // Public reads carry no Authorization header.
        assertTrue(backend.requests.all { HttpHeaders.Authorization !in it.headers.names() })
    }

    @Test fun repeating_page_token_does_not_loop_forever() = runBlocking {
        val backend = MockBackend(FakeTokenStore()) { json(HttpStatusCode.OK, """{"items":[],"next_page_token":"same"}""") }
        assertTrue(backend.api.markets() is ApiResult.NetworkError)
    }

    @Test fun error_envelope_is_parsed() = runBlocking {
        val backend = MockBackend(FakeTokenStore("a", "r")) { errorJson(HttpStatusCode.BadRequest, "VALIDATION_FAILED", "price too high") }
        val e = backend.api.commodities() as ApiResult.HttpError
        assertEquals(400, e.status); assertEquals("VALIDATION_FAILED", e.code); assertEquals("price too high", e.message)
    }

    @Test fun catalog_refresh_caches_markets_and_commodities_and_stamps_time() = runBlocking {
        val backend = MockBackend(FakeTokenStore()) { req ->
            when {
                req.url.encodedPath == "/v1/markets" -> json(HttpStatusCode.OK, """{"items":[${marketJson("gikomba")}],"next_page_token":null}""")
                else -> json(
                    HttpStatusCode.OK,
                    """{"items":[{"commodity_id":"maize","name_en":"Maize","name_sw":"Mahindi","default_unit":"sack_90kg","grades":["standard"],
                    "units":[{"unit":"sack_90kg","kg_per_unit":90,"version":1,"verified":true}]}],"next_page_token":null}""",
                )
            }
        }
        val repo = CatalogRepositoryImpl(backend.api, db.catalogDao(), db.priceDao(), clock)
        assertEquals(RefreshResult.OK, repo.refresh())
        assertEquals(listOf("gikomba"), repo.markets.first().map { it.marketId })
        assertEquals(BigDecimal("90"), repo.commodities.first().single().kgPerUnit("sack_90kg"))
        assertEquals(5_000L, repo.lastUpdatedMs.first())
    }

    @Test fun offline_refresh_keeps_cached_data_and_reports_offline() = runBlocking {
        db.catalogDao().replaceMarkets(listOf(ke.sokobei.app.support.gikomba))
        val backend = MockBackend(FakeTokenStore()) { throw IOException("offline") }
        val repo = CatalogRepositoryImpl(backend.api, db.catalogDao(), db.priceDao(), clock)
        assertEquals(RefreshResult.OFFLINE, repo.refresh())
        assertEquals(1, repo.markets.first().size)
    }

    @Test fun price_refresh_caches_prices_with_decimal_numbers_and_trend_json() = runBlocking {
        val backend = MockBackend(FakeTokenStore()) { req ->
            if (req.url.encodedPath.endsWith("/trend"))
                json(HttpStatusCode.OK, """{"commodity_id":"maize","market_id":"gikomba","days":30,"points":[
                    {"day":"2026-10-01","grade":"standard","min_per_kg":40.5,"median_per_kg":50,"max_per_kg":60.25,"report_count":4}]}""")
            else json(HttpStatusCode.OK, """{"items":[{"commodity_id":"maize","commodity_name_en":"Maize","commodity_name_sw":"Mahindi","grade":"standard",
                "median_today_per_kg":52.5,"last_price_per_kg":null,"reports_today":3,"updated_at_ms":4000}],"next_page_token":null}""")
        }
        val repo = PriceRepositoryImpl(backend.api, db.priceDao(), clock)
        assertEquals(RefreshResult.OK, repo.refreshMarketPrices("gikomba"))
        val p = repo.marketPrices("gikomba").first().single()
        assertEquals(0, BigDecimal("52.5").compareTo(p.medianTodayPerKg))
        assertNull(p.lastPricePerKg)
        assertEquals(5_000L, repo.marketPricesUpdatedMs("gikomba").first())

        assertEquals(RefreshResult.OK, repo.refreshTrend("maize", "gikomba", 30, "standard"))
        val t = repo.trend("maize", "gikomba", 30, "standard").first()!!
        assertEquals(0, BigDecimal("60.25").compareTo(t.points.single().maxPerKg))
    }

    @Test fun submit_stores_pending_report_with_uuid_and_timestamp_without_network() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = ReportRepositoryImpl(db.reportDao(), scheduler, clock)
        val id = repo.submit(
            ReportDraft("gikomba", "Gikomba", "maize", "Maize", "standard", "sack_90kg", BigDecimal("2"), 9000, BigDecimal("50.00")),
        )
        assertEquals(id, UUID.fromString(id).toString())
        assertEquals(4, UUID.fromString(id).version())
        val stored = db.reportDao().get(id)!!
        assertEquals(ReportStatus.PENDING, stored.status)
        assertEquals(5_000L, stored.reportedAtMs)
        assertEquals(1, scheduler.enqueued)
        // A second report gets a different id.
        val id2 = repo.submit(ReportDraft("gikomba", "Gikomba", "maize", "Maize", "standard", "kg", BigDecimal.ONE, 50, null))
        assertTrue(id != id2)
    }

    @Test fun login_stores_tokens_posts_consent_and_schedules_sync() = runBlocking {
        val tokens = FakeTokenStore()
        val scheduler = RecordingScheduler()
        val backend = MockBackend(tokens) { req ->
            when (req.url.encodedPath) {
                "/v1/auth/otp/verify" -> json(HttpStatusCode.OK, """{"access_token":"A","refresh_token":"R","expires_in":900}""")
                else -> json(HttpStatusCode.OK, "{}")
            }
        }
        val auth = AuthRepositoryImpl(backend.api, tokens, FakeSettings(consent = 1), db.reportDao(), scheduler)
        assertEquals(AuthResult.Success, auth.verifyOtp("0712345678", "000000"))
        assertEquals("A", tokens.access()); assertEquals("R", tokens.refresh())
        val consent = backend.requests.single { it.url.encodedPath == "/v1/me/consent" }
        assertEquals("Bearer A", consent.headers[HttpHeaders.Authorization])
        assertEquals(1, scheduler.enqueued)
    }

    @Test fun wrong_otp_is_a_failure_and_stores_nothing() = runBlocking {
        val tokens = FakeTokenStore()
        val backend = MockBackend(tokens) { errorJson(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "invalid code") }
        val auth = AuthRepositoryImpl(backend.api, tokens, FakeSettings(), db.reportDao(), RecordingScheduler())
        val r = auth.verifyOtp("0712345678", "111111")
        assertTrue(r is AuthResult.Failure && r.kind == AuthResult.Kind.INVALID_INPUT)
        assertNull(tokens.refresh())
    }

    @Test fun delete_account_wipes_tokens_reports_and_settings() = runBlocking {
        val tokens = FakeTokenStore("A", "R")
        val settings = FakeSettings(consent = 1)
        db.reportDao().insert(pending("x", 1))
        val backend = MockBackend(tokens) { req ->
            assertEquals("DELETE", req.method.value)
            json(HttpStatusCode.NoContent, "")
        }
        val auth = AuthRepositoryImpl(backend.api, tokens, settings, db.reportDao(), RecordingScheduler())
        assertEquals(AuthResult.Success, auth.deleteAccount())
        assertNull(tokens.refresh())
        assertEquals(0, db.reportDao().observeAll().first().size)
        assertEquals(false, settings.onboarded.first())
    }

    @Test fun delete_account_offline_changes_nothing() = runBlocking {
        val tokens = FakeTokenStore("A", "R")
        db.reportDao().insert(pending("x", 1))
        val backend = MockBackend(tokens) { throw IOException("offline") }
        val auth = AuthRepositoryImpl(backend.api, tokens, FakeSettings(consent = 1), db.reportDao(), RecordingScheduler())
        val r = auth.deleteAccount()
        assertTrue(r is AuthResult.Failure && r.kind == AuthResult.Kind.NETWORK)
        assertNotNull(tokens.refresh())
        assertEquals(1, db.reportDao().observeAll().first().size)
    }
}
