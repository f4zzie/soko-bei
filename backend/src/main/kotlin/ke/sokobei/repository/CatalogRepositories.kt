package ke.sokobei.repository

import com.datastax.oss.driver.api.core.cql.Row
import ke.sokobei.domain.Commodity
import ke.sokobei.domain.Market
import ke.sokobei.domain.UnitFactor

class MarketRepository(private val db: Db) {
    private val all = db.prepare("SELECT market_id, name, county, lat, lng FROM markets")
    private val byId = db.prepare("SELECT market_id, name, county, lat, lng FROM markets WHERE market_id = ?")

    private fun Row.toMarket() = Market(getString("market_id")!!, getString("name") ?: "", getString("county") ?: "",
        if (isNull("lat")) null else getDouble("lat"), if (isNull("lng")) null else getDouble("lng"))

    suspend fun page(limit: Int, token: String?): Pair<List<Market>, String?> =
        db.page(all.bind(), limit, token).let { p -> p.rows.map { it.toMarket() } to p.nextToken }

    suspend fun all(): List<Market> = db.all(all.bind()).map { it.toMarket() }
    suspend fun get(id: String): Market? = db.one(byId.bind(id))?.toMarket()
}

class CommodityRepository(private val db: Db) {
    private val all = db.prepare("SELECT commodity_id, name_en, name_sw, default_unit, grades FROM commodities")
    private val byId = db.prepare("SELECT commodity_id, name_en, name_sw, default_unit, grades FROM commodities WHERE commodity_id = ?")

    private fun Row.toCommodity() = Commodity(getString("commodity_id")!!, getString("name_en") ?: "", getString("name_sw") ?: "",
        getString("default_unit") ?: "kg", getList("grades", String::class.java) ?: listOf("standard"))

    suspend fun page(limit: Int, token: String?): Pair<List<Commodity>, String?> =
        db.page(all.bind(), limit, token).let { p -> p.rows.map { it.toCommodity() } to p.nextToken }

    suspend fun all(): List<Commodity> = db.all(all.bind()).map { it.toCommodity() }
    suspend fun get(id: String): Commodity? = db.one(byId.bind(id))?.toCommodity()
}

class CommodityUnitRepository(private val db: Db) {
    private val forCommodity = db.prepare(
        "SELECT commodity_id, unit, version, kg_per_unit, verified FROM commodity_units WHERE commodity_id = ?")
    private val forUnit = db.prepare(
        "SELECT commodity_id, unit, version, kg_per_unit, verified FROM commodity_units WHERE commodity_id = ? AND unit = ? LIMIT 1")

    private fun Row.toFactor() = UnitFactor(getString("commodity_id")!!, getString("unit")!!, getInt("version"),
        getBigDecimal("kg_per_unit")!!, getBoolean("verified"))

    /** Latest version per unit (clustering is unit ASC, version DESC so the first row per unit is the newest). */
    suspend fun latestForCommodity(commodityId: String): List<UnitFactor> =
        db.all(forCommodity.bind(commodityId)).map { it.toFactor() }.distinctBy { it.unit }

    suspend fun latest(commodityId: String, unit: String): UnitFactor? = db.one(forUnit.bind(commodityId, unit))?.toFactor()
}

