package ke.sokobei.repository

import com.datastax.oss.driver.api.core.cql.BatchableStatement
import com.datastax.oss.driver.api.core.cql.Row
import ke.sokobei.domain.ReportLocator
import ke.sokobei.domain.StoredReport
import java.time.Instant
import java.util.UUID

/** SOURCE OF TRUTH. Every other price table is derived from this one. */
class ReportSourceRepository(private val db: Db) {
    private val cols = "commodity_id, market_id, month, reported_at, report_id, received_at, user_id, grade, unit, quantity, " +
        "price_kes, kg_per_unit, conversion_version, price_per_kg_kes"
    private val insert = db.prepare("INSERT INTO reports_by_market_commodity ($cols) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
    private val one = db.prepare("SELECT $cols FROM reports_by_market_commodity WHERE commodity_id = ? AND market_id = ? AND month = ? AND reported_at = ?")
    private val slice = db.prepare(
        "SELECT $cols FROM reports_by_market_commodity WHERE commodity_id = ? AND market_id = ? AND month = ? " +
            "AND reported_at >= minTimeuuid(?) AND reported_at < minTimeuuid(?)")
    private val month = db.prepare("SELECT $cols FROM reports_by_market_commodity WHERE commodity_id = ? AND market_id = ? AND month = ?")
    private val clearUser = db.prepare("DELETE user_id FROM reports_by_market_commodity WHERE commodity_id = ? AND market_id = ? AND month = ? AND reported_at = ?")

    fun insertStatement(r: StoredReport): BatchableStatement<*> = insert.bind(
        r.commodityId, r.marketId, r.month, r.reportedAt, r.reportId, r.receivedAt, r.userId, r.grade, r.unit,
        r.quantity, r.priceKes, r.kgPerUnit, r.conversionVersion, r.pricePerKgKes)

    private fun Row.toReport() = StoredReport(
        getString("commodity_id")!!, getString("market_id")!!, getString("month")!!, getUuid("reported_at")!!,
        getUuid("report_id")!!, getInstant("received_at") ?: Instant.EPOCH, getUuid("user_id"), getString("grade")!!,
        getString("unit")!!, getBigDecimal("quantity")!!, getInt("price_kes"), getBigDecimal("kg_per_unit")!!,
        getInt("conversion_version"), getBigDecimal("price_per_kg_kes")!!)

    suspend fun get(commodityId: String, marketId: String, month: String, reportedAt: UUID): StoredReport? =
        db.one(one.bind(commodityId, marketId, month, reportedAt))?.toReport()

    /** Pure clustering-range read of [fromInclusive, toExclusive) inside one month partition, newest first. */
    suspend fun slice(commodityId: String, marketId: String, month: String, fromInclusive: Instant, toExclusive: Instant): List<StoredReport> =
        db.all(slice.bind(commodityId, marketId, month, fromInclusive, toExclusive)).map { it.toReport() }

    suspend fun page(commodityId: String, marketId: String, month: String, limit: Int, token: String?): Pair<List<StoredReport>, String?> =
        db.page(month.let { this.month.bind(commodityId, marketId, it) }, limit, token).let { p -> p.rows.map { it.toReport() } to p.nextToken }

    suspend fun clearUser(commodityId: String, marketId: String, month: String, reportedAt: UUID) {
        db.exec(clearUser.bind(commodityId, marketId, month, reportedAt))
    }
}

class ReportsByUserRepository(private val db: Db) {
    private val insert = db.prepare(
        "INSERT INTO reports_by_user (user_id, year, reported_at, report_id, commodity_id, market_id, grade, unit, quantity, price_kes) VALUES (?,?,?,?,?,?,?,?,?,?)")
    private val forYear = db.prepare(
        "SELECT user_id, year, reported_at, report_id, commodity_id, market_id, grade, unit, quantity, price_kes FROM reports_by_user WHERE user_id = ? AND year = ?")
    private val deletePartition = db.prepare("DELETE FROM reports_by_user WHERE user_id = ? AND year = ?")

    fun insertStatement(r: StoredReport, year: Int): BatchableStatement<*> = insert.bind(
        r.userId, year, r.reportedAt, r.reportId, r.commodityId, r.marketId, r.grade, r.unit, r.quantity, r.priceKes)

    suspend fun page(userId: UUID, year: Int, limit: Int, token: String?): RowPage = db.page(forYear.bind(userId, year), limit, token)
    suspend fun all(userId: UUID, year: Int): List<Row> = db.all(forYear.bind(userId, year))
    suspend fun deleteYear(userId: UUID, year: Int) { db.exec(deletePartition.bind(userId, year)) }
}

class ReportLocatorRepository(private val db: Db) {
    private val insert = db.prepare(
        "INSERT INTO report_locator (report_id, commodity_id, market_id, month, reported_at, user_id, payload_hash) VALUES (?,?,?,?,?,?,?)")
    private val get = db.prepare(
        "SELECT report_id, commodity_id, market_id, month, reported_at, user_id, payload_hash FROM report_locator WHERE report_id = ?")
    private val clearUser = db.prepare("DELETE user_id FROM report_locator WHERE report_id = ?")

    fun insertStatement(l: ReportLocator): BatchableStatement<*> =
        insert.bind(l.reportId, l.commodityId, l.marketId, l.month, l.reportedAt, l.userId, l.payloadHash)

    suspend fun get(reportId: UUID): ReportLocator? = db.one(get.bind(reportId))?.let {
        ReportLocator(it.getUuid("report_id")!!, it.getString("commodity_id")!!, it.getString("market_id")!!, it.getString("month")!!,
            it.getUuid("reported_at")!!, it.getUuid("user_id"), it.getString("payload_hash")!!)
    }

    suspend fun clearUser(reportId: UUID) { db.exec(clearUser.bind(reportId)) }
}
