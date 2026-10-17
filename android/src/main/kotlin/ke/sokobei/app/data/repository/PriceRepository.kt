package ke.sokobei.app.data.repository

import ke.sokobei.app.data.api.ApiJson
import ke.sokobei.app.data.api.ApiResult
import ke.sokobei.app.data.api.SokoBeiApi
import ke.sokobei.app.data.db.CacheMetaEntity
import ke.sokobei.app.data.db.CommodityPriceEntity
import ke.sokobei.app.data.db.MarketPriceEntity
import ke.sokobei.app.data.db.PriceDao
import ke.sokobei.app.data.db.TrendCacheEntity
import ke.sokobei.shared.TrendPointDto
import ke.sokobei.shared.TrendResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class TrendData(val points: List<TrendPointDto>, val fetchedAtMs: Long)

interface PriceRepository {
    fun marketPrices(marketId: String): Flow<List<MarketPriceEntity>>
    fun marketPricesUpdatedMs(marketId: String): Flow<Long?>
    suspend fun refreshMarketPrices(marketId: String): RefreshResult

    fun commodityPrices(commodityId: String): Flow<List<CommodityPriceEntity>>
    fun commodityPricesUpdatedMs(commodityId: String): Flow<Long?>
    suspend fun refreshCommodityPrices(commodityId: String): RefreshResult

    fun trend(commodityId: String, marketId: String, days: Int, grade: String): Flow<TrendData?>
    suspend fun refreshTrend(commodityId: String, marketId: String, days: Int, grade: String): RefreshResult
}

class PriceRepositoryImpl(
    private val api: SokoBeiApi,
    private val dao: PriceDao,
    private val clock: Clock,
) : PriceRepository {

    override fun marketPrices(marketId: String) = dao.observeMarketPrices(marketId)
    override fun marketPricesUpdatedMs(marketId: String) = dao.observeMeta(marketKey(marketId))

    override suspend fun refreshMarketPrices(marketId: String): RefreshResult {
        val r = api.marketPrices(marketId)
        if (r is ApiResult.Success) {
            dao.replaceMarketPrices(
                marketId,
                r.value.map {
                    MarketPriceEntity(
                        marketId, it.commodityId, it.commodityNameEn, it.commodityNameSw, it.grade,
                        it.medianTodayPerKg, it.lastPricePerKg, it.reportsToday, it.updatedAtMs,
                    )
                },
            )
            dao.putMeta(CacheMetaEntity(marketKey(marketId), clock.nowMs()))
        }
        return r.toRefreshResult()
    }

    override fun commodityPrices(commodityId: String) = dao.observeCommodityPrices(commodityId)
    override fun commodityPricesUpdatedMs(commodityId: String) = dao.observeMeta(commodityKey(commodityId))

    override suspend fun refreshCommodityPrices(commodityId: String): RefreshResult {
        val r = api.commodityPrices(commodityId)
        if (r is ApiResult.Success) {
            dao.replaceCommodityPrices(
                commodityId,
                r.value.map {
                    CommodityPriceEntity(
                        commodityId, it.marketId, it.marketName, it.county, it.grade,
                        it.medianTodayPerKg, it.lastPricePerKg, it.reportsToday, it.updatedAtMs,
                    )
                },
            )
            dao.putMeta(CacheMetaEntity(commodityKey(commodityId), clock.nowMs()))
        }
        return r.toRefreshResult()
    }

    override fun trend(commodityId: String, marketId: String, days: Int, grade: String): Flow<TrendData?> =
        dao.observeTrend(commodityId, marketId, days, grade).map { row ->
            row?.let { TrendData(ApiJson.decodeFromString(TrendResponse.serializer(), it.json).points, it.fetchedAtMs) }
        }

    override suspend fun refreshTrend(commodityId: String, marketId: String, days: Int, grade: String): RefreshResult {
        val r = api.trend(commodityId, marketId, days, grade)
        if (r is ApiResult.Success) {
            dao.putTrend(
                TrendCacheEntity(
                    commodityId, marketId, days, grade,
                    ApiJson.encodeToString(TrendResponse.serializer(), r.value), clock.nowMs(),
                ),
            )
        }
        return r.toRefreshResult()
    }

    private fun marketKey(id: String) = "market_prices:$id"
    private fun commodityKey(id: String) = "commodity_prices:$id"
}
