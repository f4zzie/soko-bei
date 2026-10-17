package ke.sokobei.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ke.sokobei.app.R
import ke.sokobei.app.data.repository.RefreshResult
import ke.sokobei.app.ui.Ago
import ke.sokobei.app.ui.agoOf
import java.math.BigDecimal
import java.math.RoundingMode

/** Minimum touch target for every interactive element. */
val MinTouch = 48.dp

@Composable
fun isSwahili(): Boolean = LocalConfiguration.current.locales.let { !it.isEmpty && it[0].language == "sw" }

@Composable
fun localizedName(en: String, sw: String): String = if (isSwahili() && sw.isNotBlank()) sw else en

@Composable
fun agoText(thenMs: Long?): String {
    if (thenMs == null) return stringResource(R.string.never_updated)
    val now = remember(thenMs) { System.currentTimeMillis() }
    return when (val a = agoOf(now, thenMs)) {
        Ago.JustNow -> stringResource(R.string.updated_just_now)
        is Ago.Minutes -> pluralStringResource(R.plurals.updated_minutes_ago, a.n, a.n)
        is Ago.Hours -> pluralStringResource(R.plurals.updated_hours_ago, a.n, a.n)
        is Ago.Days -> pluralStringResource(R.plurals.updated_days_ago, a.n, a.n)
    }
}

@Composable
fun kesPerKg(value: BigDecimal?): String =
    if (value == null) stringResource(R.string.no_price)
    else stringResource(R.string.kes_per_kg, value.setScale(2, RoundingMode.HALF_UP).toPlainString())

fun money(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_UP).toPlainString()

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Offline / error notice shown above cached data; cached content stays visible. */
@Composable
fun RefreshBanner(result: RefreshResult?, modifier: Modifier = Modifier) {
    val message = when (result) {
        RefreshResult.OFFLINE -> R.string.banner_offline
        RefreshResult.ERROR -> R.string.banner_server_error
        else -> return
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(stringResource(message), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun EmptyState(title: String, actionLabel: String?, onAction: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction, modifier = Modifier.heightIn(min = MinTouch)) { Text(actionLabel) }
        }
    }
}

@Composable
fun LabeledRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SmallTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick, Modifier.heightIn(min = MinTouch)) { Text(text) }
}
