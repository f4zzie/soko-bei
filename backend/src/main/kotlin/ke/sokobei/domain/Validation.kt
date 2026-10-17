package ke.sokobei.domain

import ke.sokobei.shared.Limits
import ke.sokobei.shared.ReportRequest
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ValidationException(val details: Map<String, String>, message: String = "Validation failed") :
    RuntimeException(message)

data class ValidatedReport(
    val reportId: UUID,
    val reportedAtMs: Long,
    val marketId: String,
    val commodityId: String,
    val grade: String,
    val unit: String,
    val quantity: BigDecimal,
    val priceKes: Int,
    val kgPerUnit: BigDecimal,
    val conversionVersion: Int,
    val pricePerKgKes: BigDecimal,
)

class ReportValidator(
    private val maxAgeDays: Int = Limits.DEFAULT_REPORT_MAX_AGE_DAYS,
    private val minPricePerKg: BigDecimal = BigDecimal(1),
    private val maxPricePerKg: BigDecimal = BigDecimal(100_000),
) {
    /**
     * Pure validation. Catalog lookups are passed in: [market]/[commodity]/[factor] are null when missing.
     * [factor] must be the latest conversion version for (commodity, unit).
     */
    fun validate(
        req: ReportRequest,
        market: Market?,
        commodity: Commodity?,
        factor: UnitFactor?,
        now: Instant,
    ): ValidatedReport {
        val errors = linkedMapOf<String, String>()

        val reportId = runCatching { UUID.fromString(req.reportId) }.getOrNull()
        if (reportId == null || reportId.toString() != req.reportId.lowercase()) errors["report_id"] = "must be a UUID"

        if (market == null) errors["market_id"] = "unknown market"
        if (commodity == null) errors["commodity_id"] = "unknown commodity"
        else {
            if (req.grade !in commodity.grades) errors["grade"] = "not allowed for ${commodity.commodityId}; allowed: ${commodity.grades}"
            if (factor == null) errors["unit"] = "no conversion for ${commodity.commodityId} in unit '${req.unit}'"
        }

        if (req.quantity.signum() <= 0) errors["quantity"] = "must be greater than 0"
        else if (req.quantity > MAX_QUANTITY) errors["quantity"] = "must be at most $MAX_QUANTITY"
        if (req.priceKes <= 0) errors["price_kes"] = "must be greater than 0"

        val ms = req.reportedAtMs
        if (ms > now.plus(Duration.ofMinutes(Limits.FUTURE_SKEW_MINUTES)).toEpochMilli()) {
            errors["reported_at_ms"] = "is more than ${Limits.FUTURE_SKEW_MINUTES} minutes in the future"
        } else if (ms < now.minus(Duration.ofDays(maxAgeDays.toLong())).toEpochMilli()) {
            errors["reported_at_ms"] = "is older than $maxAgeDays days"
        }

        var perKg: BigDecimal? = null
        if (errors.isEmpty() && factor != null) {
            perKg = UnitConversion.pricePerKg(req.priceKes, req.quantity, factor.kgPerUnit)
            if (perKg < minPricePerKg || perKg > maxPricePerKg) {
                errors["price_kes"] = "implied price per kg $perKg is outside the plausible range $minPricePerKg..$maxPricePerKg"
            }
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)

        return ValidatedReport(
            reportId = reportId!!, reportedAtMs = ms, marketId = req.marketId, commodityId = req.commodityId,
            grade = req.grade, unit = req.unit, quantity = req.quantity, priceKes = req.priceKes,
            kgPerUnit = factor!!.kgPerUnit, conversionVersion = factor.version, pricePerKgKes = perKg!!,
        )
    }

    companion object {
        val MAX_QUANTITY = BigDecimal(100_000)
    }
}
