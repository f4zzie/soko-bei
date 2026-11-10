package ke.sokobei.app.ui.screens.trend

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ke.sokobei.app.R
import ke.sokobei.app.ui.components.money
import ke.sokobei.shared.TrendPointDto
import java.time.LocalDate

/** Pure geometry so it can be unit-tested without a canvas. Coordinates are in [0,1], y=0 is the top. */
data class ChartGeometry(
    val minValue: Float,
    val maxValue: Float,
    val xs: List<Float>,
    val minYs: List<Float>,
    val medianYs: List<Float>,
    val maxYs: List<Float>,
    val clipped: Boolean = false,
)

fun chartGeometry(points: List<TrendPointDto>, focusMedian: Boolean = false): ChartGeometry? {
    if (points.isEmpty()) return null
    val medianLow = points.minOf { it.medianPerKg.toFloat() }
    val medianHigh = points.maxOf { it.medianPerKg.toFloat() }
    val padding = maxOf((medianHigh - medianLow) * .12f, medianLow * .05f, 1f)
    val lo = if (focusMedian) (medianLow - padding).coerceAtLeast(0f) else points.minOf { it.minPerKg.toFloat() }
    val hi = if (focusMedian) medianHigh + padding else points.maxOf { it.maxPerKg.toFloat() }
    val span = (hi - lo).takeIf { it > 0f } ?: 1f
    fun y(v: Float) = (1f - (v - lo) / span).coerceIn(0f, 1f)
    val n = points.size
    val days = points.map { LocalDate.parse(it.day).toEpochDay() }
    val firstDay = days.min()
    val daySpan = days.max() - firstDay
    return ChartGeometry(
        minValue = lo, maxValue = hi,
        xs = points.indices.map {
            if (daySpan > 0) (days[it] - firstDay).toFloat() / daySpan
            else if (n == 1) 0.5f else it / (n - 1).toFloat()
        },
        minYs = points.map { y(it.minPerKg.toFloat()) },
        medianYs = points.map { y(it.medianPerKg.toFloat()) },
        maxYs = points.map { y(it.maxPerKg.toFloat()) },
        clipped = points.any { it.minPerKg.toFloat() < lo || it.maxPerKg.toFloat() > hi },
    )
}

/** Median line with a translucent min..max band, drawn directly on a Compose Canvas. */
@Composable
fun TrendChart(points: List<TrendPointDto>, description: String, modifier: Modifier = Modifier, focusMedian: Boolean = false) {
    val geo = chartGeometry(points, focusMedian) ?: return
    val line = MaterialTheme.colorScheme.primary
    val band = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val clippingNote = if (geo.clipped) stringResource(R.string.chart_clipped) else ""

    Column(modifier.fillMaxWidth().semantics { contentDescription = listOf(description, clippingNote).filter { it.isNotEmpty() }.joinToString(" ") }) {
        Text(stringResource(R.string.chart_axis_unit), style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(64.dp).height(220.dp).padding(vertical = 4.dp), verticalArrangement = Arrangement.SpaceBetween) {
                for (i in 0..4) {
                    val value = geo.maxValue - (geo.maxValue - geo.minValue) * i / 4
                    Text(money(value.toBigDecimal()), style = MaterialTheme.typography.labelSmall)
                }
            }
            Canvas(Modifier.weight(1f).height(220.dp)) {
                val padX = 8.dp.toPx()
                val padY = 12.dp.toPx()
                val w = size.width - 2 * padX
                val h = size.height - 2 * padY
                fun px(x: Float) = padX + x * w
                fun py(y: Float) = padY + y * h

                for (i in 0..4) {
                    val gy = py(i / 4f)
                    drawLine(grid, Offset(padX, gy), Offset(padX + w, gy), strokeWidth = 1.dp.toPx())
                }

                if (geo.xs.size >= 2) {
                    val bandPath = Path().apply {
                        moveTo(px(geo.xs.first()), py(geo.maxYs.first()))
                        for (i in 1 until geo.xs.size) lineTo(px(geo.xs[i]), py(geo.maxYs[i]))
                        for (i in geo.xs.indices.reversed()) lineTo(px(geo.xs[i]), py(geo.minYs[i]))
                        close()
                    }
                    drawPath(bandPath, band)
                    val medianPath = Path().apply {
                        moveTo(px(geo.xs.first()), py(geo.medianYs.first()))
                        for (i in 1 until geo.xs.size) lineTo(px(geo.xs[i]), py(geo.medianYs[i]))
                    }
                    drawPath(medianPath, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                } else {
                    // Single day: draw the range as a vertical bar and the median as a dot.
                    drawLine(band, Offset(px(geo.xs[0]), py(geo.maxYs[0])), Offset(px(geo.xs[0]), py(geo.minYs[0])), strokeWidth = 12.dp.toPx())
                }
                // Dots on the median series (also the only mark when there is one day).
                val dots = if (geo.xs.size <= 30) geo.xs.indices.toList() else listOf(0, geo.xs.lastIndex)
                dots.forEach { i ->
                    drawCircle(line, radius = 3.5.dp.toPx(), center = Offset(px(geo.xs[i]), py(geo.medianYs[i])))
                }
            }
        }
        if (clippingNote.isNotEmpty()) Text(clippingNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
