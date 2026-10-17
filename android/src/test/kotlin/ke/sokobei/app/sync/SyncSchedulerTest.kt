package ke.sokobei.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import ke.sokobei.app.data.sync.WorkManagerSyncScheduler
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSchedulerTest {
    private lateinit var wm: WorkManager
    private lateinit var scheduler: WorkManagerSyncScheduler

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().build())
        wm = WorkManager.getInstance(ctx)
        scheduler = WorkManagerSyncScheduler(ctx)
    }

    @Test fun periodic_is_unique_keep_and_requires_network() {
        scheduler.ensurePeriodic()
        val first = wm.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.PERIODIC_NAME).get().single()
        scheduler.ensurePeriodic() // KEEP: must not replace or duplicate
        val again = wm.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.PERIODIC_NAME).get().single()
        assertEquals(first.id, again.id)
        assertEquals(NetworkType.CONNECTED, again.constraints.requiredNetworkType)
    }

    @Test fun one_off_is_unique_appended_and_requires_network() {
        scheduler.enqueueNow()
        scheduler.enqueueNow() // APPEND_OR_REPLACE: second run is chained behind the first, not dropped
        val infos = wm.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.ONE_OFF_NAME).get()
        assertEquals(2, infos.size)
        assertEquals(1, infos.count { it.state == WorkInfo.State.BLOCKED })
        infos.forEach { assertEquals(NetworkType.CONNECTED, it.constraints.requiredNetworkType) }
    }
}
