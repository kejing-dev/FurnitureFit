package io.embodit.furniturefit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.pico.spatial.ui.design.PicoTheme

/**
 * The design's exact colours (design-package `palette`): masking tape, a yellow measuring tape and
 * white size boxes over passthrough. This is the only file with colour literals; the four semantic
 * roles are injected into `PicoTheme` by [FurnitureFitTheme], the brand tokens are used directly.
 */
object FitColors {
    // ---- ColorScheme roles with a fixed design value (the other twelve inherit SpatialUI Vibrant)
    val Error = Color(0xFFFF5F52) // design-style: fixed-figma-color error: collides
    val Alert = Color(0xFFFF8A1F) // design-style: fixed-figma-color alert: blocks the door / too tight to pass
    val Passable = Color(0xFF66E08A) // design-style: fixed-figma-color passable: fits / passable
    val Interaction = Color(0xFFFFC72C) // design-style: fixed-figma-color interaction: selected

    // ---- brand tokens outside ColorScheme
    val MaskingBlue = Color(0xFF3D8BFF) // design-style: fixed-figma-color maskingBlue: footprint tape
    val MaskingBlueEdge = Color(0xFF1F5FCC) // design-style: fixed-figma-color maskingBlueEdge
    val TapeYellow = Color(0xFFFFC72C) // design-style: fixed-figma-color tapeYellow: gap tape blade
    val TapeInk = Color(0xFF16181C) // design-style: fixed-figma-color tapeInk: ticks; content on semantic fills
    val BladeEdge = Color(0xFF0E0F12) // design-style: fixed-figma-color bladeEdge: edge bar outline
    val MeterRed = Color(0xFFE23B2E) // design-style: fixed-figma-color meterRed: zero end of the tape
    val PieceEdge = Color(0xE6FFFFFF) // design-style: fixed-figma-color pieceEdge white 90 %
    val PieceFill = Color(0x24FFFFFF) // design-style: fixed-figma-color pieceFill white 14 %
    val PlateInk = Color(0xF0121417) // design-style: fixed-figma-color plateInk 94 %: reading plate, piece strip card
    val OnInkFill = Color(0x24FFFFFF) // design-style: fixed-figma-color onInkFill white 14 %
    val OnInkSecondary = Color(0xA8FFFFFF) // design-style: fixed-figma-color onInkSecondary white 66 %
    val StaleGrey = Color(0xFF9A9A96) // design-style: fixed-figma-color staleGrey: tracking lost
    val White = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight on plateInk
    val Fabric = Color(0xFFC9B79C) // design-style: fixed-figma-color fabric: soft parts of the looks
    val Wood = Color(0xFFA9825A) // design-style: fixed-figma-color wood: hard parts of the looks
    val Steel = Color(0xFFC9CED6) // design-style: fixed-figma-color steel: the tape's hook
    val Debug = Color(0xFF00E5FF) // design-style: fixed-figma-color debug: obstacle geometry (debug command only)
}

/** Type roles of the design (`typography.roles`): tabular figures everywhere a number is shown. */
object FitType {
    val dims: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val sizingValue: TextStyle
        @Composable get() = PicoTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val label: TextStyle
        @Composable get() = PicoTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val body: TextStyle
        @Composable get() = PicoTheme.typography.bodyLargeMultiline.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)

    val title: TextStyle
        @Composable get() = PicoTheme.typography.headlineLarge.copy(fontSize = 24.sp, lineHeight = 30.sp)

    val key: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")
}
