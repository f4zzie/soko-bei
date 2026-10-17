package ke.sokobei.service

import ke.sokobei.repository.FlagRepository
import ke.sokobei.repository.ReportLocatorRepository
import ke.sokobei.time.BusinessCalendar
import java.util.UUID

class FlagService(
    private val calendar: BusinessCalendar,
    private val locator: ReportLocatorRepository,
    private val flags: FlagRepository,
) {
    /** @return true if a new flag was recorded, false if this user had already flagged the report. */
    suspend fun flag(userId: UUID, reportId: UUID, reason: String): Boolean {
        locator.get(reportId) ?: throw ApiException.notFound("report")
        return flags.flag(reportId, userId, reason.take(100), calendar.now())
    }
}
