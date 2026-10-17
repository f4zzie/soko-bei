@file:UseSerializers(BigDecimalSerializer::class)

package ke.sokobei.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.math.BigDecimal


@Serializable
data class ErrorBody(val code: String, val message: String, val details: Map<String, String> = emptyMap())

@Serializable
data class ErrorEnvelope(val error: ErrorBody)

@Serializable
data class Page<T>(val items: List<T>, @SerialName("next_page_token") val nextPageToken: String? = null)

@Serializable
data class MarketDto(
    @SerialName("market_id") val marketId: String,
    val name: String,
    val county: String,
    val lat: Double? = null,
    val lng: Double? = null,
)

@Serializable
data class UnitDto(
    val unit: String,
    @SerialName("kg_per_unit") val kgPerUnit: BigDecimal,
    val version: Int,
    val verified: Boolean,
)

@Serializable
data class CommodityDto(
    @SerialName("commodity_id") val commodityId: String,
    @SerialName("name_en") val nameEn: String,
    @SerialName("name_sw") val nameSw: String,
    @SerialName("default_unit") val defaultUnit: String,
    val grades: List<String>,
    val units: List<UnitDto>,
)

@Serializable
data class ReportRequest(
    @SerialName("report_id") val reportId: String,
    @SerialName("reported_at_ms") val reportedAtMs: Long,
    @SerialName("market_id") val marketId: String,
    @SerialName("commodity_id") val commodityId: String,
    val grade: String = Limits.DEFAULT_GRADE,
    val unit: String,
    val quantity: BigDecimal,
    @SerialName("price_kes") val priceKes: Int,
)

@Serializable
data class ReportDto(
    @SerialName("report_id") val reportId: String,
    @SerialName("reported_at_ms") val reportedAtMs: Long,
    @SerialName("received_at_ms") val receivedAtMs: Long? = null,
    @SerialName("market_id") val marketId: String,
    @SerialName("commodity_id") val commodityId: String,
    val grade: String,
    val unit: String,
    val quantity: BigDecimal,
    @SerialName("price_kes") val priceKes: Int,
    @SerialName("kg_per_unit") val kgPerUnit: BigDecimal? = null,
    @SerialName("conversion_version") val conversionVersion: Int? = null,
    @SerialName("price_per_kg_kes") val pricePerKgKes: BigDecimal? = null,
)

@Serializable
data class MarketPriceDto(
    @SerialName("commodity_id") val commodityId: String,
    @SerialName("commodity_name_en") val commodityNameEn: String,
    @SerialName("commodity_name_sw") val commodityNameSw: String,
    val grade: String,
    @SerialName("median_today_per_kg") val medianTodayPerKg: BigDecimal?,
    @SerialName("last_price_per_kg") val lastPricePerKg: BigDecimal?,
    @SerialName("reports_today") val reportsToday: Int,
    @SerialName("updated_at_ms") val updatedAtMs: Long,
)

@Serializable
data class CommodityPriceDto(
    @SerialName("market_id") val marketId: String,
    @SerialName("market_name") val marketName: String,
    val county: String,
    val grade: String,
    @SerialName("median_today_per_kg") val medianTodayPerKg: BigDecimal?,
    @SerialName("last_price_per_kg") val lastPricePerKg: BigDecimal?,
    @SerialName("reports_today") val reportsToday: Int,
    @SerialName("updated_at_ms") val updatedAtMs: Long,
)

@Serializable
data class TrendPointDto(
    val day: String,
    val grade: String,
    @SerialName("min_per_kg") val minPerKg: BigDecimal,
    @SerialName("median_per_kg") val medianPerKg: BigDecimal,
    @SerialName("max_per_kg") val maxPerKg: BigDecimal,
    @SerialName("report_count") val reportCount: Int,
)

@Serializable
data class TrendResponse(
    @SerialName("commodity_id") val commodityId: String,
    @SerialName("market_id") val marketId: String,
    val days: Int,
    val points: List<TrendPointDto>,
)

// ---- auth / me ----
@Serializable data class OtpRequest(val phone: String)
@Serializable data class OtpVerifyRequest(val phone: String, val code: String)
@Serializable data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class AuthTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Int,
)

@Serializable
data class AccessTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Int,
)

@Serializable data class ConsentRequest(@SerialName("consent_version") val consentVersion: Int)

@Serializable
data class UserDto(
    @SerialName("user_id") val userId: String,
    @SerialName("phone_masked") val phoneMasked: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("consent_version") val consentVersion: Int? = null,
    @SerialName("current_consent_version") val currentConsentVersion: Int,
)

@Serializable
data class FlagRequest(val reason: String = "wrong_price")

@Serializable
data class RebuildResult(val from: String, val to: String, val days: Int, @SerialName("pairs_processed") val pairsProcessed: Int)
