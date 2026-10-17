package ke.sokobei.app.ui.screens.trend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import ke.sokobei.app.ui.components.MinTouch
import ke.sokobei.app.ui.components.RefreshBanner
import ke.sokobei.app.ui.components.agoText
import ke.sokobei.app.ui.components.isSwahili
import ke.sokobei.app.ui.components.money

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendScreen(onBack: () -> Unit, vm: TrendViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val sw = isSwahili()
    val title = s.commodityName?.let { (en, swName) -> if (sw && swName.isNotBlank()) swName else en } ?: stringResource(R.string.trend)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            s.marketName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(30, 90).forEach { d ->
                    FilterChip(
                        selected = s.days == d, onClick = { vm.selectDays(d) },
                        label = { Text(stringResource(R.string.days_fmt, d)) },
                        modifier = Modifier.heightIn(min = MinTouch).testTag("days_$d"),
                    )
                }
                if (s.loading) CircularProgressIndicator(Modifier.padding(8.dp))
            }
            RefreshBanner(s.lastRefresh)

            val points = s.trend?.points.orEmpty()
            if (points.isEmpty()) {
                EmptyState(stringResource(if (s.loading) R.string.loading else R.string.no_trend_data), stringResource(R.string.retry), vm::refresh, Modifier.heightIn(min = 200.dp))
            } else {
                val low = points.minOf { it.minPerKg }
                val high = points.maxOf { it.maxPerKg }
                val last = points.last()
                val description = stringResource(R.string.chart_description, s.days, money(low), money(high), money(last.medianPerKg))
                TrendChart(points, description, Modifier.testTag("trend_chart"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(points.first().day, style = MaterialTheme.typography.bodySmall)
                    Text(points.last().day, style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.chart_legend), style = MaterialTheme.typography.bodySmall)
                LabeledRow(stringResource(R.string.latest_median), stringResource(R.string.kes_per_kg, money(last.medianPerKg)))
                LabeledRow(stringResource(R.string.period_low), stringResource(R.string.kes_per_kg, money(low)))
                LabeledRow(stringResource(R.string.period_high), stringResource(R.string.kes_per_kg, money(high)))
                s.trend?.let { Text(stringResource(R.string.prices_last_updated, agoText(it.fetchedAtMs)), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
