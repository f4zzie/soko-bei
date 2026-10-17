package ke.sokobei.service

import ke.sokobei.repository.DailyStatsRepository
import ke.sokobei.shared.TrendPointDto
import ke.sokobei.shared.TrendResponse
import ke.sokobei.time.BusinessCalendar
import java.time.LocalDate

class TrendService(private val calendar: BusinessCalendar, private val stats: DailyStatsRepository) {
    /** Last [days] days ending today (EAT), stitched across year partitions, ascending by day. */
    suspend fun trend(commodityId: String, marketId: String, days: Int, grade: String): TrendResponse {
        val to = calendar.today()
        val from = to.minusDays(days.toLong() - 1)
        val points = (from.year..to.year).flatMap { year ->
            val lo = maxOf(from, LocalDate.of(year, 1, 1))
            val hi = minOf(to, LocalDate.of(year, 12, 31))
            stats.range(commodityId, marketId, year, lo, hi)
        }.filter { it.grade == grade }.sortedBy { it.day }
        return TrendResponse(commodityId, marketId, days,
            points.map { TrendPointDto(it.day.toString(), it.grade, it.min, it.median, it.max, it.count) })
    }
}
