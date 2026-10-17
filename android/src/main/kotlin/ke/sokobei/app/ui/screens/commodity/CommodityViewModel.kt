package ke.sokobei.app.ui.screens.commodity

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ke.sokobei.app.data.db.CommodityEntity
import ke.sokobei.app.data.db.CommodityPriceEntity
import ke.sokobei.app.data.repository.CatalogRepository
import ke.sokobei.app.data.repository.PriceRepository
import ke.sokobei.app.data.repository.RefreshResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CommodityUiState(
    val commodity: CommodityEntity? = null,
    /** Cheapest first; markets without a median today go last. */
    val prices: List<CommodityPriceEntity> = emptyList(),
    val updatedMs: Long? = null,
    val refreshing: Boolean = false,
    val lastRefresh: RefreshResult? = null,
)

fun sortByPrice(items: List<CommodityPriceEntity>): List<CommodityPriceEntity> =
    items.sortedWith(
        compareBy<CommodityPriceEntity> { it.medianTodayPerKg == null }
            .thenBy { it.medianTodayPerKg }
            .thenBy { it.marketName },
    )

@HiltViewModel
class CommodityViewModel @Inject constructor(
    savedState: SavedStateHandle,
    catalog: CatalogRepository,
    private val prices: PriceRepository,
) : ViewModel() {
    val commodityId: String = checkNotNull(savedState["commodityId"])

    private val refreshing = MutableStateFlow(false)
    private val lastRefresh = MutableStateFlow<RefreshResult?>(null)

    val state: StateFlow<CommodityUiState> = combine(
        catalog.commodities, prices.commodityPrices(commodityId), prices.commodityPricesUpdatedMs(commodityId),
        refreshing, lastRefresh,
    ) { commodities, p, updated, busy, result ->
        CommodityUiState(commodities.firstOrNull { it.commodityId == commodityId }, sortByPrice(p), updated, busy, result)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommodityUiState())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            lastRefresh.value = prices.refreshCommodityPrices(commodityId)
            refreshing.value = false
        }
    }
}
