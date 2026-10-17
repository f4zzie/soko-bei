package ke.sokobei.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class Market(val marketId: String, val name: String, val county: String, val lat: Double?, val lng: Double?)

data class Commodity(
    val commodityId: String,
    val nameEn: String,
    val nameSw: String,
    val defaultUnit: String,
    val grades: List<String>,
)

data class UnitFactor(
    val commodityId: String,
    val unit: String,
    val version: Int,
    val kgPerUnit: BigDecimal,
    val verified: Boolean,
)

/** A row of the source-of-truth table. [userId] is null after account deletion. */
data class StoredReport(
    val commodityId: String,
    val marketId: String,
    val month: String,
    val reportedAt: UUID,
    val reportId: UUID,
    val receivedAt: Instant,
    val userId: UUID?,
    val grade: String,
    val unit: String,
    val quantity: BigDecimal,
    val priceKes: Int,
    val kgPerUnit: BigDecimal,
    val conversionVersion: Int,
    val pricePerKgKes: BigDecimal,
)

data class UserRecord(
    val userId: UUID,
    val phone: String,
    val displayName: String?,
    val consentVersion: Int?,
    val consentAt: Instant?,
    val createdAt: Instant,
)

data class ReportLocator(
    val reportId: UUID,
    val commodityId: String,
    val marketId: String,
    val month: String,
    val reportedAt: UUID,
    val userId: UUID?,
    val payloadHash: String,
)

/** Aggregate for one (commodity, market, grade) over a slice of reports. */
data class GradeStats(
    val grade: String,
    val min: BigDecimal,
    val median: BigDecimal,
    val max: BigDecimal,
    val last: BigDecimal,
    val count: Int,
)

data class LatestRow(
    val commodityId: String,
    val marketId: String,
    val grade: String,
    val median: BigDecimal?,
    val last: BigDecimal?,
    val count: Int,
    val updatedAt: Instant,
)

data class DailyStat(
    val commodityId: String,
    val marketId: String,
    val day: LocalDate,
    val grade: String,
    val min: BigDecimal,
    val median: BigDecimal,
    val max: BigDecimal,
    val count: Int,
)
