package ke.sokobei.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import ke.sokobei.app.data.db.CacheMetaEntity
import ke.sokobei.app.data.db.MarketPriceEntity
import ke.sokobei.app.data.db.ReportStatus
import ke.sokobei.app.data.db.SokoBeiDatabase
import ke.sokobei.app.data.db.TrendCacheEntity
import ke.sokobei.app.support.beans
import ke.sokobei.app.support.gikomba
import ke.sokobei.app.support.inMemoryDb
import ke.sokobei.app.support.maize
import ke.sokobei.app.support.pending
import ke.sokobei.app.support.wakulima
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
class DaoTest {
    private lateinit var db: SokoBeiDatabase

    @Before fun setUp() { db = inMemoryDb(ApplicationProvider.getApplicationContext<Context>()) }
    @After fun tearDown() { db.close() }

    @Test fun pending_reports_roundtrip_with_exact_decimals() = runBlocking {
        val r = pending("r1", 100).copy(quantity = BigDecimal("2.5"), pricePerKg = BigDecimal("33.33"))
        db.reportDao().insert(r)
        val back = db.reportDao().get("r1")!!
        assertEquals(BigDecimal("2.5"), back.quantity)
        assertEquals(BigDecimal("33.33"), back.pricePerKg)
        assertEquals(ReportStatus.PENDING, back.status)
    }

    @Test fun observeAll_is_newest_first_and_pending_is_oldest_first() = runBlocking {
        db.reportDao().insert(pending("old", 100))
        db.reportDao().insert(pending("new", 300))
        db.reportDao().insert(pending("mid", 200, status = ReportStatus.SYNCED))
        db.reportDao().observeAll().test {
            assertEquals(listOf("new", "mid", "old"), awaitItem().map { it.reportId })
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("old", "new"), db.reportDao().pending().map { it.reportId })
        assertEquals(2, db.reportDao().pendingCount())
    }

    @Test fun duplicate_report_id_is_not_silently_overwritten() = runBlocking {
        db.reportDao().insert(pending("dup", 100, price = 4500))
        runCatching { db.reportDao().insert(pending("dup", 100, price = 9999)) }
        assertEquals(4500, db.reportDao().get("dup")!!.priceKes)
    }

    @Test fun expireOlderThan_only_touches_pending() = runBlocking {
        db.reportDao().insert(pending("p-old", 100))
        db.reportDao().insert(pending("s-old", 100, status = ReportStatus.SYNCED))
        db.reportDao().insert(pending("p-new", 1000))
        val n = db.reportDao().expireOlderThan(cutoffMs = 500, now = 2000)
        assertEquals(1, n)
        assertEquals(ReportStatus.EXPIRED, db.reportDao().get("p-old")!!.status)
        assertEquals(ReportStatus.SYNCED, db.reportDao().get("s-old")!!.status)
        assertEquals(ReportStatus.PENDING, db.reportDao().get("p-new")!!.status)
    }

    @Test fun markRejected_and_markSynced_update_status_and_reason() = runBlocking {
        db.reportDao().insert(pending("a", 1))
        db.reportDao().insert(pending("b", 2))
        db.reportDao().markRejected("a", "bad price", 10)
        db.reportDao().markSynced("b", 10)
        assertEquals("bad price", db.reportDao().get("a")!!.rejectReason)
        assertEquals(ReportStatus.REJECTED, db.reportDao().get("a")!!.status)
        assertEquals(ReportStatus.SYNCED, db.reportDao().get("b")!!.status)
        assertNull(db.reportDao().get("b")!!.rejectReason)
    }

    @Test fun commodities_roundtrip_with_units_and_replace_removes_stale_rows() = runBlocking {
        db.catalogDao().replaceCommodities(listOf(maize, beans))
        val stored = db.catalogDao().commodity("maize")!!
        assertEquals(maize, stored)
        assertEquals(BigDecimal("90"), stored.kgPerUnit("sack_90kg"))
        db.catalogDao().replaceCommodities(listOf(beans))
        assertNull(db.catalogDao().commodity("maize"))
    }

    @Test fun markets_replace_and_observe() = runBlocking {
        db.catalogDao().replaceMarkets(listOf(wakulima, gikomba))
        db.catalogDao().observeMarkets().test {
            assertEquals(listOf("Gikomba", "Wakulima"), awaitItem().map { it.name })
            db.catalogDao().replaceMarkets(listOf(gikomba))
            assertEquals(listOf("Gikomba"), awaitItem().map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun market_prices_replace_is_scoped_to_market_and_meta_is_stored() = runBlocking {
        fun p(market: String, c: String, v: String) =
            MarketPriceEntity(market, c, c, c, "standard", BigDecimal(v), null, 3, 5L)
        db.priceDao().replaceMarketPrices("gikomba", listOf(p("gikomba", "maize", "50.00"), p("gikomba", "beans", "120.00")))
        db.priceDao().replaceMarketPrices("wakulima", listOf(p("wakulima", "maize", "55.00")))
        db.priceDao().replaceMarketPrices("gikomba", listOf(p("gikomba", "maize", "51.00")))
        db.priceDao().putMeta(CacheMetaEntity("market_prices:gikomba", 777))

        db.priceDao().observeMarketPrices("gikomba").test {
            val items = awaitItem()
            assertEquals(listOf("maize"), items.map { it.commodityId })
            assertEquals(BigDecimal("51.00"), items.single().medianTodayPerKg)
            assertNull(items.single().lastPricePerKg)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, db.priceDao().observeMarketPrices("wakulima").let { f -> f.first().size })
        assertEquals(777L, db.priceDao().meta("market_prices:gikomba"))
    }

    @Test fun trend_cache_is_keyed_by_window() = runBlocking {
        db.priceDao().putTrend(TrendCacheEntity("maize", "gikomba", 30, "standard", "{\"x\":30}", 1))
        db.priceDao().putTrend(TrendCacheEntity("maize", "gikomba", 90, "standard", "{\"x\":90}", 2))
        assertEquals("{\"x\":30}", db.priceDao().trend("maize", "gikomba", 30, "standard")!!.json)
        assertEquals("{\"x\":90}", db.priceDao().trend("maize", "gikomba", 90, "standard")!!.json)
        assertNull(db.priceDao().trend("maize", "gikomba", 7, "standard"))
    }
}
