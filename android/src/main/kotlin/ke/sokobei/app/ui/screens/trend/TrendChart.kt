package ke.sokobei.app.ui.screens.trend

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ke.sokobei.shared.TrendPointDto

/** Pure geometry so it can be unit-tested without a canvas. Coordinates are in [0,1], y=0 is the top. */
data class ChartGeometry(
    val minValue: Float,
    val maxValue: Float,
    val xs: List<Float>,
    val minYs: List<Float>,
    val medianYs: List<Float>,
    val maxYs: List<Float>,
)

fun chartGeometry(points: List<TrendPointDto>): ChartGeometry? {
    if (points.isEmpty()) return null
    val lo = points.minOf { it.minPerKg.toFloat() }
    val hi = points.maxOf { it.maxPerKg.toFloat() }
    val span = (hi - lo).takeIf { it > 0f } ?: 1f
    fun y(v: Float) = 1f - (v - lo) / span
    val n = points.size
    return ChartGeometry(
        minValue = lo, maxValue = hi,
        xs = points.indices.map { if (n == 1) 0.5f else it / (n - 1).toFloat() },
        minYs = points.map { y(it.minPerKg.toFloat()) },
        medianYs = points.map { y(it.medianPerKg.toFloat()) },
        maxYs = points.map { y(it.maxPerKg.toFloat()) },
    )
}

/** Median line with a translucent min..max band, drawn directly on a Compose Canvas. */
@Composable
fun TrendChart(points: List<TrendPointDto>, description: String, modifier: Modifier = Modifier) {
    val geo = chartGeometry(points) ?: return
    val line = MaterialTheme.colorScheme.primary
    val band = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val grid = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier.fillMaxWidth().height(220.dp).semantics { contentDescription = description },
    ) {
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
        geo.xs.indices.forEach { i ->
            drawCircle(line, radius = 3.5.dp.toPx(), center = Offset(px(geo.xs[i]), py(geo.medianYs[i])))
        }
    }
}
