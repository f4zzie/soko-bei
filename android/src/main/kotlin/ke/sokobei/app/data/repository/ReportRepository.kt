package ke.sokobei.app.data.repository

import ke.sokobei.app.data.db.PendingReportEntity
import ke.sokobei.app.data.db.ReportDao
import ke.sokobei.app.data.db.ReportStatus
import ke.sokobei.app.data.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.util.UUID

data class ReportDraft(
    val marketId: String,
    val marketName: String,
    val commodityId: String,
    val commodityName: String,
    val grade: String,
    val unit: String,
    val quantity: BigDecimal,
    val priceKes: Int,
    val pricePerKg: BigDecimal?,
)

interface ReportRepository {
    fun observeReports(): Flow<List<PendingReportEntity>>

    /** Stores the report locally (status PENDING) and schedules a sync. Never touches the network. */
    suspend fun submit(draft: ReportDraft): String
}

class ReportRepositoryImpl(
    private val dao: ReportDao,
    private val scheduler: SyncScheduler,
    private val clock: Clock,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : ReportRepository {
    override fun observeReports() = dao.observeAll()

    override suspend fun submit(draft: ReportDraft): String {
        val now = clock.nowMs()
        val id = idGenerator()
        dao.insert(
            PendingReportEntity(
                reportId = id,
                reportedAtMs = now,
                marketId = draft.marketId,
                marketName = draft.marketName,
                commodityId = draft.commodityId,
                commodityName = draft.commodityName,
                grade = draft.grade,
                unit = draft.unit,
                quantity = draft.quantity,
                priceKes = draft.priceKes,
                pricePerKg = draft.pricePerKg,
                status = ReportStatus.PENDING,
                updatedAtMs = now,
            ),
        )
        scheduler.enqueueNow()
        return id
    }
}
