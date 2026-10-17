package ke.sokobei.repository

import com.datastax.oss.driver.api.core.cql.BatchableStatement
import ke.sokobei.domain.DailyStat
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** DERIVED CACHE of reports_by_market_commodity (today's per-grade aggregates), partitioned by commodity. */
class LatestPriceByCommodityRepository(private val db: Db) {
    data class Row(
        val commodityId: String, val marketId: String, val grade: String, val marketName: String, val county: String,
        val median: BigDecimal?, val last: BigDecimal?, val count: Int, val updatedAt: Instant,
    )

    private val upsert = db.prepare(
        "INSERT INTO latest_price_by_commodity (commodity_id, market_id, grade, market_name, county, median_today_per_kg, last_price_per_kg, reports_today, updated_at) VALUES (?,?,?,?,?,?,?,?,?)")
    private val delete = db.prepare("DELETE FROM latest_price_by_commodity WHERE commodity_id = ? AND market_id = ? AND grade = ?")
    private val forCommodity = db.prepare(
        "SELECT commodity_id, market_id, grade, market_name, county, median_today_per_kg, last_price_per_kg, reports_today, updated_at FROM latest_price_by_commodity WHERE commodity_id = ?")
    private val forMarket = db.prepare(
        "SELECT commodity_id, market_id, grade, market_name, county, median_today_per_kg, last_price_per_kg, reports_today, updated_at FROM latest_price_by_commodity WHERE commodity_id = ? AND market_id = ?")

    fun upsertStatement(r: Row): BatchableStatement<*> = upsert.bind(r.commodityId, r.marketId, r.grade, r.marketName, r.county,
        r.median, r.last, r.count, r.updatedAt)
    fun deleteStatement(commodityId: String, marketId: String, grade: String): BatchableStatement<*> = delete.bind(commodityId, marketId, grade)

    private fun com.datastax.oss.driver.api.core.cql.Row.toRow() = Row(getString("commodity_id")!!, getString("market_id")!!, getString("grade")!!,
        getString("market_name") ?: "", getString("county") ?: "", getBigDecimal("median_today_per_kg"), getBigDecimal("last_price_per_kg"),
        getInt("reports_today"), getInstant("updated_at") ?: Instant.EPOCH)

    suspend fun page(commodityId: String, limit: Int, token: String?): Pair<List<Row>, String?> =
        db.page(forCommodity.bind(commodityId), limit, token).let { p -> p.rows.map { it.toRow() } to p.nextToken }
    suspend fun forPair(commodityId: String, marketId: String): List<Row> = db.all(forMarket.bind(commodityId, marketId)).map { it.toRow() }
}

/** DERIVED CACHE of reports_by_market_commodity, partitioned by market. */
class LatestPriceByMarketRepository(private val db: Db) {
    data class Row(
        val marketId: String, val commodityId: String, val grade: String, val nameEn: String, val nameSw: String,
        val median: BigDecimal?, val last: BigDecimal?, val count: Int, val updatedAt: Instant,
    )

    private val upsert = db.prepare(
        "INSERT INTO latest_price_by_market (market_id, commodity_id, grade, commodity_name_en, commodity_name_sw, median_today_per_kg, last_price_per_kg, reports_today, updated_at) VALUES (?,?,?,?,?,?,?,?,?)")
    private val delete = db.prepare("DELETE FROM latest_price_by_market WHERE market_id = ? AND commodity_id = ? AND grade = ?")
    private val forMarket = db.prepare(
        "SELECT market_id, commodity_id, grade, commodity_name_en, commodity_name_sw, median_today_per_kg, last_price_per_kg, reports_today, updated_at FROM latest_price_by_market WHERE market_id = ?")
    private val forPair = db.prepare(
        "SELECT market_id, commodity_id, grade, commodity_name_en, commodity_name_sw, median_today_per_kg, last_price_per_kg, reports_today, updated_at FROM latest_price_by_market WHERE market_id = ? AND commodity_id = ?")

    fun upsertStatement(r: Row): BatchableStatement<*> = upsert.bind(r.marketId, r.commodityId, r.grade, r.nameEn, r.nameSw,
        r.median, r.last, r.count, r.updatedAt)
    fun deleteStatement(marketId: String, commodityId: String, grade: String): BatchableStatement<*> = delete.bind(marketId, commodityId, grade)

    private fun com.datastax.oss.driver.api.core.cql.Row.toRow() = Row(getString("market_id")!!, getString("commodity_id")!!, getString("grade")!!,
        getString("commodity_name_en") ?: "", getString("commodity_name_sw") ?: "", getBigDecimal("median_today_per_kg"),
        getBigDecimal("last_price_per_kg"), getInt("reports_today"), getInstant("updated_at") ?: Instant.EPOCH)

    suspend fun page(marketId: String, limit: Int, token: String?): Pair<List<Row>, String?> =
        db.page(forMarket.bind(marketId), limit, token).let { p -> p.rows.map { it.toRow() } to p.nextToken }
    suspend fun forPair(marketId: String, commodityId: String): List<Row> = db.all(forPair.bind(marketId, commodityId)).map { it.toRow() }
}

class DailyStatsRepository(private val db: Db) {
    private val upsert = db.prepare(
        "INSERT INTO daily_stats (commodity_id, market_id, year, day, grade, min_per_kg, median_per_kg, max_per_kg, report_count, computed_at) VALUES (?,?,?,?,?,?,?,?,?,?)")
    private val delete = db.prepare("DELETE FROM daily_stats WHERE commodity_id = ? AND market_id = ? AND year = ? AND day = ? AND grade = ?")
    private val forDay = db.prepare("SELECT grade FROM daily_stats WHERE commodity_id = ? AND market_id = ? AND year = ? AND day = ?")
    private val range = db.prepare(
        "SELECT commodity_id, market_id, day, grade, min_per_kg, median_per_kg, max_per_kg, report_count FROM daily_stats WHERE commodity_id = ? AND market_id = ? AND year = ? AND day >= ? AND day <= ?")

    fun upsertStatement(s: DailyStat, computedAt: Instant): BatchableStatement<*> =
        upsert.bind(s.commodityId, s.marketId, s.day.year, s.day, s.grade, s.min, s.median, s.max, s.count, computedAt)
    fun deleteStatement(commodityId: String, marketId: String, day: LocalDate, grade: String): BatchableStatement<*> =
        delete.bind(commodityId, marketId, day.year, day, grade)

    suspend fun gradesForDay(commodityId: String, marketId: String, day: LocalDate): List<String> =
        db.all(forDay.bind(commodityId, marketId, day.year, day)).map { it.getString("grade")!! }

    /** One year partition, [from, to] inclusive; both must be in [year]. */
    suspend fun range(commodityId: String, marketId: String, year: Int, from: LocalDate, to: LocalDate): List<DailyStat> =
        db.all(range.bind(commodityId, marketId, year, from, to)).map {
            DailyStat(it.getString("commodity_id")!!, it.getString("market_id")!!, it.getLocalDate("day")!!, it.getString("grade")!!,
                it.getBigDecimal("min_per_kg")!!, it.getBigDecimal("median_per_kg")!!, it.getBigDecimal("max_per_kg")!!, it.getInt("report_count"))
        }
}

class FlagRepository(private val db: Db) {
    private val get = db.prepare("SELECT reason FROM flags_by_report WHERE report_id = ? AND user_id = ?")
    private val insert = db.prepare("INSERT INTO flags_by_report (report_id, user_id, reason, created_at) VALUES (?,?,?,?)")
    private val insertByUser = db.prepare("INSERT INTO flags_by_user (user_id, report_id, created_at) VALUES (?,?,?)")
    private val count = db.prepare("SELECT user_id FROM flags_by_report WHERE report_id = ?")
    private val forUser = db.prepare("SELECT report_id FROM flags_by_user WHERE user_id = ?")
    private val deleteFlag = db.prepare("DELETE FROM flags_by_report WHERE report_id = ? AND user_id = ?")
    private val deleteUserPartition = db.prepare("DELETE FROM flags_by_user WHERE user_id = ?")

    /** One flag per user per report; repeating it is a no-op (returns false). */
    suspend fun flag(reportId: UUID, userId: UUID, reason: String, now: Instant): Boolean {
        if (db.one(get.bind(reportId, userId)) != null) return false
        db.exec(insert.bind(reportId, userId, reason, now))
        db.exec(insertByUser.bind(userId, reportId, now))
        return true
    }

    suspend fun flagCount(reportId: UUID): Int = db.all(count.bind(reportId)).size

    suspend fun deleteAllForUser(userId: UUID) {
        db.all(forUser.bind(userId)).forEach { db.exec(deleteFlag.bind(it.getUuid("report_id"), userId)) }
        db.exec(deleteUserPartition.bind(userId))
    }
}

class JobStateRepository(private val db: Db) {
    data class State(val jobName: String, val lastSuccessAt: Instant?, val lastProcessedDay: LocalDate?, val lastError: String?)

    private val success = db.prepare("INSERT INTO job_state (job_name, last_success_at, last_processed_day, last_error) VALUES (?, ?, ?, null)")
    private val failure = db.prepare("UPDATE job_state SET last_error = ? WHERE job_name = ?")
    private val get = db.prepare("SELECT job_name, last_success_at, last_processed_day, last_error FROM job_state WHERE job_name = ?")

    suspend fun recordSuccess(job: String, at: Instant, day: LocalDate?) { db.exec(success.bind(job, at, day)) }
    suspend fun recordFailure(job: String, error: String) { db.exec(failure.bind(error.take(500), job)) }
    suspend fun get(job: String): State? = db.one(get.bind(job))?.let {
        State(it.getString("job_name")!!, it.getInstant("last_success_at"), it.getLocalDate("last_processed_day"), it.getString("last_error"))
    }
}
