package ke.sokobei.app.ui

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import ke.sokobei.app.data.db.MarketPriceEntity
import ke.sokobei.app.data.db.ReportStatus
import ke.sokobei.app.data.repository.AuthResult
import ke.sokobei.app.data.repository.RefreshResult
import ke.sokobei.app.data.repository.TrendData
import ke.sokobei.app.support.FakeAuthRepository
import ke.sokobei.app.support.FakeCatalogRepository
import ke.sokobei.app.support.FakeLanguage
import ke.sokobei.app.support.FakePriceRepository
import ke.sokobei.app.support.FakeReportRepository
import ke.sokobei.app.support.FakeSettings
import ke.sokobei.app.support.MainDispatcherRule
import ke.sokobei.app.support.beans
import ke.sokobei.app.support.gikomba
import ke.sokobei.app.support.maize
import ke.sokobei.app.support.pending
import ke.sokobei.app.support.wakulima
import ke.sokobei.app.ui.screens.home.HomeViewModel
import ke.sokobei.app.ui.screens.login.LoginError
import ke.sokobei.app.ui.screens.login.LoginStep
import ke.sokobei.app.ui.screens.login.LoginViewModel
import ke.sokobei.app.ui.screens.reports.MyReportsViewModel
import ke.sokobei.app.ui.screens.report.ReportViewModel
import ke.sokobei.app.ui.screens.settings.SettingsViewModel
import ke.sokobei.app.ui.screens.trend.TrendViewModel
import ke.sokobei.app.ui.screens.welcome.WelcomeViewModel
import ke.sokobei.shared.Limits
import ke.sokobei.shared.TrendPointDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal

class ReportViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val catalog = FakeCatalogRepository(listOf(gikomba, wakulima), listOf(maize, beans))
    private val reports = FakeReportRepository()
    private val lang = FakeLanguage()

    private fun vm(args: Map<String, Any?> = emptyMap(), settings: FakeSettings = FakeSettings()) =
        ReportViewModel(SavedStateHandle(args), catalog, reports, settings, lang)

    @Test fun live_price_per_kg_updates_as_inputs_change() = runTest {
        val vm = vm(mapOf("marketId" to "gikomba", "commodityId" to "maize"))
        vm.state.test {
            awaitItem() // initial/derived
            vm.setPrice("4500")
            var s = expectMostRecentItem()
            assertEquals("sack_90kg", s.unit)            // commodity default unit
            assertEquals("standard", s.grade)
            assertEquals(BigDecimal("50.00"), s.pricePerKg) // 4500 / (1 x 90)

            vm.setQuantity("2")
            s = expectMostRecentItem()
            assertEquals(BigDecimal("25.00"), s.pricePerKg)

            vm.selectUnit("debe")                          // 18 kg
            s = expectMostRecentItem()
            assertEquals(BigDecimal("125.00"), s.pricePerKg) // 4500 / (2 x 18)

            vm.setQuantity("")
            s = expectMostRecentItem()
            assertNull(s.pricePerKg)
            assertFalse(s.canSubmit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun changing_commodity_resets_unit_and_grade_to_valid_choices() = runTest {
        val vm = vm(mapOf("marketId" to "gikomba", "commodityId" to "maize"))
        vm.state.test {
            vm.selectGrade("premium"); vm.selectUnit("debe")
            assertEquals("debe", expectMostRecentItem().unit)
            vm.selectCommodity("beans")
            val s = expectMostRecentItem()
            assertEquals("kg", s.unit); assertEquals("standard", s.grade)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun submit_saves_draft_locally_and_signals_done() = runTest {
        val vm = vm(mapOf("marketId" to "gikomba", "commodityId" to "maize"))
        vm.state.test {
            vm.setPrice("9000"); vm.setQuantity("2")
            assertTrue(expectMostRecentItem().canSubmit)
            vm.done.test {
                vm.submit()
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
        val d = reports.drafts.single()
        assertEquals("gikomba", d.marketId); assertEquals("Gikomba", d.marketName)
        assertEquals("maize", d.commodityId); assertEquals("Maize", d.commodityName)
        assertEquals("sack_90kg", d.unit); assertEquals(9000, d.priceKes)
        assertEquals(BigDecimal("2"), d.quantity)
        assertEquals(BigDecimal("50.00"), d.pricePerKg)
    }

    @Test fun commodity_name_is_stored_in_current_language() = runTest {
        lang.tag = "sw"
        val vm = vm(mapOf("marketId" to "gikomba", "commodityId" to "maize"))
        vm.state.test {
            vm.setPrice("100"); expectMostRecentItem()
            vm.submit()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("Mahindi", reports.drafts.single().commodityName)
    }

    @Test fun submit_is_ignored_while_invalid() = runTest {
        val vm = vm(mapOf("marketId" to "gikomba", "commodityId" to "maize"))
        vm.state.test {
            vm.submit() // no price yet
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(reports.drafts.isEmpty())
    }

    @Test fun market_defaults_to_the_one_selected_on_home() = runTest {
        val vm = vm(settings = FakeSettings(market = "wakulima"))
        vm.state.test {
            assertEquals("wakulima", expectMostRecentItem().marketId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun price_input_keeps_digits_only() = runTest {
        val vm = vm()
        vm.state.test {
            vm.setPrice("12a.5,0")
            assertEquals("1250", expectMostRecentItem().price)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun p(c: String) = MarketPriceEntity("gikomba", c, c, c, "standard", BigDecimal("50"), BigDecimal("48"), 2, 1L)

    @Test fun selects_first_market_loads_its_cached_prices_and_refreshes() = runTest {
        val catalog = FakeCatalogRepository(listOf(gikomba, wakulima))
        val prices = FakePriceRepository().apply { marketPriceFlows["gikomba"] = kotlinx.coroutines.flow.MutableStateFlow(listOf(p("maize"))) }
        val vm = HomeViewModel(catalog, prices, FakeSettings())
        vm.state.test {
            val s = expectMostRecentItem()
            assertEquals("gikomba", s.selectedMarketId)
            assertEquals(listOf("maize"), s.prices.map { it.commodityId })
            assertEquals(1_000L, s.pricesUpdatedMs)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue("gikomba" in prices.refreshedMarkets)
        assertTrue(catalog.refreshCalls >= 1)
    }

    @Test fun switching_market_changes_data_and_triggers_refresh() = runTest {
        val settings = FakeSettings()
        val prices = FakePriceRepository()
        prices.marketPriceFlows["wakulima"] = kotlinx.coroutines.flow.MutableStateFlow(listOf(p("beans").copy(marketId = "wakulima")))
        val vm = HomeViewModel(FakeCatalogRepository(listOf(gikomba, wakulima)), prices, settings)
        vm.state.test {
            vm.selectMarket("wakulima")
            val s = expectMostRecentItem()
            assertEquals("wakulima", s.selectedMarketId)
            assertEquals(listOf("beans"), s.prices.map { it.commodityId })
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("wakulima", settings.selectedMarketId.first())
        assertTrue("wakulima" in prices.refreshedMarkets)
    }

    @Test fun offline_refresh_keeps_cached_rows_and_exposes_offline_state() = runTest {
        val prices = FakePriceRepository().apply {
            result = RefreshResult.OFFLINE
            marketPriceFlows["gikomba"] = kotlinx.coroutines.flow.MutableStateFlow(listOf(p("maize")))
        }
        val vm = HomeViewModel(FakeCatalogRepository(listOf(gikomba)).apply { refreshResult = RefreshResult.OFFLINE }, prices, FakeSettings())
        vm.state.test {
            val s = expectMostRecentItem()
            assertEquals(RefreshResult.OFFLINE, s.lastRefresh)
            assertEquals(1, s.prices.size)
            assertFalse(s.refreshing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun stale_saved_market_falls_back_to_first_available() = runTest {
        val vm = HomeViewModel(FakeCatalogRepository(listOf(gikomba)), FakePriceRepository(), FakeSettings(market = "deleted"))
        vm.state.test {
            assertEquals("gikomba", expectMostRecentItem().selectedMarketId)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

class LoginViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun invalid_phone_never_hits_the_network() = runTest {
        val auth = FakeAuthRepository()
        val vm = LoginViewModel(auth)
        vm.onPhoneChange("12345"); vm.sendCode()
        assertEquals(LoginError.INVALID_PHONE, vm.state.value.error)
        assertTrue(auth.otpRequests.isEmpty())
    }

    @Test fun full_flow_phone_then_code_then_done() = runTest {
        val auth = FakeAuthRepository()
        val vm = LoginViewModel(auth)
        vm.done.test {
            vm.onPhoneChange("0712 345 678"); vm.sendCode()
            assertEquals(LoginStep.CODE, vm.state.value.step)
            assertEquals(listOf("0712345678"), auth.otpRequests)
            vm.onCodeChange("00a000"); assertEquals("00000", vm.state.value.code) // non-digits stripped
            vm.verify(); assertEquals(LoginError.INVALID_CODE, vm.state.value.error) // too short
            vm.onCodeChange("000000"); vm.verify()
            awaitItem()
            assertEquals("0712345678" to "000000", auth.verifies.single())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun wrong_code_and_offline_show_specific_errors() = runTest {
        val auth = FakeAuthRepository()
        val vm = LoginViewModel(auth)
        vm.onPhoneChange("0712345678"); vm.sendCode()
        vm.onCodeChange("111111")
        auth.verifyResult = AuthResult.Failure(AuthResult.Kind.INVALID_INPUT)
        vm.verify(); assertEquals(LoginError.INVALID_CODE, vm.state.value.error)
        auth.verifyResult = AuthResult.Failure(AuthResult.Kind.NETWORK)
        vm.verify(); assertEquals(LoginError.NETWORK, vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }
}

class WelcomeAndSettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun cannot_continue_without_consent() = runTest {
        val settings = FakeSettings()
        val vm = WelcomeViewModel(settings, FakeLanguage())
        vm.accept()
        assertFalse(settings.onboarded.first())
        vm.setConsent(true)
        vm.done.test {
            vm.accept()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(settings.onboarded.first())
        assertEquals(Limits.CURRENT_CONSENT_VERSION, settings.consentVersion.first())
    }

    @Test fun language_choice_is_applied() = runTest {
        val lang = FakeLanguage()
        val vm = WelcomeViewModel(FakeSettings(), lang)
        vm.setLanguage("sw")
        assertEquals("sw", lang.tag); assertEquals("sw", vm.state.value.language)
    }

    @Test fun delete_requires_confirmation_and_signals_on_success() = runTest {
        val auth = FakeAuthRepository().apply { loggedInFlow.value = true }
        val vm = SettingsViewModel(auth, FakeLanguage())
        vm.state.test {
            vm.askDelete()
            assertTrue(expectMostRecentItem().confirmingDelete)
            vm.cancelDelete()
            assertFalse(expectMostRecentItem().confirmingDelete)
            vm.askDelete()
            vm.accountDeleted.test {
                vm.confirmDelete()
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun failed_delete_keeps_dialog_open_with_error() = runTest {
        val auth = FakeAuthRepository().apply {
            loggedInFlow.value = true
            deleteResult = AuthResult.Failure(AuthResult.Kind.NETWORK)
        }
        val vm = SettingsViewModel(auth, FakeLanguage())
        vm.state.test {
            vm.askDelete(); vm.confirmDelete()
            val s = expectMostRecentItem()
            assertTrue(s.confirmingDelete)
            assertEquals(AuthResult.Kind.NETWORK, s.deleteError)
            assertFalse(s.busy)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun logout_calls_repository() = runTest {
        val auth = FakeAuthRepository().apply { loggedInFlow.value = true }
        val vm = SettingsViewModel(auth, FakeLanguage())
        vm.logout()
        assertEquals(1, auth.logoutCalls)
    }
}

class ReportsAndTrendViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun my_reports_exposes_all_statuses_newest_first() = runTest {
        // A cold source that has not emitted yet, so "not loaded" is genuinely observable first.
        val source = kotlinx.coroutines.flow.MutableSharedFlow<List<ke.sokobei.app.data.db.PendingReportEntity>>()
        val repo = object : ke.sokobei.app.data.repository.ReportRepository {
            override fun observeReports() = source
            override suspend fun submit(draft: ke.sokobei.app.data.repository.ReportDraft) = error("unused")
        }
        val vm = MyReportsViewModel(repo)
        vm.state.test {
            assertFalse(awaitItem().loaded)
            source.emit(listOf(
                pending("a", 3, ReportStatus.REJECTED).copy(rejectReason = "price out of range"),
                pending("b", 2, ReportStatus.EXPIRED), pending("c", 1, ReportStatus.SYNCED), pending("d", 0),
            ))
            val s = expectMostRecentItem()
            assertTrue(s.loaded)
            assertEquals(listOf(ReportStatus.REJECTED, ReportStatus.EXPIRED, ReportStatus.SYNCED, ReportStatus.PENDING), s.reports.map { it.status })
            assertEquals("price out of range", s.reports.first().rejectReason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun point(day: String) = TrendPointDto(day, "standard", BigDecimal("40"), BigDecimal("50"), BigDecimal("60"), 2)

    @Test fun trend_switches_between_30_and_90_days_and_refreshes_each() = runTest {
        val prices = FakePriceRepository()
        prices.trendFlows["30"] = kotlinx.coroutines.flow.MutableStateFlow(TrendData(listOf(point("d30")), 10))
        prices.trendFlows["90"] = kotlinx.coroutines.flow.MutableStateFlow(TrendData(listOf(point("d90a"), point("d90b")), 20))
        val vm = TrendViewModel(
            SavedStateHandle(mapOf("commodityId" to "maize", "marketId" to "gikomba")),
            FakeCatalogRepository(listOf(gikomba), listOf(maize)), prices,
        )
        vm.state.test {
            var s = expectMostRecentItem()
            assertEquals(30, s.days); assertEquals(1, s.trend!!.points.size)
            assertEquals("Gikomba", s.marketName); assertEquals("Maize" to "Mahindi", s.commodityName)
            vm.selectDays(90)
            s = expectMostRecentItem()
            assertEquals(90, s.days); assertEquals(2, s.trend!!.points.size)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(30, 90), prices.refreshedTrends)
    }
}
