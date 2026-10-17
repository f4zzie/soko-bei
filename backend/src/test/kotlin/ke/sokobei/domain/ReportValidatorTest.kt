package ke.sokobei.domain

import ke.sokobei.shared.ReportRequest
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReportValidatorTest {
    private val now = Instant.parse("2026-10-17T09:00:00Z")
    private val v = ReportValidator(maxAgeDays = 14)
    private val market = Market("wakulima", "Wakulima", "Nairobi", null, null)
    private val tomatoes = Commodity("tomatoes", "Tomatoes", "Nyanya", "crate", listOf("standard", "grade_a"))
    private val crate = UnitFactor("tomatoes", "crate", 2, BigDecimal(60), false)

    private fun req(
        ms: Long = now.toEpochMilli(), grade: String = "standard", unit: String = "crate",
        qty: String = "1", price: Int = 4500, id: String = UUID.randomUUID().toString(),
    ) = ReportRequest(id, ms, "wakulima", "tomatoes", grade, unit, BigDecimal(qty), price)

    private fun errors(r: ReportRequest, m: Market? = market, c: Commodity? = tomatoes, f: UnitFactor? = crate) =
        assertFailsWith<ValidationException> { v.validate(r, m, c, f, now) }.details

    @Test fun `valid report computes per kg and records conversion version`() {
        val ok = v.validate(req(), market, tomatoes, crate, now)
        assertEquals(BigDecimal("75.00"), ok.pricePerKgKes)
        assertEquals(2, ok.conversionVersion)
        assertEquals(BigDecimal(60), ok.kgPerUnit)
    }

    @Test fun `unknown market and commodity`() {
        assertTrue("market_id" in errors(req(), m = null))
        assertTrue("commodity_id" in errors(req(), c = null, f = null))
    }

    @Test fun `grade must be allowed`() = assertTrue("grade" in errors(req(grade = "grade_z")))
    @Test fun `unit must have a conversion`() = assertTrue("unit" in errors(req(unit = "bunch"), f = null))
    @Test fun `quantity must be positive`() {
        assertTrue("quantity" in errors(req(qty = "0")))
        assertTrue("quantity" in errors(req(qty = "-1")))
    }
    @Test fun `price must be positive`() {
        assertTrue("price_kes" in errors(req(price = 0)))
        assertTrue("price_kes" in errors(req(price = -5)))
    }
    @Test fun `report id must be uuid`() = assertTrue("report_id" in errors(req(id = "nope")))

    @Test fun `future tolerance is five minutes`() {
        v.validate(req(ms = now.plus(Duration.ofMinutes(5)).toEpochMilli()), market, tomatoes, crate, now)
        assertTrue("reported_at_ms" in errors(req(ms = now.plus(Duration.ofMinutes(5)).toEpochMilli() + 1)))
    }

    @Test fun `age limit is fourteen days`() {
        v.validate(req(ms = now.minus(Duration.ofDays(14)).toEpochMilli()), market, tomatoes, crate, now)
        assertTrue("reported_at_ms" in errors(req(ms = now.minus(Duration.ofDays(14)).toEpochMilli() - 1)))
    }

    @Test fun `age limit is configurable`() {
        val strict = ReportValidator(maxAgeDays = 1)
        assertFailsWith<ValidationException> {
            strict.validate(req(ms = now.minus(Duration.ofDays(2)).toEpochMilli()), market, tomatoes, crate, now)
        }
    }

    @Test fun `implied price per kg must be within bounds`() {
        assertTrue("price_kes" in errors(req(price = 1), f = UnitFactor("tomatoes", "crate", 1, BigDecimal(60), false)))
        assertTrue("price_kes" in errors(req(price = 10_000_000)))
    }

    @Test fun `all errors are reported together`() {
        val e = errors(req(grade = "x", qty = "0", price = 0))
        assertEquals(setOf("grade", "quantity", "price_kes"), e.keys)
    }
}
