package ke.sokobei.service

import com.datastax.oss.driver.api.core.cql.BatchType
import ke.sokobei.domain.DailyStat
import ke.sokobei.domain.GradeStats
import ke.sokobei.domain.Median
import ke.sokobei.domain.StoredReport
import ke.sokobei.repository.CommodityRepository
import ke.sokobei.repository.DailyStatsRepository
import ke.sokobei.repository.Db
import ke.sokobei.repository.LatestPriceByCommodityRepository
import ke.sokobei.repository.LatestPriceByMarketRepository
import ke.sokobei.repository.MarketRepository
import ke.sokobei.repository.ReportSourceRepository
import ke.sokobei.time.BusinessCalendar
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Recomputes DERIVED tables from reports_by_market_commodity (the source of truth).
 * Every operation reads the source and overwrites; nothing is ever incremented.
 */
class PriceRecomputeService(
    private val db: Db,
    private val calendar: BusinessCalendar,
    private val reports: ReportSourceRepository,
    private val markets: MarketRepository,
    private val commodities: CommodityRepository,
    private val latestByCommodity: LatestPriceByCommodityRepository,
    private val latestByMarket: LatestPriceByMarketRepository,
    private val dailyStats: DailyStatsRepository,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(c: String, m: String) = locks.computeIfAbsent("$c|$m") { Mutex() }

    suspend fun readDay(commodityId: String, marketId: String, day: LocalDate): List<StoredReport> =
        reports.slice(commodityId, marketId, calendar.monthKey(day), calendar.startOfDay(day), calendar.startOfNextDay(day))

    /** Recomputes today's latest rows for one (commodity, market) from today's slice (all grades). */
    suspend fun recomputeLatest(commodityId: String, marketId: String) {
        val market = markets.get(marketId) ?: return
        val commodity = commodities.get(commodityId) ?: return
        lock(commodityId, marketId).withLock {
            val today = calendar.today()
            val stats = statsByGrade(readDay(commodityId, marketId, today))
            val now = calendar.now()
            val existing = (latestByCommodity.forPair(commodityId, marketId).map { it.grade } +
                latestByMarket.forPair(marketId, commodityId).map { it.grade }).toSet()
            val statements = buildList {
                for (s in stats) {
                    add(latestByCommodity.upsertStatement(LatestPriceByCommodityRepository.Row(commodityId, marketId, s.grade,
                        market.name, market.county, s.median, s.last, s.count, now)))
                    add(latestByMarket.upsertStatement(LatestPriceByMarketRepository.Row(marketId, commodityId, s.grade,
                        commodity.nameEn, commodity.nameSw, s.median, s.last, s.count, now)))
                }
                for (g in existing - stats.map { it.grade }.toSet()) {
                    add(latestByCommodity.deleteStatement(commodityId, marketId, g))
                    add(latestByMarket.deleteStatement(marketId, commodityId, g))
                }
            }
            db.batch(BatchType.LOGGED, statements)
        }
    }

    /** Recomputes daily_stats for one (commodity, market, day); removes grades that no longer have reports. */
    suspend fun recomputeDaily(commodityId: String, marketId: String, day: LocalDate) {
        lock(commodityId, marketId).withLock {
            val stats = statsByGrade(readDay(commodityId, marketId, day))
            val now = calendar.now()
            val stale = dailyStats.gradesForDay(commodityId, marketId, day).toSet() - stats.map { it.grade }.toSet()
            val statements = stats.map { s ->
                dailyStats.upsertStatement(DailyStat(commodityId, marketId, day, s.grade, s.min, s.median, s.max, s.count), now)
            } + stale.map { dailyStats.deleteStatement(commodityId, marketId, day, it) }
            db.batch(BatchType.LOGGED, statements)
        }
    }

    private suspend fun pairs(): List<Pair<String, String>> {
        val ms = markets.all().map { it.marketId }
        return commodities.all().flatMap { c -> ms.map { m -> c.commodityId to m } }
    }

    /** Rebuilds both latest tables for today for every pair; also clears stale rows left from earlier days. */
    suspend fun rebuildLatestToday(): Int = pairs().onEach { (c, m) -> recomputeLatest(c, m) }.size

    /** Rebuilds daily_stats for every pair for each day in [from, to]. Returns pairs processed. */
    suspend fun rebuildDaily(from: LocalDate, to: LocalDate): Int {
        val ps = pairs()
        var day = from
        while (!day.isAfter(to)) {
            for ((c, m) in ps) recomputeDaily(c, m, day)
            day = day.plusDays(1)
        }
        return ps.size
    }

    companion object {
        /** Pure: groups by grade (never mixing grades), median/min/max/last/count. [rows] need not be sorted. */
        fun statsByGrade(rows: List<StoredReport>): List<GradeStats> =
            rows.groupBy { it.grade }.toSortedMap().map { (grade, rs) ->
                val prices = rs.map { it.pricePerKgKes }
                GradeStats(grade, prices.min(), Median.of(prices)!!, prices.max(),
                    rs.maxBy { it.reportedAt.timestamp() }.pricePerKgKes, rs.size)
            }
    }
}
