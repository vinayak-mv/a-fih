package app.afih.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Ui {
    // Graphite dark ramp (cinematic, archival)
    val BgDeep = Color(0xFF0F0F10)
    val Bg = Color(0xFF141415)
    val Surface = Color(0xFF19191B)
    val Raised = Color(0xFF232325)
    val Hairline = Color.White.copy(alpha = 0.08f)
    val HairlineStrong = Color.White.copy(alpha = 0.12f)

    val TextPrimary = Color(0xFFEDEDED)
    val TextSecondary = Color(0xFFA1A1A6)
    val TextMuted = Color(0xFF85858B)

    // Warm projection-light accent (used sparingly)
    val Accent = Color(0xFFE0A458)
    val AccentMuted = Color(0xFFB88943)
    val Positive = Color(0xFF4CC38A)
    val Negative = Color(0xFFE5484D)

    object Space {
        val xxs = 4.dp
        val xs = 8.dp
        val sm = 12.dp
        val md = 16.dp
        val lg = 20.dp
        val xl = 24.dp
        val xxl = 32.dp
        val xxxl = 40.dp
        val section = 48.dp
    }

    object Radius {
        val control = 10.dp
        val card = 14.dp
        val panel = 18.dp
        val image = 12.dp
        val small = 6.dp
    }

    val controlHeight = 36.dp
    val sidebarWidth = 248.dp
}

@Composable
fun AfihTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Ui.TextPrimary,
        onPrimary = Ui.BgDeep,
        primaryContainer = Ui.Raised,
        onPrimaryContainer = Ui.TextPrimary,
        secondary = Ui.Raised,
        onSecondary = Ui.TextPrimary,
        background = Ui.Bg,
        onBackground = Ui.TextPrimary,
        surface = Ui.Surface,
        onSurface = Ui.TextPrimary,
        surfaceVariant = Ui.Raised,
        onSurfaceVariant = Ui.TextSecondary,
        outline = Ui.Hairline,
        outlineVariant = Ui.Hairline,
        error = Ui.Negative,
        onError = Ui.TextPrimary,
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 36.sp, color = Ui.TextPrimary),
            headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 32.sp, color = Ui.TextPrimary),
            headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp, color = Ui.TextPrimary),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp, color = Ui.TextPrimary),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, color = Ui.TextPrimary),
            titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, color = Ui.TextPrimary),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, color = Ui.TextPrimary),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, color = Ui.TextSecondary),
            bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, color = Ui.TextSecondary),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, color = Ui.TextSecondary),
            labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, color = Ui.TextMuted),
            labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, color = Ui.TextMuted),
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(Ui.Radius.small),
            small = RoundedCornerShape(Ui.Radius.control),
            medium = RoundedCornerShape(Ui.Radius.card),
            large = RoundedCornerShape(Ui.Radius.panel),
            extraLarge = RoundedCornerShape(Ui.Radius.panel),
        ),
        content = content,
    )
}

@Composable
fun QuietCard(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        color = Ui.Surface,
        contentColor = Ui.TextPrimary,
        shape = RoundedCornerShape(Ui.Radius.card),
        border = BorderStroke(1.dp, Ui.Hairline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = { androidx.compose.foundation.layout.Column(content = content) },
    )
}

@Composable
fun QuietPanel(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        color = Ui.Surface,
        contentColor = Ui.TextPrimary,
        shape = RoundedCornerShape(Ui.Radius.panel),
        border = BorderStroke(1.dp, Ui.Hairline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = { androidx.compose.foundation.layout.Column(content = content) },
    )
}

object Buttons {
    val primaryColors @Composable get() = ButtonDefaults.buttonColors(containerColor = Ui.TextPrimary, contentColor = Ui.BgDeep, disabledContainerColor = Ui.Raised, disabledContentColor = Ui.TextMuted)
    val secondaryColors @Composable get() = ButtonDefaults.outlinedButtonColors(contentColor = Ui.TextPrimary, disabledContentColor = Ui.TextMuted)
    val quietColors @Composable get() = ButtonDefaults.textButtonColors(contentColor = Ui.TextSecondary, disabledContentColor = Ui.TextMuted)
    val accentText @Composable get() = ButtonDefaults.textButtonColors(contentColor = Ui.Accent, disabledContentColor = Ui.TextMuted)
    val controlPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
}
