package ke.sokobei.demo

import ke.sokobei.Services
import ke.sokobei.domain.Commodity
import ke.sokobei.domain.Market
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class DemoCommodity(val commodity: Commodity, val factors: Map<String, BigDecimal>)

object DemoCatalog {
    val markets = listOf(
        Market("gikomba", "Gikomba Market", "Nairobi", -1.2834, 36.8428),
        Market("muthurwa", "Muthurwa Market", "Nairobi", -1.2908, 36.8369),
        Market("kangemi", "Kangemi Market", "Nairobi", -1.2661, 36.7479),
        Market("githurai", "Githurai 45 Market", "Kiambu", -1.2047, 36.9176),
        Market("karatina", "Karatina Market", "Nyeri", -0.4840, 37.1271),
        Market("daraja-mbili", "Daraja Mbili Market", "Kisii", -0.6773, 34.7660),
        Market("eldoret-main", "Eldoret Main Market", "Uasin Gishu", 0.5167, 35.2698),
        Market("kitale-municipal", "Kitale Municipal Market", "Trans Nzoia", 1.0168, 35.0063),
        Market("gakoromone", "Gakoromone Market", "Meru", 0.0478, 37.6527),
        Market("machakos-main", "Machakos Main Market", "Machakos", -1.5182, 37.2648),
    )

    private fun commodity(id: String, en: String, sw: String, unit: String, vararg factors: Pair<String, String>) =
        DemoCommodity(Commodity(id, en, sw, unit, listOf("standard", "grade_a", "grade_c")),
            (listOf("kg" to "1") + factors).associate { (name, kg) -> name to kg.toBigDecimal() })

    val commodities = listOf(
        commodity("avocados", "Avocados", "Parachichi", "crate", "crate" to "20", "bag_50kg" to "50", "piece" to "0.2"),
        commodity("mangoes", "Mangoes", "Maembe", "crate", "crate" to "25", "piece" to "0.3"),
        commodity("green_grams", "Green grams", "Ndengu", "kg", "gorogoro" to "2", "debe" to "16", "bag_90kg" to "90"),
        commodity("sweet_potatoes", "Sweet potatoes", "Viazi vitamu", "bag_50kg", "bag_50kg" to "50", "debe" to "18"),
        commodity("garlic", "Garlic", "Vitunguu saumu", "kg", "bag_10kg" to "10"),
        commodity("ginger", "Ginger", "Tangawizi", "kg", "bag_25kg" to "25", "crate" to "20"),
        commodity("watermelons", "Watermelons", "Matikiti maji", "piece", "piece" to "5", "crate" to "40"),
        commodity("spinach", "Spinach", "Spinachi", "bunch", "bunch" to "0.3", "bag_50kg" to "50"),
    )

    suspend fun apply(s: Services): Int {
        DemoCli.requireDev(s.config)
        val marketInsert = s.db.prepare("INSERT INTO markets (market_id,name,county,lat,lng,created_at,updated_at) VALUES (?,?,?,?,?,?,?) IF NOT EXISTS")
        val commodityInsert = s.db.prepare("INSERT INTO commodities (commodity_id,name_en,name_sw,default_unit,grades,created_at,updated_at) VALUES (?,?,?,?,?,?,?) IF NOT EXISTS")
        val unitInsert = s.db.prepare("INSERT INTO commodity_units (commodity_id,unit,version,kg_per_unit,valid_from,verified,note) VALUES (?,?,?,?,?,?,?) IF NOT EXISTS")
        val stamp = Instant.parse("2026-01-01T00:00:00Z")
        var inserted = 0
        for (m in markets) {
            if (s.db.exec(marketInsert.bind(m.marketId, m.name, m.county, m.lat, m.lng, stamp, stamp)).wasApplied()) inserted++
            check(s.markets.get(m.marketId) == m) { "Demo market ID conflicts with existing catalog: ${m.marketId}" }
        }
        for ((c, factors) in commodities) {
            if (s.db.exec(commodityInsert.bind(c.commodityId, c.nameEn, c.nameSw, c.defaultUnit, c.grades, stamp, stamp)).wasApplied()) inserted++
            check(s.commodities.get(c.commodityId) == c) { "Demo commodity ID conflicts with existing catalog: ${c.commodityId}" }
            for ((unit, kg) in factors) {
                if (s.db.exec(unitInsert.bind(c.commodityId, unit, 1, kg, LocalDate.of(2026, 1, 1), false,
                        "GENERATED DEMO CATALOG: placeholder conversion, not field verified")).wasApplied()) inserted++
                val actual = s.units.latest(c.commodityId, unit)!!
                check(actual.version == 1 && actual.kgPerUnit.compareTo(kg) == 0 && !actual.verified) {
                    "Demo conversion changed; use an explicitly versioned new dataset: ${c.commodityId}/$unit"
                }
            }
        }
        return inserted
    }
}
