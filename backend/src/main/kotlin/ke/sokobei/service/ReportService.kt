package ke.sokobei.service

import com.datastax.oss.driver.api.core.cql.BatchType
import ke.sokobei.domain.ReportLocator
import ke.sokobei.domain.ReportValidator
import ke.sokobei.domain.StoredReport
import ke.sokobei.domain.ValidationException
import ke.sokobei.repository.CommodityRepository
import ke.sokobei.repository.CommodityUnitRepository
import ke.sokobei.repository.Db
import ke.sokobei.repository.MarketRepository
import ke.sokobei.repository.ReportLocatorRepository
import ke.sokobei.repository.ReportSourceRepository
import ke.sokobei.repository.ReportsByUserRepository
import ke.sokobei.repository.UserRepository
import ke.sokobei.shared.ErrorCodes
import ke.sokobei.shared.ReportRequest
import ke.sokobei.time.BusinessCalendar
import ke.sokobei.time.TimeUuids
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class SubmitResult(val report: StoredReport, val created: Boolean)

class ReportService(
    private val db: Db,
    private val calendar: BusinessCalendar,
    private val validator: ReportValidator,
    private val consentVersion: Int,
    private val users: UserRepository,
    private val markets: MarketRepository,
    private val commodities: CommodityRepository,
    private val units: CommodityUnitRepository,
    private val source: ReportSourceRepository,
    private val byUser: ReportsByUserRepository,
    private val locator: ReportLocatorRepository,
    private val recompute: PriceRecomputeService,
    private val onSubmitted: (created: Boolean) -> Unit = {},
) {
    suspend fun submit(userId: UUID, req: ReportRequest): SubmitResult {
        val user = users.get(userId) ?: throw ApiException.unauthorized("account no longer exists")
        if ((user.consentVersion ?: 0) < consentVersion) {
            throw ApiException(403, ErrorCodes.CONSENT_REQUIRED, "Accept the current consent version before reporting prices")
        }

        // Idempotency first: a retry of an already-stored report must succeed even if it has since aged past the limit.
        val reportId = runCatching { UUID.fromString(req.reportId) }.getOrNull()
            ?: throw ValidationException(mapOf("report_id" to "must be a UUID"))
        val existing = locator.get(reportId)
        if (existing != null) {
            if (existing.payloadHash != payloadHash(req)) {
                throw ApiException(409, ErrorCodes.CONFLICT, "report_id already used with a different payload")
            }
            val stored = source.get(existing.commodityId, existing.marketId, existing.month, existing.reportedAt)
            if (stored != null) {
                onSubmitted(false)
                return SubmitResult(stored, created = false)
            }
            // Locator without a source row (should not happen with a logged batch): fall through and re-write.
        }

        val now = calendar.now()
        val commodity = commodities.get(req.commodityId)
        val v = validator.validate(
            req, markets.get(req.marketId), commodity,
            commodity?.let { units.latest(it.commodityId, req.unit) }, now,
        )

        val reportedAt = Instant.ofEpochMilli(v.reportedAtMs)
        val stored = StoredReport(
            commodityId = v.commodityId, marketId = v.marketId, month = calendar.monthKey(reportedAt),
            reportedAt = TimeUuids.forReport(v.reportId, v.reportedAtMs), reportId = v.reportId, receivedAt = now,
            userId = userId, grade = v.grade, unit = v.unit, quantity = v.quantity, priceKes = v.priceKes,
            kgPerUnit = v.kgPerUnit, conversionVersion = v.conversionVersion, pricePerKgKes = v.pricePerKgKes,
        )
        db.batch(
            BatchType.LOGGED,
            listOf(
                source.insertStatement(stored),
                byUser.insertStatement(stored, calendar.year(reportedAt)),
                locator.insertStatement(ReportLocator(v.reportId, v.commodityId, v.marketId, stored.month, stored.reportedAt, userId, payloadHash(req))),
            ),
        )
        if (calendar.dayOf(reportedAt) == calendar.today()) recompute.recomputeLatest(v.commodityId, v.marketId)
        onSubmitted(true)
        return SubmitResult(stored, created = true)
    }

    companion object {
        /** Hash of the client-controlled fields only; server-derived values (conversion version, ...) are excluded. */
        fun payloadHash(r: ReportRequest): String {
            val canonical = listOf(r.reportId.lowercase(), r.reportedAtMs, r.marketId, r.commodityId, r.grade, r.unit,
                r.quantity.stripTrailingZeros().toPlainString(), r.priceKes).joinToString("|")
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
