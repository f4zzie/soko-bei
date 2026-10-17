package ke.sokobei.app.ui.screens.report

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ke.sokobei.app.data.db.CommodityEntity
import ke.sokobei.app.data.db.MarketEntity
import ke.sokobei.app.data.repository.AppSettings
import ke.sokobei.app.data.repository.CatalogRepository
import ke.sokobei.app.data.repository.ReportDraft
import ke.sokobei.app.data.repository.ReportRepository
import ke.sokobei.app.ui.LanguageController
import ke.sokobei.shared.Limits
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

private data class Inputs(
    val marketId: String? = null,
    val commodityId: String? = null,
    val grade: String? = null,
    val unit: String? = null,
    val quantity: String = "1",
    val price: String = "",
)

data class ReportFormState(
    val markets: List<MarketEntity> = emptyList(),
    val commodities: List<CommodityEntity> = emptyList(),
    val marketId: String? = null,
    val commodityId: String? = null,
    val grade: String? = null,
    val unit: String? = null,
    val quantity: String = "1",
    val price: String = "",
    val kgPerUnit: BigDecimal? = null,
    /** Live KES/kg, null until inputs are valid. */
    val pricePerKg: BigDecimal? = null,
    val submitting: Boolean = false,
) {
    val commodity get() = commodities.firstOrNull { it.commodityId == commodityId }
    val market get() = markets.firstOrNull { it.marketId == marketId }
    val quantityInvalid get() = quantity.isNotBlank() && PricePreview.parseQuantity(quantity) == null
    val priceInvalid get() = price.isNotBlank() && PricePreview.parsePrice(price) == null
    val canSubmit get() = !submitting && market != null && commodity != null && grade != null && unit != null &&
        PricePreview.parseQuantity(quantity) != null && PricePreview.parsePrice(price) != null
}

@HiltViewModel
class ReportViewModel @Inject constructor(
    savedState: SavedStateHandle,
    catalog: CatalogRepository,
    private val reports: ReportRepository,
    private val settings: AppSettings,
    private val language: LanguageController,
) : ViewModel() {

    private val inputs = MutableStateFlow(
        Inputs(marketId = savedState["marketId"], commodityId = savedState["commodityId"]),
    )
    private val submitting = MutableStateFlow(false)

    private val _done = Channel<Unit>(Channel.BUFFERED)
    val done = _done.receiveAsFlow()

    val state: StateFlow<ReportFormState> = combine(
        catalog.markets, catalog.commodities, inputs, submitting,
    ) { markets, commodities, i, busy ->
        val commodity = commodities.firstOrNull { it.commodityId == i.commodityId }
        val grade = i.grade?.takeIf { commodity != null && it in commodity.grades } ?: commodity?.defaultGrade()
        val unit = i.unit?.takeIf { u -> commodity?.units?.any { it.unit == u } == true } ?: commodity?.defaultUnit
        val kg = unit?.let { commodity?.kgPerUnit(it) }
        ReportFormState(
            markets = markets, commodities = commodities,
            marketId = i.marketId?.takeIf { id -> markets.any { it.marketId == id } },
            commodityId = commodity?.commodityId,
            grade = grade, unit = unit, quantity = i.quantity, price = i.price,
            kgPerUnit = kg,
            pricePerKg = PricePreview.pricePerKg(i.price, i.quantity, kg),
            submitting = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportFormState())

    init {
        // Default the market to the one being browsed on the home tab.
        if (inputs.value.marketId == null) {
            viewModelScope.launch { settings.selectedMarketId.first()?.let { id -> inputs.update { it.copy(marketId = it.marketId ?: id) } } }
        }
    }

    fun selectMarket(id: String) = inputs.update { it.copy(marketId = id) }
    fun selectCommodity(id: String) = inputs.update { it.copy(commodityId = id, grade = null, unit = null) }
    fun selectGrade(g: String) = inputs.update { it.copy(grade = g) }
    fun selectUnit(u: String) = inputs.update { it.copy(unit = u) }
    fun setQuantity(v: String) = inputs.update { it.copy(quantity = v.take(10)) }
    fun setPrice(v: String) = inputs.update { it.copy(price = v.filter(Char::isDigit).take(9)) }

    /** Saves locally and signals [done] immediately; the network is never involved. */
    fun submit() {
        val s = state.value
        if (!s.canSubmit) return
        val market = s.market ?: return
        val commodity = s.commodity ?: return
        val draft = ReportDraft(
            marketId = market.marketId, marketName = market.name,
            commodityId = commodity.commodityId,
            commodityName = commodity.displayName(language.current() == "sw"),
            grade = s.grade ?: return, unit = s.unit ?: return,
            quantity = PricePreview.parseQuantity(s.quantity) ?: return,
            priceKes = PricePreview.parsePrice(s.price) ?: return,
            pricePerKg = s.pricePerKg,
        )
        submitting.value = true
        viewModelScope.launch {
            reports.submit(draft)
            submitting.value = false
            _done.send(Unit)
        }
    }

    private fun CommodityEntity.defaultGrade() = if (Limits.DEFAULT_GRADE in grades) Limits.DEFAULT_GRADE else grades.firstOrNull()
}
