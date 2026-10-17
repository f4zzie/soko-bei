package ke.sokobei.app.ui

import ke.sokobei.app.data.db.CommodityPriceEntity
import ke.sokobei.app.ui.screens.commodity.sortByPrice
import ke.sokobei.app.ui.screens.login.PhoneInput
import ke.sokobei.app.ui.screens.report.PricePreview
import ke.sokobei.app.ui.screens.trend.chartGeometry
import ke.sokobei.shared.TrendPointDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class PricePreviewTest {
    private val sack = BigDecimal("90")

    @Test fun computes_price_per_kg_with_shared_PriceMath_rounding() {
        assertEquals(BigDecimal("50.00"), PricePreview.pricePerKg("4500", "1", sack))
        assertEquals(BigDecimal("25.00"), PricePreview.pricePerKg("4500", "2", sack))
        assertEquals(BigDecimal("33.33"), PricePreview.pricePerKg("100", "3", BigDecimal.ONE))
        assertEquals(BigDecimal("1.67"), PricePreview.pricePerKg("5", "3", BigDecimal.ONE)) // 1.666.. half-up
    }

    @Test fun accepts_decimal_comma_and_fractional_quantity() {
        assertEquals(BigDecimal("40.00"), PricePreview.pricePerKg("100", "2,5", BigDecimal.ONE))
        assertEquals(BigDecimal("40.00"), PricePreview.pricePerKg("100", "2.5", BigDecimal.ONE))
    }

    @Test fun null_until_all_inputs_valid() {
        assertNull(PricePreview.pricePerKg("", "1", sack))
        assertNull(PricePreview.pricePerKg("4500", "", sack))
        assertNull(PricePreview.pricePerKg("4500", "1", null))
        assertNull(PricePreview.pricePerKg("0", "1", sack))
        assertNull(PricePreview.pricePerKg("4500", "0", sack))
        assertNull(PricePreview.pricePerKg("4500", "-1", sack))
        assertNull(PricePreview.pricePerKg("45.5", "1", sack)) // price is whole shillings
        assertNull(PricePreview.pricePerKg("abc", "1", sack))
    }
}

class OtherPureLogicTest {
    @Test fun ago_buckets() {
        val now = 10_000_000_000L
        assertEquals(Ago.JustNow, agoOf(now, now - 30_000))
        assertEquals(Ago.JustNow, agoOf(now, now + 5_000)) // clock skew
        assertEquals(Ago.Minutes(1), agoOf(now, now - 60_000))
        assertEquals(Ago.Minutes(59), agoOf(now, now - 59 * 60_000L))
        assertEquals(Ago.Hours(1), agoOf(now, now - 60 * 60_000L))
        assertEquals(Ago.Hours(23), agoOf(now, now - 23 * 3_600_000L - 59 * 60_000L))
        assertEquals(Ago.Days(2), agoOf(now, now - 48 * 3_600_000L))
    }

    @Test fun phone_validation() {
        listOf("0712345678", "0112345678", "+254712345678", "254712345678", "0712 345 678", "0712-345-678").forEach {
            assertTrue(it, PhoneInput.isValid(it))
        }
        listOf("", "071234567", "07123456789", "0212345678", "abc", "+255712345678").forEach {
            assertFalse(it, PhoneInput.isValid(it))
        }
        assertEquals("0712345678", PhoneInput.clean("0712 345-678"))
    }

    private fun cp(market: String, median: String?) =
        CommodityPriceEntity("maize", market, market, "C", "standard", median?.let(::BigDecimal), null, 1, 1)

    @Test fun commodity_prices_sorted_cheapest_first_with_missing_last() {
        val sorted = sortByPrice(listOf(cp("c", "60"), cp("none", null), cp("a", "45.5"), cp("b", "45.5")))
        assertEquals(listOf("a", "b", "c", "none"), sorted.map { it.marketId })
    }

    private fun tp(min: String, med: String, max: String) =
        TrendPointDto("2026-10-01", "standard", BigDecimal(min), BigDecimal(med), BigDecimal(max), 3)

    @Test fun chart_geometry_maps_values_into_unit_square() {
        assertNull(chartGeometry(emptyList()))
        val g = chartGeometry(listOf(tp("40", "50", "60"), tp("50", "60", "100")))!!
        assertEquals(40f, g.minValue); assertEquals(100f, g.maxValue)
        assertEquals(listOf(0f, 1f), g.xs)
        assertEquals(1f, g.minYs[0], 1e-6f)   // lowest value is at the bottom
        assertEquals(0f, g.maxYs[1], 1e-6f)   // highest at the top
        assertEquals(1f - (60f - 40f) / 60f, g.maxYs[0], 1e-6f)
        assertTrue(g.maxYs.zip(g.minYs).all { (hi, lo) -> hi <= lo }) // band is never inverted
    }

    @Test fun chart_geometry_handles_single_and_flat_series() {
        val one = chartGeometry(listOf(tp("50", "50", "50")))!!
        assertEquals(listOf(0.5f), one.xs)
        assertNotNull(one.medianYs.single())
    }
}
