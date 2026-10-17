package ke.sokobei.time

import java.security.MessageDigest
import java.util.UUID

/**
 * Deterministic version-1 UUIDs for reports.
 *
 * Timestamp = reported_at_ms (+ 0..9999 x 100ns sub-millisecond offset derived from report_id).
 * Clock sequence and node bits come from SHA-256(report_id). Same inputs always yield the same UUID,
 * which makes client retries idempotent at the primary-key level.
 */
object TimeUuids {
    /** 100ns intervals between 1582-10-15 (UUID epoch) and 1970-01-01. */
    private const val GREGORIAN_OFFSET_100NS = 122_192_928_000_000_000L
    private const val INTERVALS_PER_MS = 10_000L

    fun forReport(reportId: UUID, reportedAtMs: Long): UUID {
        require(reportedAtMs >= 0) { "reportedAtMs must be non-negative" }
        val h = MessageDigest.getInstance("SHA-256").digest(reportId.toString().toByteArray())
        val subMs = (((h[0].toLong() and 0xff) shl 8) or (h[1].toLong() and 0xff)) % INTERVALS_PER_MS
        val ts = reportedAtMs * INTERVALS_PER_MS + subMs + GREGORIAN_OFFSET_100NS

        val timeLow = ts and 0xffffffffL
        val timeMid = (ts ushr 32) and 0xffffL
        val timeHi = (ts ushr 48) and 0x0fffL
        val msb = (timeLow shl 32) or (timeMid shl 16) or (0x1000L or timeHi)

        val clockSeq = (((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)) and 0x3fffL
        var node = 0L
        for (i in 4 until 10) node = (node shl 8) or (h[i].toLong() and 0xff)
        val lsb = (0x8000L or clockSeq).shl(48) or node
        return UUID(msb, lsb)
    }

    /** Unix epoch milliseconds embedded in a version-1 UUID. */
    fun timestampMs(uuid: UUID): Long {
        require(uuid.version() == 1) { "not a time-based UUID" }
        return (uuid.timestamp() - GREGORIAN_OFFSET_100NS) / INTERVALS_PER_MS
    }
}
