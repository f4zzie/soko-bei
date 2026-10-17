package ke.sokobei.app.data.sync

import ke.sokobei.app.data.api.ApiResult
import ke.sokobei.app.data.api.SokoBeiApi
import ke.sokobei.app.data.api.TokenStore
import ke.sokobei.app.data.db.PendingReportEntity
import ke.sokobei.app.data.db.ReportDao
import ke.sokobei.app.data.repository.AppSettings
import ke.sokobei.app.data.repository.Clock
import ke.sokobei.shared.ErrorCodes
import ke.sokobei.shared.Limits
import ke.sokobei.shared.ReportRequest
import kotlinx.coroutines.flow.first

enum class SyncOutcome {
    /** Nothing more to do now (everything sent, or the rest is waiting for login/consent). */
    DONE,
    /** Transient failure (offline, 5xx, 429): WorkManager should back off and retry. */
    RETRY,
}

/**
 * Sends PENDING reports to the backend. Every request re-uses the stored report_id and
 * reported_at_ms, so replays after a lost response are idempotent (server answers 200).
 */
class ReportSyncer(
    private val api: SokoBeiApi,
    private val dao: ReportDao,
    private val tokens: TokenStore,
    private val settings: AppSettings,
    private val clock: Clock,
) {
    suspend fun syncAll(): SyncOutcome {
        val now = clock.nowMs()
        // Reports older than the server's accepted window are never sent.
        dao.expireOlderThan(now - Limits.DEFAULT_REPORT_MAX_AGE_DAYS * DAY_MS, now)

        for (report in dao.pending()) {
            var result = api.submitReport(report.toRequest())

            if (result.isConsentRequired() && acceptConsentOnServer()) {
                result = api.submitReport(report.toRequest())
            }

            when (result) {
                is ApiResult.Success -> dao.markSynced(report.reportId, clock.nowMs())
                is ApiResult.NetworkError -> {
                    dao.markAttempted(report.reportId, clock.nowMs())
                    return SyncOutcome.RETRY
                }
                is ApiResult.HttpError -> when {
                    // Still unauthorised after Ktor's single refresh attempt.
                    result.status == 401 ->
                        // No session left -> wait for the user to log in (login triggers a new sync).
                        return if (tokens.refresh() == null) SyncOutcome.DONE else SyncOutcome.RETRY
                    // Not rejected: the report becomes valid once consent is recorded.
                    result.isConsentRequired() -> return SyncOutcome.DONE
                    result.status >= 500 || result.status == 408 || result.status == 429 -> {
                        dao.markAttempted(report.reportId, clock.nowMs())
                        return SyncOutcome.RETRY
                    }
                    // 409 (same id, different payload) and every other 4xx: permanent.
                    else -> dao.markRejected(
                        report.reportId,
                        result.message.ifBlank { result.code },
                        clock.nowMs(),
                    )
                }
            }
        }
        return SyncOutcome.DONE
    }

    private fun ApiResult<*>.isConsentRequired() =
        this is ApiResult.HttpError && status == 403 && code == ErrorCodes.CONSENT_REQUIRED

    /** Only possible if the user already agreed on the welcome screen and we hold a session. */
    private suspend fun acceptConsentOnServer(): Boolean {
        if (settings.consentVersion.first() == null || tokens.refresh() == null) return false
        return api.postConsent(Limits.CURRENT_CONSENT_VERSION) is ApiResult.Success
    }

    private fun PendingReportEntity.toRequest() = ReportRequest(
        reportId = reportId,
        reportedAtMs = reportedAtMs,
        marketId = marketId,
        commodityId = commodityId,
        grade = grade,
        unit = unit,
        quantity = quantity,
        priceKes = priceKes,
    )

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
