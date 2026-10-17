package ke.sokobei.app.ui.screens.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ke.sokobei.app.R
import ke.sokobei.app.ui.components.DropdownField
import ke.sokobei.app.ui.components.MinTouch
import ke.sokobei.app.ui.components.Option
import ke.sokobei.app.ui.components.isSwahili
import ke.sokobei.app.ui.components.localizedName
import ke.sokobei.app.ui.components.money

object ReportTags {
    const val MARKET = "report_market"
    const val COMMODITY = "report_commodity"
    const val GRADE = "report_grade"
    const val UNIT = "report_unit"
    const val QUANTITY = "report_quantity"
    const val PRICE = "report_price"
    const val PER_KG = "price_per_kg"
    const val SUBMIT = "report_submit"
}

@Composable
fun ReportScreen(onDone: () -> Unit, onBack: () -> Unit, vm: ReportViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.done.collect { onDone() } }
    ReportForm(state, onBack, vm::selectMarket, vm::selectCommodity, vm::selectGrade, vm::selectUnit, vm::setQuantity, vm::setPrice, vm::submit)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportForm(
    s: ReportFormState,
    onBack: () -> Unit,
    onMarket: (String) -> Unit,
    onCommodity: (String) -> Unit,
    onGrade: (String) -> Unit,
    onUnit: (String) -> Unit,
    onQuantity: (String) -> Unit,
    onPrice: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.report_price)) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DropdownField(
                stringResource(R.string.market), s.market?.let { "${it.name} (${it.county})" }.orEmpty(),
                s.markets.map { Option(it.marketId, "${it.name} (${it.county})") }, onMarket, tag = ReportTags.MARKET,
            )
            DropdownField(
                stringResource(R.string.commodity), s.commodity?.let { localizedName(it.nameEn, it.nameSw) }.orEmpty(),
                s.commodities.map { Option(it.commodityId, localizedName(it.nameEn, it.nameSw)) }, onCommodity, tag = ReportTags.COMMODITY,
            )
            val commodity = s.commodity
            DropdownField(
                stringResource(R.string.grade), s.grade.orEmpty(),
                commodity?.grades.orEmpty().map { Option(it, it) }, onGrade, tag = ReportTags.GRADE, enabled = commodity != null,
            )
            DropdownField(
                stringResource(R.string.unit),
                s.unit?.let { u -> commodity?.kgPerUnit(u)?.let { stringResource(R.string.unit_with_kg, u, it.stripTrailingZeros().toPlainString()) } ?: u }.orEmpty(),
                commodity?.units.orEmpty().map { Option(it.unit, stringResource(R.string.unit_with_kg, it.unit, it.kgPerUnit.stripTrailingZeros().toPlainString())) },
                onUnit, tag = ReportTags.UNIT, enabled = commodity != null,
            )
            OutlinedTextField(
                value = s.quantity, onValueChange = onQuantity, singleLine = true,
                label = { Text(stringResource(R.string.quantity)) },
                isError = s.quantityInvalid,
                supportingText = if (s.quantityInvalid) ({ Text(stringResource(R.string.error_quantity)) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag(ReportTags.QUANTITY),
            )
            OutlinedTextField(
                value = s.price, onValueChange = onPrice, singleLine = true,
                label = { Text(stringResource(R.string.price_kes)) },
                isError = s.priceInvalid,
                supportingText = if (s.priceInvalid) ({ Text(stringResource(R.string.error_price)) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag(ReportTags.PRICE),
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.price_per_kg_title), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        s.pricePerKg?.let { stringResource(R.string.kes_per_kg, money(it)) } ?: stringResource(R.string.price_per_kg_placeholder),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag(ReportTags.PER_KG),
                    )
                }
            }
            Text(stringResource(R.string.offline_note), style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onSubmit, enabled = s.canSubmit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ReportTags.SUBMIT),
            ) { Text(stringResource(R.string.submit_report)) }
        }
    }
}
