package ke.sokobei.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import ke.sokobei.app.data.db.ReportDao
import ke.sokobei.app.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SokoBeiApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var scheduler: SyncScheduler
    @Inject lateinit var reportDao: ReportDao

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // App start: one sync (also expires stale reports) plus the periodic safety net.
        scheduler.ensurePeriodic()
        scheduler.enqueueNow()
        watchConnectivity()
    }

    /** When the network comes back and reports are waiting, trigger a sync right away. */
    private fun watchConnectivity() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                appScope.launch { if (reportDao.pendingCount() > 0) scheduler.enqueueNow() }
            }
        })
    }
}
