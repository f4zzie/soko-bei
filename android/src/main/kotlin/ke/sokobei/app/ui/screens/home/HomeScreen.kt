package ke.sokobei.app.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ke.sokobei.app.R
import ke.sokobei.app.data.db.MarketPriceEntity
import ke.sokobei.app.ui.components.DropdownField
import ke.sokobei.app.ui.components.EmptyState
import ke.sokobei.app.ui.components.LabeledRow
import ke.sokobei.app.ui.components.MinTouch
import ke.sokobei.app.ui.components.Option
import ke.sokobei.app.ui.components.RefreshBanner
import ke.sokobei.app.ui.components.agoText
import ke.sokobei.app.ui.components.kesPerKg
import ke.sokobei.app.ui.components.localizedName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCommodity: (commodityId: String) -> Unit,
    onTrend: (commodityId: String, marketId: String, grade: String) -> Unit,
    onReport: (marketId: String?) -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onReport(s.selectedMarketId) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.report_price)) },
                modifier = Modifier.heightIn(min = 56.dp).testTag("fab_report"),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
            DropdownField(
                label = stringResource(R.string.market),
                selectedLabel = s.selectedMarket?.let { "${it.name} (${it.county})" } ?: "",
                options = s.markets.map { Option(it.marketId, "${it.name} (${it.county})") },
                onSelect = vm::selectMarket,
                tag = "market_picker",
                enabled = s.markets.isNotEmpty(),
            )
            Text(
                stringResource(R.string.prices_last_updated, agoText(s.pricesUpdatedMs)),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("last_updated"),
            )
            RefreshBanner(s.lastRefresh)

            PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                if (s.prices.isEmpty()) {
                    EmptyState(
                        title = stringResource(if (s.refreshing) R.string.loading else R.string.no_prices_yet),
                        actionLabel = stringResource(R.string.retry),
                        onAction = vm::refresh,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize().testTag("price_list")) {
                        items(s.prices, key = { "${it.commodityId}:${it.grade}" }) { p ->
                            PriceCard(p, onClick = { onCommodity(p.commodityId) }, onTrend = { onTrend(p.commodityId, p.marketId, p.grade) })
                        }
                        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 80.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PriceCard(p: MarketPriceEntity, onClick: () -> Unit, onTrend: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("price_${p.commodityId}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(localizedName(p.commodityNameEn, p.commodityNameSw), style = MaterialTheme.typography.titleMedium)
                    Text(p.grade, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onTrend, Modifier.heightIn(min = MinTouch).padding(0.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ShowChart, stringResource(R.string.view_trend))
                }
            }
            Text(kesPerKg(p.medianTodayPerKg), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.median_today), style = MaterialTheme.typography.bodySmall)
            LabeledRow(stringResource(R.string.last_price), kesPerKg(p.lastPricePerKg))
            LabeledRow(stringResource(R.string.reports_label), p.reportsToday.toString())
            Text(agoText(p.updatedAtMs), style = MaterialTheme.typography.bodySmall)
        }
    }
}
