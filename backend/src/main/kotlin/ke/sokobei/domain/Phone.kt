package ke.sokobei.domain

object Phone {
    /** Normalizes Kenyan numbers to +2547XXXXXXXX / +2541XXXXXXXX. Returns null if invalid. */
    fun normalize(raw: String): String? {
        val cleaned = raw.trim().replace(Regex("[\\s\\-()]"), "")
        val national = when {
            cleaned.startsWith("+254") -> cleaned.removePrefix("+254")
            cleaned.startsWith("254") -> cleaned.removePrefix("254")
            cleaned.startsWith("0") -> cleaned.removePrefix("0")
            else -> return null
        }
        if (!national.matches(Regex("[71]\\d{8}"))) return null
        return "+254$national"
    }

    /** Mask all but the last 3 digits, for logs. */
    fun mask(phone: String): String =
        if (phone.length <= 3) "***" else "*".repeat(phone.length - 3) + phone.takeLast(3)
}
