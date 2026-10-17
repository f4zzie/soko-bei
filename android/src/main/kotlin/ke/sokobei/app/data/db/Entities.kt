package ke.sokobei.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ke.sokobei.shared.UnitDto
import java.math.BigDecimal

@Entity(tableName = "markets")
data class MarketEntity(
    @PrimaryKey val marketId: String,
    val name: String,
    val county: String,
    val lat: Double?,
    val lng: Double?,
)

@Entity(tableName = "commodities")
data class CommodityEntity(
    @PrimaryKey val commodityId: String,
    val nameEn: String,
    val nameSw: String,
    val defaultUnit: String,
    val grades: List<String>,
    val units: List<UnitDto>,
) {
    fun displayName(swahili: Boolean): String = if (swahili && nameSw.isNotBlank()) nameSw else nameEn
    fun kgPerUnit(unit: String): BigDecimal? = units.firstOrNull { it.unit == unit }?.kgPerUnit
}

@Entity(tableName = "market_prices", primaryKeys = ["marketId", "commodityId", "grade"])
data class MarketPriceEntity(
    val marketId: String,
    val commodityId: String,
    val commodityNameEn: String,
    val commodityNameSw: String,
    val grade: String,
    val medianTodayPerKg: BigDecimal?,
    val lastPricePerKg: BigDecimal?,
    val reportsToday: Int,
    val updatedAtMs: Long,
)

@Entity(tableName = "commodity_prices", primaryKeys = ["commodityId", "marketId", "grade"])
data class CommodityPriceEntity(
    val commodityId: String,
    val marketId: String,
    val marketName: String,
    val county: String,
    val grade: String,
    val medianTodayPerKg: BigDecimal?,
    val lastPricePerKg: BigDecimal?,
    val reportsToday: Int,
    val updatedAtMs: Long,
)

/** Trend response cached as JSON, keyed by what was last viewed. */
@Entity(tableName = "trend_cache", primaryKeys = ["commodityId", "marketId", "days", "grade"])
data class TrendCacheEntity(
    val commodityId: String,
    val marketId: String,
    val days: Int,
    val grade: String,
    val json: String,
    val fetchedAtMs: Long,
)

/** When each cached collection was last refreshed from the server. */
@Entity(tableName = "cache_meta")
data class CacheMetaEntity(
    @PrimaryKey val key: String,
    val updatedAtMs: Long,
)

enum class ReportStatus { PENDING, SYNCED, REJECTED, EXPIRED }

@Entity(tableName = "pending_reports", indices = [Index("status"), Index("reportedAtMs")])
data class PendingReportEntity(
    @PrimaryKey val reportId: String,
    val reportedAtMs: Long,
    val marketId: String,
    val marketName: String,
    val commodityId: String,
    val commodityName: String,
    val grade: String,
    val unit: String,
    val quantity: BigDecimal,
    val priceKes: Int,
    val pricePerKg: BigDecimal?,
    val status: ReportStatus,
    val rejectReason: String? = null,
    val attempts: Int = 0,
    val updatedAtMs: Long,
)
