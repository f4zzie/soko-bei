package ke.sokobei.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MedianTest {
    private fun d(vararg v: String) = v.map(::BigDecimal)

    @Test fun `empty is null`() = assertNull(Median.of(emptyList()))
    @Test fun `single`() = assertEquals(BigDecimal("42.00"), Median.of(d("42")))
    @Test fun `odd count picks middle after sorting`() = assertEquals(BigDecimal("30.00"), Median.of(d("50", "10", "30")))
    @Test fun `even count is mean of two middle`() = assertEquals(BigDecimal("25.00"), Median.of(d("40", "10", "20", "30")))
    @Test fun `even count rounds half up to 2 places`() = assertEquals(BigDecimal("10.01"), Median.of(d("10.00", "10.01")))
}

class UnitConversionTest {
    @Test fun `crate of 60kg`() =
        assertEquals(BigDecimal("75.00"), UnitConversion.pricePerKg(4500, BigDecimal(1), BigDecimal(60)))
    @Test fun `quantity multiplies`() =
        assertEquals(BigDecimal("37.50"), UnitConversion.pricePerKg(4500, BigDecimal(2), BigDecimal(60)))
    @Test fun `rounds to two places half up`() =
        assertEquals(BigDecimal("33.33"), UnitConversion.pricePerKg(100, BigDecimal(1), BigDecimal(3)))
    @Test fun `rounds up on half`() =
        assertEquals(BigDecimal("0.67"), UnitConversion.pricePerKg(2, BigDecimal(1), BigDecimal(3)))
    @Test fun `fractional quantity`() =
        assertEquals(BigDecimal("40.00"), UnitConversion.pricePerKg(100, BigDecimal("2.5"), BigDecimal(1)))
    @Test fun `non positive rejected`() {
        assertFailsWith<IllegalArgumentException> { UnitConversion.pricePerKg(10, BigDecimal.ZERO, BigDecimal.ONE) }
    }
}

class PhoneTest {
    @Test fun `all accepted forms normalize`() {
        listOf("0712345678", "+254712345678", "254712345678", "0712 345 678", "+254-712-345-678").forEach {
            assertEquals("+254712345678", Phone.normalize(it), it)
        }
        assertEquals("+254112345678", Phone.normalize("0112345678"))
        assertEquals("+254112345678", Phone.normalize("+254112345678"))
    }

    @Test fun `invalid rejected`() {
        listOf("", "abc", "071234567", "07123456789", "0212345678", "+255712345678", "+2540712345678", "12345", "+254 7123")
            .forEach { assertNull(Phone.normalize(it), it) }
    }

    @Test fun `mask keeps last three digits`() {
        assertEquals("**********678", Phone.mask("+254712345678"))
    }
}
