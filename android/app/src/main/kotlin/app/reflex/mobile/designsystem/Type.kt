package app.reflex.mobile.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object HealthType {
    val display = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 48.sp, lineHeight = 52.sp)
    val title = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 36.sp)
    val section = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp)
    val row = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp)
    val body = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp)
    val smallBody = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 22.4.sp, letterSpacing = 0.13.sp)
    val meta = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.52.sp)
    val caption = TextStyle(fontFamily = FontFamily.Default, fontSize = 13.sp, lineHeight = 20.sp)
    val input = TextStyle(fontFamily = FontFamily.Default, fontSize = 15.sp, lineHeight = 22.sp)
    val footnote = TextStyle(fontFamily = FontFamily.Default, fontSize = 11.sp, lineHeight = 14.sp)
}

val HealthTypography = Typography(
    displayLarge = HealthType.display, displayMedium = HealthType.display, displaySmall = HealthType.title,
    headlineLarge = HealthType.title, headlineMedium = HealthType.section, headlineSmall = HealthType.section,
    titleLarge = HealthType.section, titleMedium = HealthType.row, titleSmall = HealthType.meta,
    bodyLarge = HealthType.body, bodyMedium = HealthType.smallBody, bodySmall = HealthType.caption,
    labelLarge = HealthType.meta, labelMedium = HealthType.meta, labelSmall = HealthType.footnote,
)
