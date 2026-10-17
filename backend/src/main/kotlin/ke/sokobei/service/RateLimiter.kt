package ke.sokobei.service

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** In-memory sliding-window limiter. Single-instance only (see README, known limitations). */
class SlidingWindowLimiter(private val now: () -> Instant) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    fun tryAcquire(key: String, limit: Int, window: Duration): Boolean {
        val t = now()
        val q = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && q.first().plus(window) <= t) q.removeFirst()
            if (q.size >= limit) return false
            q.addLast(t)
            return true
        }
    }
}
