package ke.sokobei.demo

import ke.sokobei.Services
import ke.sokobei.domain.Commodity
import ke.sokobei.domain.Market
import ke.sokobei.domain.UnitFactor
import ke.sokobei.shared.ReportRequest
import ke.sokobei.time.BusinessCalendar
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Random
import java.util.UUID
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

@Serializable
data class DemoEvent(
    @SerialName("user_index") val userIndex: Int,
    val report: ReportRequest,
    val outlier: Boolean,
)

class DemoScenario(
    private val seed: Long,
    private val markets: List<Market>,
    private val commodities: List<Commodity>,
    private val factors: Map<String, List<UnitFactor>>,
) {
    private val big = setOf("wakulima", "marikiti", "gikomba", "kongowea")
    private val medium = setOf("muthurwa", "githurai", "eldoret-main", "kibuye", "nakuru-main")
    private val openingCount = markets.size * commodities.size

    fun day(day: LocalDate): List<DemoEvent> {
        val events = mutableListOf<DemoEvent>()
        for (market in markets) {
            val volume = when (market.marketId) { in big -> 7.0; in medium -> 2.6; else -> 1.2 }
            if (market.marketId !in big && market.marketId !in medium && random("closed", day, market.marketId).nextDouble() < .18) continue
            for (commodity in commodities) {
                val rng = random("volume", day, market.marketId, commodity.commodityId)
                val weekday = listOf(1.12, .98, 1.08, .98, 1.12, 1.18, .72)[day.dayOfWeek.value - 1]
                val count = poisson(rng, volume * weekday).coerceAtMost(24)
                repeat(count) { ordinal -> events += event(day, market, commodity, ordinal, false) }
            }
        }
        return events.sortedWith(compareBy<DemoEvent> { it.report.reportedAtMs }.thenBy { it.report.reportId })
            .mapIndexed { index, event -> event.copy(userIndex = (index + openingCount + userOffset(day)) % USER_COUNT) }
    }

    fun opening(day: LocalDate): List<DemoEvent> = markets.flatMap { market ->
        commodities.map { commodity -> event(day, market, commodity, 0, true) }
    }.sortedWith(compareBy<DemoEvent> { it.report.reportedAtMs }.thenBy { it.report.reportId })
        .mapIndexed { index, event -> event.copy(userIndex = (index + userOffset(day)) % USER_COUNT) }

    private fun userOffset(day: LocalDate) = random("users", day).nextInt(USER_COUNT)

    private fun event(day: LocalDate, market: Market, commodity: Commodity, ordinal: Int, opening: Boolean): DemoEvent {
        val key = listOf("soko-large-demo-v1", seed, if (opening) "opening" else "trade", day, market.marketId, commodity.commodityId, ordinal).joinToString("|")
        val rng = random(key)
        val units = factors.getValue(commodity.commodityId).sortedBy { it.unit }
        val unit = units[rng.nextInt(units.size)]
        val quantity = when (unit.unit) {
            "kg" -> listOf("1", "2", "5", "10", "20")[rng.nextInt(5)]
            "piece", "bunch" -> listOf("5", "10", "20")[rng.nextInt(3)]
            else -> listOf("0.5", "1", "1", "2", "3")[rng.nextInt(5)]
        }.toBigDecimal()
        val grade = if (opening || ordinal == 0 || rng.nextDouble() < .65) "standard" else commodity.grades[rng.nextInt(commodity.grades.size)]
        val outlier = !opening && rng.nextDouble() < .01
        val jitter = if (opening) 1.0 else .86 + (rng.nextDouble() + rng.nextDouble() + rng.nextDouble()) * .0933333333
        val gradeFactor = when (grade) { "grade_a" -> 1.16; "grade_c" -> .82; else -> 1.0 }
        val perKg = level(day, market.marketId, commodity.commodityId) * jitter * gradeFactor * if (outlier) 10.0 else 1.0
        val price = BigDecimal.valueOf(perKg).multiply(quantity).multiply(unit.kgPerUnit).setScale(0, RoundingMode.HALF_UP).intValueExact().coerceAtLeast(1)
        val seconds = if (opening) 1L + rng.nextInt(59) else {
            val hour = when (rng.nextInt(10)) { 0 -> 1 + rng.nextInt(4); in 1..7 -> 5 + rng.nextInt(9); else -> 14 + rng.nextInt(6) }
            hour * 3600L + rng.nextInt(3600)
        }
        val at = day.atStartOfDay(BusinessCalendar.EAT).toInstant().plusSeconds(seconds)
        return DemoEvent(0, ReportRequest(UUID.nameUUIDFromBytes(key.toByteArray()).toString(), at.toEpochMilli(),
            market.marketId, commodity.commodityId, grade, unit.unit, quantity, price), outlier)
    }

    fun level(day: LocalDate, market: String, commodity: String): Double {
        val base = BASES.getValue(commodity)
        val index = day.toEpochDay().toDouble()
        val phase = random("phase", commodity).nextDouble() * 2 * PI
        val seasonal = 1 + .09 * sin(index * 2 * PI / 110 + phase) + .05 * sin(index * 2 * PI / 365 + phase)
        val marketFactor = when (market) {
            "kongowea" -> if (commodity in setOf("mangoes", "bananas", "watermelons")) .94 else 1.19
            "kitale-municipal", "eldoret-main" -> if (commodity in setOf("maize", "dry_beans", "irish_potatoes")) .84 else .96
            "daraja-mbili" -> if (commodity in setOf("bananas", "avocados", "sweet_potatoes")) .79 else .94
            "karatina", "gakoromone" -> if (commodity in setOf("avocados", "green_grams", "spinach", "sukuma_wiki")) .86 else .97
            "machakos-main" -> if (commodity == "green_grams") .83 else 1.04
            "wakulima" -> 1.0
            "marikiti", "gikomba" -> 1.05
            "muthurwa", "kangemi", "githurai" -> 1.10
            "nakuru-main" -> .92
            else -> .98
        }
        val cycle = Math.floorMod(day.toEpochDay() + seed, 90L).toInt()
        val shock = when {
            commodity == "tomatoes" && cycle in 28..34 -> 1.60
            commodity == "onions" && cycle in 29..35 -> 1.35
            commodity == "mangoes" && cycle in 60..66 -> .73
            else -> 1.0
        }
        val weekday = if (day.dayOfWeek.value == 6) 1.025 else if (day.dayOfWeek.value == 1) .98 else 1.0
        return base * marketFactor * seasonal * shock * weekday
    }

    private fun random(vararg parts: Any): Random {
        val bytes = MessageDigest.getInstance("SHA-256").digest((seed.toString() + "|" + parts.joinToString("|")).toByteArray())
        return Random(ByteBuffer.wrap(bytes).long)
    }

    private fun poisson(random: Random, mean: Double): Int {
        val limit = exp(-mean)
        var product = 1.0
        var count = 0
        do { count++; product *= random.nextDouble() } while (product > limit)
        return count - 1
    }

    companion object {
        const val USER_COUNT = 40
        val PHONES = (0 until USER_COUNT).map { "07000020%02d".format(it) }
        val BASES = mapOf(
            "maize" to 55.0, "dry_beans" to 140.0, "tomatoes" to 85.0, "onions" to 105.0,
            "irish_potatoes" to 65.0, "cabbage" to 35.0, "sukuma_wiki" to 40.0, "bananas" to 70.0,
            "rice" to 160.0, "carrots" to 60.0, "avocados" to 110.0, "mangoes" to 80.0,
            "green_grams" to 150.0, "sweet_potatoes" to 70.0, "garlic" to 380.0, "ginger" to 300.0,
            "watermelons" to 45.0, "spinach" to 55.0,
        )

        suspend fun load(s: Services, seed: Long): DemoScenario {
            DemoCli.requireDev(s.config)
            val markets = s.markets.all().sortedBy { it.marketId }
            val commodities = s.commodities.all().sortedBy { it.commodityId }
            check(commodities.all { it.commodityId in BASES }) { "Catalog contains a commodity without a demo price profile" }
            return DemoScenario(seed, markets, commodities, commodities.associate { it.commodityId to s.units.latestForCommodity(it.commodityId) })
        }
    }
}
