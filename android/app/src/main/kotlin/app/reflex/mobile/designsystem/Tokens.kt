package app.reflex.mobile.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class HealthPalette(
    val background: Color,
    val secondaryBackground: Color,
    val tertiaryBackground: Color,
    val inputBackground: Color,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val body: Color,
    val border: Color,
    val strongBorder: Color,
    val controlBorder: Color,
    val buttonSecondary: Color,
    val success: Color,
    val successBackground: Color,
    val destructive: Color,
    val destructiveBackground: Color,
)

val HealthLightColors = HealthPalette(
    background = Color.White,
    secondaryBackground = Color(0xFFF2F2F2),
    tertiaryBackground = Color(0xFFEBEBEB),
    inputBackground = Color(0xFFF2F2F2),
    primary = Color.Black,
    secondary = Color(0xFF666666),
    tertiary = Color(0xFFB3B3B3),
    body = Color(0xFF474747),
    border = Color(0xFFE6E6E6),
    strongBorder = Color(0xFFCCCCCC),
    controlBorder = Color(0xFF8C8C8C),
    buttonSecondary = Color(0xFFF2F2F2),
    success = Color(0xFF007A51),
    successBackground = Color(0xFFDBF8EB),
    destructive = Color(0xFFBF1725),
    destructiveBackground = Color(0xFFFEDEE3),
)

val HealthDarkColors = HealthPalette(
    background = Color.Black,
    secondaryBackground = Color(0xFF141414),
    tertiaryBackground = Color(0xFF262626),
    inputBackground = Color(0xFF121212),
    primary = Color.White,
    secondary = Color(0xFF999999),
    tertiary = Color(0xFF4D4D4D),
    body = Color(0xFFB8B8B8),
    border = Color(0xFF1A1A1A),
    strongBorder = Color(0xFF333333),
    controlBorder = Color(0xFF757575),
    buttonSecondary = Color(0xFF1A1A1A),
    success = Color(0xFF53D6A3),
    successBackground = Color(0xFF002218),
    destructive = Color(0xFFFF7C86),
    destructiveBackground = Color(0xFF3D0105),
)

object HealthBrand {
    val top = Color(0xFFFF61AD)
    val bottom = Color(0xFFFF2719)
}

object HealthSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val screen = 20.dp
    val controlHeight = 48.dp
    val panelRadius = 16.dp
}
