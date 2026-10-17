package ke.sokobei.app.ui.screens.trend

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ke.sokobei.app.data.repository.CatalogRepository
import ke.sokobei.app.data.repository.PriceRepository
import ke.sokobei.app.data.repository.RefreshResult
import ke.sokobei.app.data.repository.TrendData
import ke.sokobei.shared.Limits
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrendUiState(
    val commodityName: Pair<String, String>? = null, // (en, sw)
    val marketName: String? = null,
    val days: Int = 30,
    val trend: TrendData? = null,
    val loading: Boolean = false,
    val lastRefresh: RefreshResult? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TrendViewModel @Inject constructor(
    savedState: SavedStateHandle,
    catalog: CatalogRepository,
    private val prices: PriceRepository,
) : ViewModel() {
    val commodityId: String = checkNotNull(savedState["commodityId"])
    val marketId: String = checkNotNull(savedState["marketId"])
    private val grade: String = savedState.get<String>("grade") ?: Limits.DEFAULT_GRADE

    private val days = MutableStateFlow(30)
    private val loading = MutableStateFlow(false)
    private val lastRefresh = MutableStateFlow<RefreshResult?>(null)

    private val trend = days.flatMapLatest { prices.trend(commodityId, marketId, it, grade) }

    val state: StateFlow<TrendUiState> = combine(
        catalog.commodities, catalog.markets, days, trend, combine(loading, lastRefresh) { l, r -> l to r },
    ) { commodities, markets, d, t, (busy, result) ->
        TrendUiState(
            commodityName = commodities.firstOrNull { it.commodityId == commodityId }?.let { it.nameEn to it.nameSw },
            marketName = markets.firstOrNull { it.marketId == marketId }?.name,
            days = d,
            trend = t,
            loading = busy,
            lastRefresh = result,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendUiState())

    init { refresh() }

    fun selectDays(value: Int) {
        if (value == days.value) return
        days.value = value
        refresh()
    }

    fun refresh() {
        val d = days.value
        viewModelScope.launch {
            loading.value = true
            lastRefresh.value = prices.refreshTrend(commodityId, marketId, d, grade)
            loading.value = false
        }
    }
}
