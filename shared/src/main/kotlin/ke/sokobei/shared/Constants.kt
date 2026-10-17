package ke.sokobei.shared

/** Validation constants shared by backend and Android. */
object Limits {
    const val DEFAULT_REPORT_MAX_AGE_DAYS = 14
    const val FUTURE_SKEW_MINUTES = 5L
    const val DEFAULT_PAGE_LIMIT = 20
    const val MAX_PAGE_LIMIT = 100
    const val MAX_TREND_DAYS = 365
    const val DEFAULT_GRADE = "standard"
    const val CURRENT_CONSENT_VERSION = 1
    const val OTP_LENGTH = 6
}

object ErrorCodes {
    const val VALIDATION_FAILED = "VALIDATION_FAILED"
    const val CONFLICT = "CONFLICT"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val FORBIDDEN = "FORBIDDEN"
    const val NOT_FOUND = "NOT_FOUND"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val CONSENT_REQUIRED = "CONSENT_REQUIRED"
    const val INTERNAL = "INTERNAL_ERROR"
}

/** Pure price-per-kg preview used by the Android report form; same formula as the backend. */
object PriceMath {
    fun pricePerKg(priceKes: Int, quantity: java.math.BigDecimal, kgPerUnit: java.math.BigDecimal): java.math.BigDecimal? {
        if (priceKes <= 0 || quantity.signum() <= 0 || kgPerUnit.signum() <= 0) return null
        return java.math.BigDecimal(priceKes).divide(quantity.multiply(kgPerUnit), 2, java.math.RoundingMode.HALF_UP)
    }
}
