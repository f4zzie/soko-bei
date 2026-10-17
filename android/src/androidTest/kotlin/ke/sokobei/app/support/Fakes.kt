package ke.sokobei.app.support

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import ke.sokobei.app.data.api.ApiClientFactory
import ke.sokobei.app.data.api.SokoBeiApi
import ke.sokobei.app.data.api.TokenStore
import ke.sokobei.app.data.db.CommodityEntity
import ke.sokobei.app.data.db.CommodityPriceEntity
import ke.sokobei.app.data.db.MarketEntity
import ke.sokobei.app.data.db.MarketPriceEntity
import ke.sokobei.app.data.db.PendingReportEntity
import ke.sokobei.app.data.db.ReportStatus
import ke.sokobei.app.data.repository.AppSettings
import ke.sokobei.app.data.repository.AuthRepository
import ke.sokobei.app.data.repository.AuthResult
import ke.sokobei.app.data.repository.CatalogRepository
import ke.sokobei.app.data.repository.Clock
import ke.sokobei.app.data.repository.PriceRepository
import ke.sokobei.app.data.repository.RefreshResult
import ke.sokobei.app.data.repository.ReportDraft
import ke.sokobei.app.data.repository.ReportRepository
import ke.sokobei.app.data.repository.TrendData
import ke.sokobei.app.data.sync.SyncScheduler
import ke.sokobei.app.ui.LanguageController
import ke.sokobei.shared.UnitDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal

class FixedClock(var now: Long = 1_800_000_000_000L) : Clock {
    override fun nowMs() = now
}

class FakeTokenStore(access: String? = null, refresh: String? = null) : TokenStore {
    private val a = MutableStateFlow(access)
    private val r = MutableStateFlow(refresh)
    override val loggedIn: Flow<Boolean> = r.map { it != null }
    override suspend fun access() = a.value
    override suspend fun refresh() = r.value
    override suspend fun save(access: String, refresh: String) { a.value = access; r.value = refresh }
    override suspend fun updateAccess(access: String) { a.value = access }
    override suspend fun clear() { a.value = null; r.value = null }
}

class FakeSettings(consent: Int? = null, market: String? = null) : AppSettings {
    private val consent = MutableStateFlow(consent)
    private val market = MutableStateFlow(market)
    private val onb = MutableStateFlow(consent != null)
    override val onboarded: Flow<Boolean> = onb
    override val consentVersion: Flow<Int?> = this.consent
    override val selectedMarketId: Flow<String?> = this.market
    override suspend fun acceptConsent(version: Int) { consent.value = version; onb.value = true }
    override suspend fun setSelectedMarket(marketId: String) { market.value = marketId }
    override suspend fun clear() { consent.value = null; market.value = null; onb.value = false }
}

class RecordingScheduler : SyncScheduler {
    var enqueued = 0
    var periodic = 0
    override fun enqueueNow() { enqueued++ }
    override fun ensurePeriodic() { periodic++ }
}

class FakeLanguage(var tag: String = "en") : LanguageController {
    override fun current() = tag
    override fun set(tag: String) { this.tag = tag }
}

class FakeCatalogRepository(
    markets: List<MarketEntity> = emptyList(),
    commodities: List<CommodityEntity> = emptyList(),
) : CatalogRepository {
    val marketFlow = MutableStateFlow(markets)
    val commodityFlow = MutableStateFlow(commodities)
    override val markets: Flow<List<MarketEntity>> = marketFlow
    override val commodities: Flow<List<CommodityEntity>> = commodityFlow
    override val lastUpdatedMs: Flow<Long?> = MutableStateFlow(null)
    var refreshResult = RefreshResult.OK
    var refreshCalls = 0
    override suspend fun refresh(): RefreshResult { refreshCalls++; return refreshResult }
}

class FakePriceRepository : PriceRepository {
    val marketPriceFlows = mutableMapOf<String, MutableStateFlow<List<MarketPriceEntity>>>()
    val commodityPriceFlows = mutableMapOf<String, MutableStateFlow<List<CommodityPriceEntity>>>()
    val trendFlows = mutableMapOf<String, MutableStateFlow<TrendData?>>()
    val refreshedMarkets = mutableListOf<String>()
    val refreshedTrends = mutableListOf<Int>()
    var result = RefreshResult.OK

    private fun <T> flowOf(map: MutableMap<String, MutableStateFlow<T>>, key: String, init: T) = map.getOrPut(key) { MutableStateFlow(init) }

    override fun marketPrices(marketId: String) = flowOf(marketPriceFlows, marketId, emptyList())
    override fun marketPricesUpdatedMs(marketId: String): Flow<Long?> = MutableStateFlow(1_000L)
    override suspend fun refreshMarketPrices(marketId: String): RefreshResult { refreshedMarkets += marketId; return result }

    override fun commodityPrices(commodityId: String) = flowOf(commodityPriceFlows, commodityId, emptyList())
    override fun commodityPricesUpdatedMs(commodityId: String): Flow<Long?> = MutableStateFlow(null)
    override suspend fun refreshCommodityPrices(commodityId: String) = result

    override fun trend(commodityId: String, marketId: String, days: Int, grade: String): Flow<TrendData?> =
        flowOf(trendFlows, "$days", null)
    override suspend fun refreshTrend(commodityId: String, marketId: String, days: Int, grade: String): RefreshResult {
        refreshedTrends += days
        return result
    }
}

class FakeReportRepository : ReportRepository {
    val reports = MutableStateFlow<List<PendingReportEntity>>(emptyList())
    val drafts = mutableListOf<ReportDraft>()
    override fun observeReports(): Flow<List<PendingReportEntity>> = reports
    override suspend fun submit(draft: ReportDraft): String { drafts += draft; return "id-${drafts.size}" }
}

class FakeAuthRepository : AuthRepository {
    val loggedInFlow = MutableStateFlow(false)
    override val loggedIn: Flow<Boolean> = loggedInFlow
    var otpResult: AuthResult = AuthResult.Success
    var verifyResult: AuthResult = AuthResult.Success
    var deleteResult: AuthResult = AuthResult.Success
    val otpRequests = mutableListOf<String>()
    val verifies = mutableListOf<Pair<String, String>>()
    var logoutCalls = 0
    override suspend fun requestOtp(phone: String): AuthResult { otpRequests += phone; return otpResult }
    override suspend fun verifyOtp(phone: String, code: String): AuthResult { verifies += phone to code; return verifyResult }
    override suspend fun logout() { logoutCalls++; loggedInFlow.value = false }
    override suspend fun deleteAccount(): AuthResult = deleteResult
}

// ---- sample data (test only) ----
val maize = CommodityEntity(
    commodityId = "maize", nameEn = "Maize", nameSw = "Mahindi", defaultUnit = "sack_90kg",
    grades = listOf("standard", "premium"),
    units = listOf(
        UnitDto("kg", BigDecimal("1"), 1, true),
        UnitDto("sack_90kg", BigDecimal("90"), 1, true),
        UnitDto("debe", BigDecimal("18"), 1, false),
    ),
)
val beans = CommodityEntity("beans", "Beans", "Maharagwe", "kg", listOf("standard"), listOf(UnitDto("kg", BigDecimal("1"), 1, true)))
val gikomba = MarketEntity("gikomba", "Gikomba", "Nairobi", -1.28, 36.83)
val wakulima = MarketEntity("wakulima", "Wakulima", "Nairobi", null, null)

fun pending(
    id: String,
    reportedAt: Long,
    status: ReportStatus = ReportStatus.PENDING,
    price: Int = 4500,
) = PendingReportEntity(
    reportId = id, reportedAtMs = reportedAt, marketId = "gikomba", marketName = "Gikomba",
    commodityId = "maize", commodityName = "Maize", grade = "standard", unit = "sack_90kg",
    quantity = BigDecimal("2"), priceKes = price, pricePerKg = BigDecimal("25.00"),
    status = status, updatedAtMs = reportedAt,
)

/** Builds a real SokoBeiApi (real Ktor client + Auth plugin) over a MockEngine. */
class MockBackend(val tokens: TokenStore, handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val requests = mutableListOf<HttpRequestData>()
    val engine = MockEngine { req ->
        requests += req
        handler(req)
    }
    val api = SokoBeiApi(ApiClientFactory.create(engine, "http://localhost:8080", tokens))
}

fun MockRequestHandleScope.json(status: HttpStatusCode, body: String) =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

fun MockRequestHandleScope.errorJson(status: HttpStatusCode, code: String, message: String) =
    json(status, """{"error":{"code":"$code","message":"$message","details":{}}}""")

val tomatoes = CommodityEntity(
    "tomatoes", "Tomatoes", "Nyanya", "crate", listOf("standard"),
    listOf(UnitDto("kg", BigDecimal("1"), 1, true), UnitDto("crate", BigDecimal("60"), 1, true)),
)
