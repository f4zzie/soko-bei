package ke.sokobei.app.ui

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import ke.sokobei.app.data.db.ReportStatus
import ke.sokobei.app.data.db.SokoBeiDatabase
import ke.sokobei.app.data.repository.ReportRepositoryImpl
import ke.sokobei.app.support.FakeCatalogRepository
import ke.sokobei.app.support.FakeLanguage
import ke.sokobei.app.support.FakeSettings
import ke.sokobei.app.support.FixedClock
import ke.sokobei.app.support.RecordingScheduler
import ke.sokobei.app.support.beans
import ke.sokobei.app.support.gikomba
import ke.sokobei.app.support.inMemoryDb
import ke.sokobei.app.support.maize
import ke.sokobei.app.support.wakulima
import ke.sokobei.app.ui.screens.report.ReportScreen
import ke.sokobei.app.ui.screens.report.ReportTags
import ke.sokobei.app.ui.screens.report.ReportViewModel
import ke.sokobei.app.ui.theme.SokoBeiTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.math.BigDecimal

/**
 * Compose UI test of the real report form, run on the JVM with Robolectric. The report repository is the
 * real Room-backed one and there is no HTTP client anywhere in this graph, so a successful submit proves
 * the form works with no network.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportFormComposeTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var db: SokoBeiDatabase
    private val scheduler = RecordingScheduler()
    @Volatile private var done = false

    @Before fun setUp() {
        db = inMemoryDb(ApplicationProvider.getApplicationContext<Context>())
        val vm = ReportViewModel(
            SavedStateHandle(mapOf("marketId" to "gikomba", "commodityId" to "maize")),
            FakeCatalogRepository(listOf(gikomba, wakulima), listOf(maize, beans)),
            ReportRepositoryImpl(db.reportDao(), scheduler, FixedClock(42_000)),
            FakeSettings(), FakeLanguage(),
        )
        rule.setContent { SokoBeiTheme { ReportScreen(onDone = { done = true }, onBack = {}, vm = vm) } }
    }

    @After fun tearDown() { db.close() }

    @Test fun price_per_kg_preview_updates_as_inputs_change() {
        rule.onNodeWithTag(ReportTags.PER_KG).assertTextContains("Enter the price and quantity")
        rule.onNodeWithTag(ReportTags.SUBMIT).assertIsNotEnabled()

        rule.onNodeWithTag(ReportTags.PRICE).performTextInput("4500")
        rule.onNodeWithTag(ReportTags.PER_KG).assertTextContains("50.00", substring = true) // 1 x 90 kg sack

        rule.onNodeWithTag(ReportTags.QUANTITY).performTextClearance()
        rule.onNodeWithTag(ReportTags.QUANTITY).performTextInput("2")
        rule.onNodeWithTag(ReportTags.PER_KG).assertTextContains("25.00", substring = true)

        // Change the unit through the dropdown: 4500 / (2 x 18 kg) = 125.00
        rule.onNodeWithTag(ReportTags.UNIT).performClick()
        rule.onNodeWithTag("${ReportTags.UNIT}:debe").performClick()
        rule.onNodeWithTag(ReportTags.PER_KG).assertTextContains("125.00", substring = true)

        rule.onNodeWithTag(ReportTags.PRICE).performTextClearance()
        rule.onNodeWithTag(ReportTags.PER_KG).assertTextContains("Enter the price and quantity")
        rule.onNodeWithTag(ReportTags.SUBMIT).assertIsNotEnabled()
    }

    @Test fun submit_works_offline_stores_pending_report_and_leaves_the_screen() {
        rule.onNodeWithTag(ReportTags.PRICE).performTextInput("9000")
        rule.onNodeWithTag(ReportTags.QUANTITY).performTextClearance()
        rule.onNodeWithTag(ReportTags.QUANTITY).performTextInput("2")
        rule.onNodeWithTag(ReportTags.SUBMIT).assertIsEnabled().performScrollTo().performClick()

        // Room writes on a real background thread; give it real time while pumping the main looper.
        val deadline = System.currentTimeMillis() + 15_000
        while (!done && System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            Thread.sleep(25)
        }
        assertTrue(done)

        val stored = runBlocking { db.reportDao().observeAll().first() }.single()
        assertEquals(ReportStatus.PENDING, stored.status)
        assertEquals(9000, stored.priceKes)
        assertEquals(BigDecimal("2"), stored.quantity)
        assertEquals("sack_90kg", stored.unit)
        assertEquals("gikomba", stored.marketId)
        assertEquals(BigDecimal("50.00"), stored.pricePerKg)
        assertEquals(42_000L, stored.reportedAtMs)
        assertEquals(1, scheduler.enqueued)
    }
}
