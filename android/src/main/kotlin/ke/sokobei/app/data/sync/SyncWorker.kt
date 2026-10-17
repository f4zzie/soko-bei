package ke.sokobei.app.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncer: ReportSyncer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (syncer.syncAll()) {
        SyncOutcome.DONE -> Result.success()
        SyncOutcome.RETRY -> Result.retry()
    }
}
