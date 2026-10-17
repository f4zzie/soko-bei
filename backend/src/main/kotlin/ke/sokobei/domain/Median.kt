package ke.sokobei.domain

import java.math.BigDecimal
import java.math.RoundingMode

object Median {
    /** Median of [values]; mean of the two middle values for even counts; null when empty. Rounded to 2 places. */
    fun of(values: Collection<BigDecimal>): BigDecimal? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        val m = if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]).divide(BigDecimal(2), 6, RoundingMode.HALF_UP)
        return m.setScale(2, RoundingMode.HALF_UP)
    }
}
