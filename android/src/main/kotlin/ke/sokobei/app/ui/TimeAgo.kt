package ke.sokobei.app.ui

sealed interface Ago {
    data object JustNow : Ago
    data class Minutes(val n: Int) : Ago
    data class Hours(val n: Int) : Ago
    data class Days(val n: Int) : Ago
}

/** Pure, testable "updated X ago" bucketing. Future timestamps (clock skew) count as just now. */
fun agoOf(nowMs: Long, thenMs: Long): Ago {
    val minutes = ((nowMs - thenMs) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> Ago.JustNow
        minutes < 60 -> Ago.Minutes(minutes.toInt())
        minutes < 24 * 60 -> Ago.Hours((minutes / 60).toInt())
        else -> Ago.Days((minutes / (24 * 60)).toInt())
    }
}
