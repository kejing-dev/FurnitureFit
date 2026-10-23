package io.embodit.furniturefit.ui.fit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Divider
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleButton
import com.pico.spatial.ui.design.ToggleButtonDefaults
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.furniturefit.R
import io.embodit.furniturefit.domain.GapKind
import io.embodit.furniturefit.domain.Units
import io.embodit.furniturefit.domain.Verdict
import io.embodit.furniturefit.ui.fit.FitEvent
import io.embodit.furniturefit.ui.fit.FitUiState
import io.embodit.furniturefit.ui.fit.GapUi
import io.embodit.furniturefit.ui.fit.debug.probe
import io.embodit.furniturefit.ui.theme.FitColors
import io.embodit.furniturefit.ui.theme.FitType

private val KeyW = 56.dp
private val KeyH = 48.dp

/** The TagCard of Spatial Ruler: a 3 dp glass rim around a solid plateInk card. */
@Composable
private fun TagCard(width: Float, height: Float, content: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier
                .size(width.dp, height.dp)
                .clip(RoundedCornerShape(20.dp))
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
                .padding(3.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(FitColors.PlateInk)
                .padding(horizontal = 10.dp, vertical = 8.dp)
    ) { content() }
}

/** The verdict badge: a 40 dp disc in the verdict's colour with its glyph in tape ink. */
@Composable
private fun VerdictBadge(verdict: Verdict) {
    val (fill, icon, word) =
        when (verdict) {
            Verdict.FITS -> Triple(PicoTheme.colorScheme.passable, R.drawable.ic_check, R.string.verdict_fits)
            Verdict.DOOR -> Triple(PicoTheme.colorScheme.alert, R.drawable.ic_warning, R.string.verdict_door)
            Verdict.COLLIDES -> Triple(PicoTheme.colorScheme.error, R.drawable.ic_hit, R.string.verdict_collide)
        }
    Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(fill).probe("verdict"), contentAlignment = Alignment.Center) {
        Icon(painter = painterResource(icon), contentDescription = rs(word), modifier = Modifier.size(24.dp), tint = FitColors.TapeInk)
    }
}

@Composable
private fun inkKeyColors() = IconButtonDefaults.iconButtonColors(containerColor = FitColors.OnInkFill, contentColor = FitColors.White)

/**
 * The piece strip (design `panels.pieceStrip`, collapsed): verdict badge · size button · turn 90° ·
 * remove, and one line of hint for the controller stick (v0.1.1). No close key: a tap on the floor
 * deselects.
 */
@Composable
internal fun PieceStrip(state: FitUiState?, onEvent: (FitEvent) -> Unit) {
    TagCard(PanelSizes.STRIP_W, PanelSizes.STRIP_H) {
        val piece = state?.selectedPiece ?: return@TagCard
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VerdictBadge(state.verdict(piece.id))
            SizeButton(text = Units.dims(piece.dims, state.unit), selected = false, onEvent = onEvent, modifier = Modifier.weight(1f))
            IconButton(
                onClick = { onEvent(FitEvent.RotateClicked) },
                modifier = Modifier.probe("rotate"),
                colors = inkKeyColors(),
                size = IconButtonDefaults.iconButtonSize(width = KeyW, height = KeyH),
                enabled = state.dragEnabled,
            ) { Icon(painter = painterResource(R.drawable.ic_rotate90), contentDescription = rs(R.string.action_rotate), modifier = Modifier.size(24.dp), tint = FitColors.White) }
            IconButton(
                onClick = { onEvent(FitEvent.DeleteClicked) },
                modifier = Modifier.probe("remove"),
                colors = inkKeyColors(),
                size = IconButtonDefaults.iconButtonSize(width = KeyW, height = KeyH),
            ) { Icon(painter = painterResource(R.drawable.ic_trash), contentDescription = rs(R.string.action_remove), modifier = Modifier.size(24.dp), tint = FitColors.White) }
        }
        Text(text = rs(R.string.hint_stick), color = FitColors.White.copy(alpha = 0.72f), style = FitType.hint, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(start = 4.dp).probe("hint_stick"))
        }
    }
}

@Composable
private fun SizeButton(text: String, selected: Boolean, onEvent: (FitEvent) -> Unit, modifier: Modifier = Modifier) {
    val label = rs(R.string.action_size)
    val container = if (selected) PicoTheme.colorScheme.interaction else FitColors.OnInkFill
    val content = if (selected) FitColors.TapeInk else FitColors.White
    Button(
        onClick = { onEvent(FitEvent.SizeClicked) },
        modifier = modifier.height(KeyH).semantics { contentDescription = label }.probe("size"),
        size = ButtonDefaults.buttonSize(height = KeyH),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) { Text(text = text, color = content, style = FitType.dims, maxLines = 1, softWrap = false) }
}

/**
 * The strip opened for sizing (design `panels.pieceStrip`, sizing 384 x 312): the same top row, then
 * W / D / H at the left (the row that takes digits in `interaction`) and a 3 x 4 keypad at the right.
 * Digits only change the cells; OK writes the piece and is disabled while a value is out of range.
 */
@Composable
internal fun SizingPanel(state: FitUiState?, onEvent: (FitEvent) -> Unit) {
    TagCard(PanelSizes.SIZING_W, PanelSizes.SIZING_H) {
        val piece = state?.selectedPiece ?: return@TagCard
        val sz = state.sizing ?: return@TagCard
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VerdictBadge(state.verdict(piece.id))
                SizeButton(text = Units.dims(piece.dims, state.unit), selected = true, onEvent = onEvent, modifier = Modifier.weight(1f))
            }
            Divider(color = FitColors.OnInkFill)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.width(168.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val axes = listOf(R.string.axis_w, R.string.axis_d, R.string.axis_h)
                    for (i in 0 until 3) SizeRow(rs(axes[i]), sz.texts[i], state.unit.tag, i == sz.row, sz.rowValid(i, state.unit), i, onEvent)
                }
                Keypad(okEnabled = sz.valid(state.unit), onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun SizeRow(axis: String, value: String, unit: String, selected: Boolean, valid: Boolean, index: Int, onEvent: (FitEvent) -> Unit) {
    val content = if (selected) FitColors.TapeInk else if (valid) FitColors.White else PicoTheme.colorScheme.error
    ToggleButton(
        checked = selected,
        onCheckedChange = { onEvent(FitEvent.SizingRow(index)) },
        modifier = Modifier.fillMaxWidth().height(56.dp).probe("row_$index"),
        colors =
            ToggleButtonDefaults.toggleButtonColors(
                checkedContainerColor = if (valid) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.error,
                checkedContentColor = FitColors.TapeInk,
                uncheckedContainerColor = FitColors.OnInkFill,
                uncheckedContentColor = content,
            ),
        size = ToggleButtonDefaults.toggleButtonSize(width = 168.dp, height = 56.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = axis, color = if (selected) FitColors.TapeInk else FitColors.OnInkSecondary, style = FitType.label, maxLines = 1)
            Spacer(modifier = Modifier.weight(1f))
            Text(text = value.ifEmpty { "–" }, color = content, style = FitType.sizingValue, maxLines = 1, softWrap = false)
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = unit, color = if (selected) FitColors.TapeInk else FitColors.OnInkSecondary, style = FitType.label, maxLines = 1)
        }
    }
}

@Composable
private fun Keypad(okEnabled: Boolean, onEvent: (FitEvent) -> Unit) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("back", "0", "ok"))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (k in r) {
                    val isOk = k == "ok"
                    val enabled = !isOk || okEnabled
                    val container = if (isOk) PicoTheme.colorScheme.interaction else FitColors.OnInkFill
                    val content = if (isOk) FitColors.TapeInk else FitColors.White
                    Button(
                        onClick = { onEvent(FitEvent.SizingKey(k)) },
                        modifier = Modifier.size(56.dp, 48.dp).probe("key_$k"),
                        size = ButtonDefaults.buttonSize(width = 56.dp, height = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(0.dp),
                        enabled = enabled,
                        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
                    ) {
                        when (k) {
                            "back" -> Icon(painter = painterResource(R.drawable.ic_backspace), contentDescription = rs(R.string.key_back), modifier = Modifier.size(24.dp), tint = content)
                            "ok" -> Icon(painter = painterResource(R.drawable.ic_check), contentDescription = rs(R.string.key_ok), modifier = Modifier.size(24.dp), tint = if (enabled) content else content.copy(alpha = PanelSizes.DISABLED_ALPHA))
                            else -> Text(text = k, color = content, style = FitType.key, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A reading plate on a gap tape (design `panels.gapPlate`): one line. 80 cm and more: a passable dot
 * before the figure on plateInk; 5 - 80 cm: the whole pill in `alert` with tape-ink figures.
 */
@Composable
internal fun GapPlate(gap: GapUi?) {
    Box(modifier = Modifier.size(PanelSizes.GAP_W.dp, PanelSizes.GAP_H.dp), contentAlignment = Alignment.Center) {
        if (gap == null) return@Box
        val tight = gap.kind == GapKind.TIGHT
        val fill: Color = if (tight) PicoTheme.colorScheme.alert else FitColors.PlateInk
        val ink = if (tight) FitColors.TapeInk else FitColors.White
        val what = rs(if (tight) R.string.gap_tight else R.string.gap_passable)
        Row(
            modifier = Modifier.clip(CircleShape).background(fill).padding(horizontal = 12.dp, vertical = 6.dp).semantics { contentDescription = "$what ${gap.text}" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!tight) Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(PicoTheme.colorScheme.passable))
            Text(text = gap.text, color = ink, style = FitType.label, maxLines = 1, softWrap = false)
        }
    }
}
