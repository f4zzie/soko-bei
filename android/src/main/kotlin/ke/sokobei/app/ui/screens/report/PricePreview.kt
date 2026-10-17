package ke.sokobei.app.ui.screens.report

import ke.sokobei.shared.PriceMath
import java.math.BigDecimal

/** Parses form text and computes the live KES/kg preview with the shared [PriceMath]. */
object PricePreview {
    fun parseQuantity(text: String): BigDecimal? =
        text.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

    fun parsePrice(text: String): Int? =
        text.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 9 }?.toIntOrNull()?.takeIf { it > 0 }

    /** Null until price, quantity and the unit's kg_per_unit are all valid. */
    fun pricePerKg(priceText: String, quantityText: String, kgPerUnit: BigDecimal?): BigDecimal? {
        val price = parsePrice(priceText) ?: return null
        val qty = parseQuantity(quantityText) ?: return null
        return PriceMath.pricePerKg(price, qty, kgPerUnit ?: return null)
    }
}
