package ke.sokobei.jobs

import ke.sokobei.repository.JobStateRepository
import ke.sokobei.service.PriceRecomputeService
import ke.sokobei.time.BusinessCalendar
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Runs a job body, records success/failure in job_state, and exposes last-success and lag metrics. */
class JobRunner(
    private val state: JobStateRepository,
    private val calendar: BusinessCalendar,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(JobRunner::class.java)
    private val lastSuccessEpoch = ConcurrentHashMap<String, AtomicLong>()
    private val registeredAt = calendar.now().epochSecond

    fun register(job: String) {
        val holder = lastSuccessEpoch.computeIfAbsent(job) { AtomicLong(0) }
        Gauge.builder("soko_job_last_success_timestamp_seconds", holder) { it.get().toDouble() }
            .tags(Tags.of("job", job)).register(registry)
        Gauge.builder("soko_job_lag_seconds", holder) { (calendar.now().epochSecond - maxOf(it.get(), registeredAt)).toDouble() }
            .tags(Tags.of("job", job)).register(registry)
    }

    suspend fun run(job: String, processedDay: LocalDate?, body: suspend () -> Unit): Boolean {
        val started = calendar.now()
        return try {
            body()
            val done = calendar.now()
            state.recordSuccess(job, done, processedDay)
            lastSuccessEpoch.computeIfAbsent(job) { AtomicLong() }.set(done.epochSecond)
            log.info("Job {} succeeded in {} ms", job, Duration.between(started, done).toMillis())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Job {} failed", job, e)
            runCatching { state.recordFailure(job, e.message ?: e.javaClass.simpleName) }
            false
        }
    }

    suspend fun lastSuccess(job: String): Instant? = state.get(job)?.lastSuccessAt
}

class DailyStatsJob(
    private val recompute: PriceRecomputeService,
    private val runner: JobRunner,
    private val calendar: BusinessCalendar,
    private val maxAgeDays: Int,
) {
    /** Recomputes daily_stats for every (commodity, market) over the late-report window (inclusive of today). */
    suspend fun run(): Boolean {
        val today = calendar.today()
        return runner.run(NAME, today) { recompute.rebuildDaily(today.minusDays(maxAgeDays.toLong()), today) }
    }

    companion object { const val NAME = "daily_stats" }
}

class ReconciliationJob(private val recompute: PriceRecomputeService, private val runner: JobRunner, private val calendar: BusinessCalendar) {
    /** Rebuilds both latest tables for today from the source; also drops stale values after midnight EAT. */
    suspend fun run(): Boolean = runner.run(NAME, calendar.today()) { recompute.rebuildLatestToday() }

    companion object { const val NAME = "reconciliation" }
}

class JobScheduler(
    private val scope: CoroutineScope,
    private val calendar: BusinessCalendar,
    private val runner: JobRunner,
    private val daily: DailyStatsJob,
    private val reconciliation: ReconciliationJob,
) {
    fun start() {
        runner.register(DailyStatsJob.NAME)
        runner.register(ReconciliationJob.NAME)
        scope.launch {
            delay(30_000)
            while (true) {
                reconciliation.run()
                delay(Duration.ofMinutes(15).toMillis())
            }
        }
        scope.launch {
            // Catch-up: if the nightly run was missed (e.g. the service was down at 00:15), do it shortly after boot.
            delay(60_000)
            val last = runner.lastSuccess(DailyStatsJob.NAME)
            if (last == null || Duration.between(last, calendar.now()) > Duration.ofHours(24)) daily.run()
            while (true) {
                delay(millisUntilNextRun(calendar.now()))
                daily.run()
            }
        }
    }

    companion object {
        val RUN_AT: LocalTime = LocalTime.of(0, 15)

        /** Milliseconds until the next 00:15 EAT strictly after [now]. */
        fun millisUntilNextRun(now: Instant): Long {
            val local = now.atZone(BusinessCalendar.EAT)
            var next = local.toLocalDate().atTime(RUN_AT).atZone(BusinessCalendar.EAT)
            if (!next.toInstant().isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next.toInstant()).toMillis()
        }
    }
}
