package ke.sokobei.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF1B5E20)
private val Amber = Color(0xFFFFB300)

private val Light = lightColorScheme(
    primary = Green, onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9), onPrimaryContainer = Color(0xFF0B2E0F),
    secondary = Color(0xFF8D6E00), tertiary = Amber,
    error = Color(0xFFB3261E),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF81C784), onPrimary = Color(0xFF00390D),
    primaryContainer = Color(0xFF1B5E20), onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFFFFCA28), tertiary = Amber,
)

/** Slightly larger than Material defaults: readable on small, low-DPI screens. */
private val AppTypography = Typography(
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium),
    headlineSmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
)

@Composable
fun SokoBeiTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
}
