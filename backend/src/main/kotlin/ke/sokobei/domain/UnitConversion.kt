package ke.sokobei.domain

import java.math.BigDecimal
import java.math.RoundingMode

object UnitConversion {
    /** price_per_kg = price_kes / (quantity x kg_per_unit), 2 decimal places (HALF_UP). */
    fun pricePerKg(priceKes: Int, quantity: BigDecimal, kgPerUnit: BigDecimal): BigDecimal {
        require(quantity.signum() > 0 && kgPerUnit.signum() > 0) { "quantity and kg_per_unit must be positive" }
        return BigDecimal(priceKes).divide(quantity.multiply(kgPerUnit), 2, RoundingMode.HALF_UP)
    }
}
