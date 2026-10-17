package ke.sokobei.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ke.sokobei.app.data.db.MarketEntity
import ke.sokobei.app.data.db.MarketPriceEntity
import ke.sokobei.app.data.repository.AppSettings
import ke.sokobei.app.data.repository.CatalogRepository
import ke.sokobei.app.data.repository.PriceRepository
import ke.sokobei.app.data.repository.RefreshResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val markets: List<MarketEntity> = emptyList(),
    val selectedMarketId: String? = null,
    val prices: List<MarketPriceEntity> = emptyList(),
    /** When the selected market's prices were last downloaded; null = never (nothing cached). */
    val pricesUpdatedMs: Long? = null,
    val refreshing: Boolean = false,
    val lastRefresh: RefreshResult? = null,
) {
    val selectedMarket get() = markets.firstOrNull { it.marketId == selectedMarketId }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val prices: PriceRepository,
    private val settings: AppSettings,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val lastRefresh = MutableStateFlow<RefreshResult?>(null)

    private val effectiveSelected = combine(catalog.markets, settings.selectedMarketId) { markets, saved ->
        saved?.takeIf { id -> markets.any { it.marketId == id } } ?: markets.firstOrNull()?.marketId
    }.distinctUntilChanged()

    private val priceData = effectiveSelected.flatMapLatest { id ->
        if (id == null) flowOf(emptyList<MarketPriceEntity>() to null as Long?)
        else combine(prices.marketPrices(id), prices.marketPricesUpdatedMs(id)) { p, u -> p to u }
    }

    val state: StateFlow<HomeUiState> = combine(
        catalog.markets, effectiveSelected, priceData, refreshing, lastRefresh,
    ) { markets, selected, (p, updated), busy, result ->
        HomeUiState(markets, selected, p, updated, busy, result)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch { effectiveSelected.filterNotNull().collect { refreshPrices(it) } }
        viewModelScope.launch { lastRefreshCatalog(catalog.refresh()) }
    }

    private fun lastRefreshCatalog(r: RefreshResult) {
        if (r != RefreshResult.OK) lastRefresh.value = r
    }

    fun selectMarket(marketId: String) {
        viewModelScope.launch { settings.setSelectedMarket(marketId) }
    }

    /** Pull-to-refresh / retry: catalog first (cheap, cached), then the selected market's prices. */
    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            val c = catalog.refresh()
            val selected = state.value.selectedMarketId
            val p = if (selected != null) prices.refreshMarketPrices(selected) else c
            lastRefresh.value = if (c != RefreshResult.OK) c else p
            refreshing.value = false
        }
    }

    private suspend fun refreshPrices(marketId: String) {
        refreshing.value = true
        lastRefresh.value = prices.refreshMarketPrices(marketId)
        refreshing.value = false
    }
}
