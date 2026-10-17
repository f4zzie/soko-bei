package ke.sokobei.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

interface SyncScheduler {
    /** One-off sync: after submit, app start, login and when connectivity returns. */
    fun enqueueNow()
    /** Safety-net periodic sync. */
    fun ensurePeriodic()
}

class WorkManagerSyncScheduler(context: Context) : SyncScheduler {
    private val workManager = WorkManager.getInstance(context)

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    override fun enqueueNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: never drop a trigger while a run is in flight (a report submitted
        // mid-sync must be picked up by a following run), and never get stuck behind a
        // cancelled/failed predecessor. See NOTES.md.
        workManager.enqueueUniqueWork(ONE_OFF_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun ensurePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // KEEP: the schedule is created once; re-calling on every app start must not reset its timer.
        workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val ONE_OFF_NAME = "report_sync_oneoff"
        const val PERIODIC_NAME = "report_sync_periodic"
        const val BACKOFF_SECONDS = 30L
        const val PERIOD_HOURS = 6L
    }
}
