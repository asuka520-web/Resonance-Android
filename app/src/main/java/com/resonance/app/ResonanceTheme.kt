package com.resonance.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Shapes
import androidx.compose.material3.SheetState
import androidx.compose.material3.Typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

internal const val ResonanceBackgroundArgb = 0xFF070B24

internal object ResonancePalette {
    val ink = Color(ResonanceBackgroundArgb)
    val surface = Color(0xFF0F1642)
    val raised = Color(0xFF172154)
    val line = Color(0xFF1E2866)
    val plate = Color(0xFF1A33D6)
    val sand = Color(0xFFF2EBDD)
    val signal = Color(0xFFFF6B3D)
    val muted = Color(0xFF8F9BD0)
}

internal object Space {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x5 = 20.dp
    val x6 = 24.dp
    val x8 = 32.dp
    val touch = 48.dp
    val listCover = 56.dp
    val dockCover = 42.dp
    val heroCover = 96.dp
    val weekTileWidth = 44.dp
    val catalogueDot = 3.dp
    val catalogueDotPitch = 6.dp
    val profileScoreWidth = 140.dp
    val tileGap = 10.dp
    val thin = 1.dp
    val focus = 1.5.dp
    val progressLine = 2.dp
    val logoStroke = 3.dp
    val opticalInset = 5.dp
    val statusDot = 6.dp
    val connectionDot = 7.dp
    val wordmarkGap = 9.dp
    val scoreTickHeight = 10.dp
    val noticeInset = 13.dp
    val scoreBottomInset = 14.dp
    val heartGlyph = 17.dp
    val sectionGap = 18.dp
    val navGlyph = 21.dp
    val nextGlyph = 22.dp
    val shuffleGlyph = 23.dp
    val navHalfWidth = 28.dp
    val navPillHeight = 30.dp
    val favoriteDisk = 40.dp
    val miniHeight = 60.dp
    val trackHeight = 68.dp
    val legacyListHeight = 280.dp
    val restJitter = 1.2.dp
    val largeGrain = 1.3.dp
    val shuffleStroke = 1.75.dp
    val smallGrain = .5.dp
    val looseGrain = .6.dp
    val mainGrain = .85.dp
}

internal object TypeSize {
    val caption = 11.sp
    val secondary = 12.sp
    val action = 13.sp
    val body = 14.sp
    val track = 16.sp
    val title = 22.sp
    val song = 26.sp
    val score = 30.sp
    val genreTileLine = 14.95.sp
    val compactLine = 17.sp
    val lead = 18.sp
    val mediumLine = 19.sp
    val readingLine = 20.sp
    val paragraphLine = 21.sp
    val panelTitle = 24.sp
    val songLine = 28.08.sp
    val largeNumber = 32.sp
    val companionNumber = 54.sp
    val wordmarkTracking = 1.5.sp
    val logoTracking = (-.02).em
}

internal object Corners {
    val cover = RoundedCornerShape(14.dp)
    val dockCover = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(18.dp)
    val search = RoundedCornerShape(24.dp)
    val dock = RoundedCornerShape(28.dp)
    val sheet = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val plate = RoundedCornerShape(32.dp)
    val tag = RoundedCornerShape(6.dp)
    val notice = RoundedCornerShape(22.dp)
}

private val palette = with(ResonancePalette) {
    darkColorScheme(
        primary = signal, onPrimary = ink, primaryContainer = raised, onPrimaryContainer = signal,
        secondary = sand, onSecondary = ink, secondaryContainer = sand, onSecondaryContainer = ink,
        tertiary = muted, onTertiary = ink, tertiaryContainer = raised, onTertiaryContainer = sand,
        background = ink, onBackground = sand, surface = surface, onSurface = sand,
        surfaceVariant = raised, onSurfaceVariant = muted, surfaceTint = signal,
        surfaceContainerLowest = ink, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = raised, surfaceContainerHighest = raised, outline = muted, outlineVariant = line,
        error = signal, onError = ink, errorContainer = raised, onErrorContainer = signal,
        inverseSurface = sand, inverseOnSurface = ink, inversePrimary = plate
    )
}

private val shapes = Shapes(small = Corners.cover, medium = Corners.card, large = Corners.search, extraLarge = Corners.dock)
internal val LocalBricolage = staticCompositionLocalOf<FontFamily> { FontFamily.SansSerif }
private const val BricolageAsset = "fonts/BricolageGrotesque.ttf"
private val baseTypography = Typography()

@Composable
internal fun ResonanceTheme(content: @Composable () -> Unit) {
    val assets = LocalContext.current.assets
    val font = remember(assets) {
        FontFamily(*listOf(FontWeight.Medium, FontWeight.Bold, FontWeight.ExtraBold).map { weight ->
            Font(BricolageAsset, assets, weight = weight,
                variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
        }.toTypedArray())
    }
    val typography = remember(font) {
        baseTypography.copy(
            titleLarge = baseTypography.titleLarge.copy(fontFamily = font, fontWeight = FontWeight.ExtraBold),
            titleMedium = baseTypography.titleMedium.copy(fontFamily = font, fontWeight = FontWeight.Bold),
            bodyMedium = baseTypography.bodyMedium.copy(fontFamily = font, fontFeatureSettings = "tnum")
        )
    }
    val sand = LocalSandBudget.current ?: remember { SandBudget() }
    CompositionLocalProvider(LocalBricolage provides font, LocalSandBudget provides sand) {
        MaterialTheme(colorScheme = palette, typography = typography, shapes = shapes, content = content)
    }
}

internal fun Modifier.resonancePanel(): Modifier =
    clip(Corners.card).background(ResonancePalette.surface).border(Space.thin, ResonancePalette.line, Corners.card)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SheetScaffold(onDismissRequest: () -> Unit, sheetState: SheetState, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest, modifier, sheetState, shape = Corners.sheet,
        containerColor = ResonancePalette.ink, contentColor = ResonancePalette.sand,
        dragHandle = { BottomSheetDefaults.DragHandle(color = ResonancePalette.muted) },
        content = {
            val permission = rememberMotionEnvironment()
            CompositionLocalProvider(LocalResonanceMotion provides permission.enabled) { content() }
        })
}

@Composable
internal fun ResonanceDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val permission = rememberMotionEnvironment()
        CompositionLocalProvider(LocalResonanceMotion provides permission.enabled) {
            Surface(Modifier.fillMaxWidth(), color = ResonancePalette.surface, contentColor = ResonancePalette.sand, shape = Corners.dock) {
                Column(Modifier.padding(Space.x6), verticalArrangement = Arrangement.spacedBy(Space.x3)) {
                    Text(title, fontSize = TypeSize.title, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif)
                    content()
                }
            }
        }
    }
}
