package ke.sokobei.app.ui.screens.commodity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import ke.sokobei.app.ui.components.EmptyState
import ke.sokobei.app.ui.components.LabeledRow
import ke.sokobei.app.ui.components.RefreshBanner
import ke.sokobei.app.ui.components.agoText
import ke.sokobei.app.ui.components.kesPerKg
import ke.sokobei.app.ui.components.localizedName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommodityScreen(
    onBack: () -> Unit,
    onTrend: (marketId: String, grade: String) -> Unit,
    vm: CommodityViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.commodity?.let { localizedName(it.nameEn, it.nameSw) } ?: stringResource(R.string.commodity)) },
                navigationIcon = {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cheapest_first), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.prices_last_updated, agoText(s.updatedMs)), style = MaterialTheme.typography.bodySmall)
            RefreshBanner(s.lastRefresh)
            PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                if (s.prices.isEmpty()) {
                    EmptyState(stringResource(if (s.refreshing) R.string.loading else R.string.no_prices_yet), stringResource(R.string.retry), vm::refresh)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize().testTag("commodity_list")) {
                        items(s.prices, key = { "${it.marketId}:${it.grade}" }) { p ->
                            Card(Modifier.fillMaxWidth().clickable { onTrend(p.marketId, p.grade) }) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(p.marketName, style = MaterialTheme.typography.titleMedium)
                                    Text("${p.county} - ${p.grade}", style = MaterialTheme.typography.bodySmall)
                                    Text(kesPerKg(p.medianTodayPerKg), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                                    LabeledRow(stringResource(R.string.last_price), kesPerKg(p.lastPricePerKg))
                                    LabeledRow(stringResource(R.string.reports_label), p.reportsToday.toString())
                                    Text(agoText(p.updatedAtMs), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
