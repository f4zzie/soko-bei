package ke.sokobei.service

import ke.sokobei.domain.Phone
import ke.sokobei.repository.FlagRepository
import ke.sokobei.repository.RefreshTokenRepository
import ke.sokobei.repository.ReportLocatorRepository
import ke.sokobei.repository.ReportSourceRepository
import ke.sokobei.repository.ReportsByUserRepository
import ke.sokobei.repository.OtpRepository
import ke.sokobei.repository.UserRepository
import ke.sokobei.shared.UserDto
import ke.sokobei.time.BusinessCalendar
import ke.sokobei.time.TimeUuids
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

class AccountService(
    private val calendar: BusinessCalendar,
    private val consentVersion: Int,
    private val users: UserRepository,
    private val byUser: ReportsByUserRepository,
    private val source: ReportSourceRepository,
    private val locator: ReportLocatorRepository,
    private val refresh: RefreshTokenRepository,
    private val flags: FlagRepository,
    private val otps: OtpRepository,
    /** Seam for crash-recovery tests: runs between the Cassandra and MongoDB phases of [delete]. No-op in production. */
    private val afterCassandraPhase: suspend () -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(AccountService::class.java)

    suspend fun me(userId: UUID): UserDto {
        val u = users.get(userId) ?: throw ApiException.unauthorized("account no longer exists")
        return UserDto(u.userId.toString(), Phone.mask(u.phone), u.displayName, u.consentVersion, consentVersion)
    }

    suspend fun acceptConsent(userId: UUID, version: Int) {
        if (users.get(userId) == null) throw ApiException.unauthorized("account no longer exists")
        if (version != consentVersion) throw ApiException.badRequest("consent_version", "current version is $consentVersion")
        users.acceptConsent(userId, version, calendar.now())
    }

    /**
     * Deletes the account across both stores. Safe to retry at any point:
     *  1. Cassandra first: source reports and locators lose user_id (found via reports_by_user, which is removed
     *     only after its rows are anonymised), then flags are deleted.
     *  2. MongoDB last: refresh tokens, OTP, and finally the user document.
     * The user document is the "still to do" marker, so a retry after a crash in between redoes the idempotent
     * steps. If the user is already gone, deletion has completed and this is a no-op.
     */
    suspend fun delete(userId: UUID) {
        val user = users.get(userId) ?: return
        // A report may be dated up to 14 days before the account existed, so start one year earlier.
        val firstYear = calendar.year(user.createdAt) - 1
        for (year in firstYear..calendar.year(calendar.now())) {
            for (row in byUser.all(userId, year)) {
                val reportedAt = row.getUuid("reported_at")!!
                val month = calendar.monthKey(Instant.ofEpochMilli(TimeUuids.timestampMs(reportedAt)))
                source.clearUser(row.getString("commodity_id")!!, row.getString("market_id")!!, month, reportedAt)
                locator.clearUser(row.getUuid("report_id")!!)
            }
            byUser.deleteYear(userId, year)
        }
        flags.deleteAllForUser(userId)
        afterCassandraPhase()
        refresh.revokeAllForUser(userId)
        otps.delete(user.phone)
        users.delete(userId, user.phone)
        log.info("Account {} deleted", userId)
    }
}
