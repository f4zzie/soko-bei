package ke.sokobei.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Query("SELECT * FROM markets ORDER BY name") fun observeMarkets(): Flow<List<MarketEntity>>
    @Query("SELECT * FROM commodities ORDER BY nameEn") fun observeCommodities(): Flow<List<CommodityEntity>>
    @Query("SELECT * FROM commodities WHERE commodityId = :id") suspend fun commodity(id: String): CommodityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMarkets(items: List<MarketEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCommodities(items: List<CommodityEntity>)
    @Query("DELETE FROM markets") suspend fun clearMarkets()
    @Query("DELETE FROM commodities") suspend fun clearCommodities()

    @Transaction
    suspend fun replaceMarkets(items: List<MarketEntity>) { clearMarkets(); insertMarkets(items) }

    @Transaction
    suspend fun replaceCommodities(items: List<CommodityEntity>) { clearCommodities(); insertCommodities(items) }
}

@Dao
interface PriceDao {
    @Query("SELECT * FROM market_prices WHERE marketId = :marketId ORDER BY commodityNameEn")
    fun observeMarketPrices(marketId: String): Flow<List<MarketPriceEntity>>

    @Query("SELECT * FROM commodity_prices WHERE commodityId = :commodityId")
    fun observeCommodityPrices(commodityId: String): Flow<List<CommodityPriceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMarketPrices(items: List<MarketPriceEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCommodityPrices(items: List<CommodityPriceEntity>)
    @Query("DELETE FROM market_prices WHERE marketId = :marketId") suspend fun clearMarketPrices(marketId: String)
    @Query("DELETE FROM commodity_prices WHERE commodityId = :commodityId") suspend fun clearCommodityPrices(commodityId: String)

    @Transaction
    suspend fun replaceMarketPrices(marketId: String, items: List<MarketPriceEntity>) {
        clearMarketPrices(marketId); insertMarketPrices(items)
    }

    @Transaction
    suspend fun replaceCommodityPrices(commodityId: String, items: List<CommodityPriceEntity>) {
        clearCommodityPrices(commodityId); insertCommodityPrices(items)
    }

    @Query("SELECT * FROM trend_cache WHERE commodityId = :c AND marketId = :m AND days = :days AND grade = :grade")
    suspend fun trend(c: String, m: String, days: Int, grade: String): TrendCacheEntity?

    @Query("SELECT * FROM trend_cache WHERE commodityId = :c AND marketId = :m AND days = :days AND grade = :grade")
    fun observeTrend(c: String, m: String, days: Int, grade: String): Flow<TrendCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTrend(item: TrendCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMeta(meta: CacheMetaEntity)
    @Query("SELECT updatedAtMs FROM cache_meta WHERE `key` = :key") fun observeMeta(key: String): Flow<Long?>
    @Query("SELECT updatedAtMs FROM cache_meta WHERE `key` = :key") suspend fun meta(key: String): Long?
}

@Dao
interface ReportDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(report: PendingReportEntity)

    @Query("SELECT * FROM pending_reports ORDER BY reportedAtMs DESC")
    fun observeAll(): Flow<List<PendingReportEntity>>

    @Query("SELECT * FROM pending_reports WHERE reportId = :id") suspend fun get(id: String): PendingReportEntity?

    @Query("SELECT * FROM pending_reports WHERE status = 'PENDING' ORDER BY reportedAtMs ASC")
    suspend fun pending(): List<PendingReportEntity>

    @Query("SELECT COUNT(*) FROM pending_reports WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM pending_reports WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    /** Marks old PENDING reports EXPIRED. Returns the number of rows changed. */
    @Query("UPDATE pending_reports SET status = 'EXPIRED', updatedAtMs = :now WHERE status = 'PENDING' AND reportedAtMs < :cutoffMs")
    suspend fun expireOlderThan(cutoffMs: Long, now: Long): Int

    @Query("UPDATE pending_reports SET status = 'SYNCED', rejectReason = NULL, updatedAtMs = :now, attempts = attempts + 1 WHERE reportId = :id")
    suspend fun markSynced(id: String, now: Long)

    @Query("UPDATE pending_reports SET status = 'REJECTED', rejectReason = :reason, updatedAtMs = :now, attempts = attempts + 1 WHERE reportId = :id")
    suspend fun markRejected(id: String, reason: String, now: Long)

    @Query("UPDATE pending_reports SET attempts = attempts + 1, updatedAtMs = :now WHERE reportId = :id")
    suspend fun markAttempted(id: String, now: Long)

    @Query("DELETE FROM pending_reports") suspend fun clear()
}

@Database(
    entities = [
        MarketEntity::class, CommodityEntity::class, MarketPriceEntity::class, CommodityPriceEntity::class,
        TrendCacheEntity::class, CacheMetaEntity::class, PendingReportEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SokoBeiDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun priceDao(): PriceDao
    abstract fun reportDao(): ReportDao
}
