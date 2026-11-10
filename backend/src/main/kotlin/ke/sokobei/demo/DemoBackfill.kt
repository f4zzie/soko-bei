package ke.sokobei.demo

import ke.sokobei.Services
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

@Serializable
data class BackfillSummary(
    val seed: Long,
    val from: String,
    val through: String,
    val created: Int,
    val existing: Int,
    val flagged: Int,
    val outliers: Int,
    val byMarket: Map<String, Int>,
    val byCommodity: Map<String, Int>,
    val byUnit: Map<String, Int>,
    val byGrade: Map<String, Int>,
    val writeSeconds: Double,
    val rebuildSeconds: Double,
)

class DemoBackfill(private val s: Services, private val progress: (String) -> Unit = ::println) {
    suspend fun load(
        seed: Long,
        from: LocalDate,
        through: LocalDate,
        marketIds: Set<String>? = null,
        concurrency: Int = 2,
        batchSize: Int = 64,
    ): BackfillSummary {
        DemoCli.requireDev(s.config)
        require(concurrency in 1..4 && batchSize in 1..128) { "Use concurrency 1..4 and batch size 1..128" }
        require(!from.isAfter(through) && through.isBefore(s.calendar.today())) { "Backfill writes past EAT dates only; submit today's reports through the API" }
        require(ChronoUnit.DAYS.between(from, s.calendar.today()) <= 365) { "Demo history is limited to 365 days" }
        val allMarkets = s.markets.all().map { it.marketId }.toSet()
        require(marketIds == null || (marketIds.isNotEmpty() && allMarkets.containsAll(marketIds))) { "Unknown or empty market selection" }
        val users = DemoUsers.ensure(s)
        val scenario = DemoScenario.load(s, seed)
        val semaphore = Semaphore(concurrency)
        var created = 0
        var existing = 0
        var flagged = 0
        var outliers = 0
        val byMarket = sortedMapOf<String, Int>()
        val byCommodity = sortedMapOf<String, Int>()
        val byUnit = sortedMapOf<String, Int>()
        val byGrade = sortedMapOf<String, Int>()
        val started = System.nanoTime()
        var day = from
        while (!day.isAfter(through)) {
            val events = scenario.day(day).filter { marketIds == null || it.report.marketId in marketIds }
            for (batch in events.chunked(batchSize)) {
                val results = coroutineScope {
                    batch.map { event -> async {
                        semaphore.withPermit {
                            val result = s.reportService.submit(users[event.userIndex], event.report)
                            check(result.report.userId == users[event.userIndex]) { "A deterministic report ID belongs to a different user" }
                            val shouldFlag = event.outlier && (UUID.fromString(event.report.reportId).leastSignificantBits and 3L) == 0L
                            val newFlag = shouldFlag && s.flags.flag(users[(event.userIndex + 17) % users.size], result.report.reportId,
                                "generated_demo_extra_zero_check_price")
                            result.created to newFlag
                        }
                    } }.awaitAll()
                }
                created += results.count { it.first }
                existing += results.count { !it.first }
                flagged += results.count { it.second }
                for (event in batch) {
                    if (event.outlier) outliers++
                    listOf(byMarket to event.report.marketId, byCommodity to event.report.commodityId,
                        byUnit to event.report.unit, byGrade to event.report.grade).forEach { (map, key) -> map[key] = (map[key] ?: 0) + 1 }
                }
            }
            progress("Demo backfill $day: ${events.size} reports; created=$created existing=$existing flags=$flagged")
            day = day.plusDays(1)
        }
        val writeSeconds = (System.nanoTime() - started) / 1e9
        val rebuildStarted = System.nanoTime()
        day = from
        while (!day.isAfter(through)) {
            s.recompute.rebuildDaily(day, day)
            if (ChronoUnit.DAYS.between(from, day) % 7L == 0L || day == through) progress("Demo aggregate rebuild through $day")
            day = day.plusDays(1)
        }
        s.recompute.rebuildLatestToday()
        return BackfillSummary(seed, from.toString(), through.toString(), created, existing, flagged, outliers,
            byMarket, byCommodity, byUnit, byGrade, writeSeconds, (System.nanoTime() - rebuildStarted) / 1e9)
    }
}
