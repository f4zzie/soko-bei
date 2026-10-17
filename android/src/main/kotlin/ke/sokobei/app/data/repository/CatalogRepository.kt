package ke.sokobei.app.data.repository

import ke.sokobei.app.data.api.ApiResult
import ke.sokobei.app.data.api.SokoBeiApi
import ke.sokobei.app.data.db.CacheMetaEntity
import ke.sokobei.app.data.db.CatalogDao
import ke.sokobei.app.data.db.CommodityEntity
import ke.sokobei.app.data.db.MarketEntity
import ke.sokobei.app.data.db.PriceDao
import kotlinx.coroutines.flow.Flow

interface CatalogRepository {
    val markets: Flow<List<MarketEntity>>
    val commodities: Flow<List<CommodityEntity>>
    /** When markets were last downloaded (null = never). */
    val lastUpdatedMs: Flow<Long?>
    suspend fun refresh(): RefreshResult
}

class CatalogRepositoryImpl(
    private val api: SokoBeiApi,
    private val catalog: CatalogDao,
    private val prices: PriceDao,
    private val clock: Clock,
) : CatalogRepository {
    override val markets = catalog.observeMarkets()
    override val commodities = catalog.observeCommodities()
    override val lastUpdatedMs: Flow<Long?> = prices.observeMeta(KEY_MARKETS)

    override suspend fun refresh(): RefreshResult {
        val m = api.markets()
        val c = api.commodities()
        var result = RefreshResult.OK
        when (m) {
            is ApiResult.Success -> {
                catalog.replaceMarkets(m.value.map { MarketEntity(it.marketId, it.name, it.county, it.lat, it.lng) })
                prices.putMeta(CacheMetaEntity(KEY_MARKETS, clock.nowMs()))
            }
            else -> result = m.toRefreshResult()
        }
        when (c) {
            is ApiResult.Success -> {
                catalog.replaceCommodities(
                    c.value.map {
                        CommodityEntity(it.commodityId, it.nameEn, it.nameSw, it.defaultUnit, it.grades, it.units)
                    },
                )
                prices.putMeta(CacheMetaEntity(KEY_COMMODITIES, clock.nowMs()))
            }
            else -> if (result == RefreshResult.OK) result = c.toRefreshResult()
        }
        return result
    }

    companion object {
        const val KEY_MARKETS = "markets"
        const val KEY_COMMODITIES = "commodities"
    }
}

internal fun ApiResult<*>.toRefreshResult(): RefreshResult = when (this) {
    is ApiResult.Success -> RefreshResult.OK
    is ApiResult.NetworkError -> RefreshResult.OFFLINE
    is ApiResult.HttpError -> RefreshResult.ERROR
}
