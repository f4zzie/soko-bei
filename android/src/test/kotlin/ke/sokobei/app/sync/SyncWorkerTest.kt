package ke.sokobei.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import ke.sokobei.app.data.db.ReportStatus
import ke.sokobei.app.data.db.SokoBeiDatabase
import ke.sokobei.app.data.sync.ReportSyncer
import ke.sokobei.app.data.sync.SyncWorker
import ke.sokobei.app.support.FakeSettings
import ke.sokobei.app.support.FakeTokenStore
import ke.sokobei.app.support.FixedClock
import ke.sokobei.app.support.MockBackend
import ke.sokobei.app.support.errorJson
import ke.sokobei.app.support.inMemoryDb
import ke.sokobei.app.support.json
import ke.sokobei.app.support.pending
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** Runs the real SyncWorker through WorkManager's test builder against a MockEngine backend. */
@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {
    private lateinit var context: Context
    private lateinit var db: SokoBeiDatabase
    private val clock = FixedClock()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = inMemoryDb(context)
    }

    @After fun tearDown() { db.close() }

    private fun run(backend: MockBackend): ListenableWorker.Result = runBlocking {
        val syncer = ReportSyncer(backend.api, db.reportDao(), backend.tokens, FakeSettings(consent = 1), clock)
        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    SyncWorker(appContext, workerParameters, syncer)
            })
            .build()
        worker.doWork()
    }

    @Test fun created_201_succeeds_and_marks_synced() = runBlocking {
        db.reportDao().insert(pending("w1", clock.now - 1000))
        val r = run(MockBackend(FakeTokenStore("a", "r")) { json(HttpStatusCode.Created, "{}") })
        assertEquals(ListenableWorker.Result.success(), r)
        assertEquals(ReportStatus.SYNCED, db.reportDao().get("w1")!!.status)
    }

    @Test fun existing_200_succeeds() = runBlocking {
        db.reportDao().insert(pending("w2", clock.now - 1000))
        val r = run(MockBackend(FakeTokenStore("a", "r")) { json(HttpStatusCode.OK, "{}") })
        assertEquals(ListenableWorker.Result.success(), r)
        assertEquals(ReportStatus.SYNCED, db.reportDao().get("w2")!!.status)
    }

    @Test fun conflict_409_completes_work_with_rejected_report() = runBlocking {
        db.reportDao().insert(pending("w3", clock.now - 1000))
        val r = run(MockBackend(FakeTokenStore("a", "r")) { errorJson(HttpStatusCode.Conflict, "CONFLICT", "different payload") })
        assertEquals(ListenableWorker.Result.success(), r) // no retry for permanent failures
        assertEquals(ReportStatus.REJECTED, db.reportDao().get("w3")!!.status)
    }

    @Test fun server_error_500_returns_retry() = runBlocking {
        db.reportDao().insert(pending("w4", clock.now - 1000))
        val r = run(MockBackend(FakeTokenStore("a", "r")) { errorJson(HttpStatusCode.InternalServerError, "INTERNAL_ERROR", "x") })
        assertEquals(ListenableWorker.Result.retry(), r)
        assertEquals(ReportStatus.PENDING, db.reportDao().get("w4")!!.status)
    }

    @Test fun io_error_returns_retry() = runBlocking {
        db.reportDao().insert(pending("w5", clock.now - 1000))
        val r = run(MockBackend(FakeTokenStore("a", "r")) { throw IOException("offline") })
        assertEquals(ListenableWorker.Result.retry(), r)
    }

    @Test fun unauthorized_then_refresh_then_success() = runBlocking {
        db.reportDao().insert(pending("w6", clock.now - 1000))
        val backend = MockBackend(FakeTokenStore("expired", "r")) { req ->
            when {
                req.url.encodedPath == "/v1/auth/refresh" -> json(HttpStatusCode.OK, """{"access_token":"fresh","expires_in":900}""")
                req.headers[HttpHeaders.Authorization] == "Bearer fresh" -> json(HttpStatusCode.Created, "{}")
                else -> errorJson(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "expired")
            }
        }
        assertEquals(ListenableWorker.Result.success(), run(backend))
        assertEquals(ReportStatus.SYNCED, db.reportDao().get("w6")!!.status)
        assertEquals(1, backend.requests.count { it.url.encodedPath == "/v1/auth/refresh" })
    }

    @Test fun expired_reports_are_not_sent() = runBlocking {
        db.reportDao().insert(pending("w7", clock.now - 15L * 24 * 60 * 60 * 1000))
        val backend = MockBackend(FakeTokenStore("a", "r")) { json(HttpStatusCode.Created, "{}") }
        assertEquals(ListenableWorker.Result.success(), run(backend))
        assertEquals(ReportStatus.EXPIRED, db.reportDao().get("w7")!!.status)
        assertEquals(0, backend.requests.size)
    }
}
