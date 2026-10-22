package io.embodit.furniturefit.ui.fit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.furniturefit.R
import io.embodit.furniturefit.domain.LanguagePref
import io.embodit.furniturefit.domain.LengthUnit
import io.embodit.furniturefit.domain.Look
import io.embodit.furniturefit.ui.fit.FitEvent
import io.embodit.furniturefit.ui.fit.FitUiState
import io.embodit.furniturefit.ui.fit.SnackKind
import io.embodit.furniturefit.ui.fit.SnackUi
import io.embodit.furniturefit.ui.fit.debug.probe
import io.embodit.furniturefit.ui.theme.FitColors
import io.embodit.furniturefit.ui.theme.FitType

private val RowH = 48.dp

/**
 * The More menu (design `panels.moreMenu`): look, language and unit segments that act in place (the
 * menu stays open), then Clear all (disabled with no furniture) and Exit.
 */
@Composable
internal fun MoreMenu(state: FitUiState?, onEvent: (FitEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.MENU_W.dp, PanelSizes.MENU_H.dp)
                .clip(RoundedCornerShape(24.dp))
                .backgroundMaterial(enable = true, style = Material.Thick)
                .then(glassRefresh())
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state == null) return@Column
        val ink = PicoTheme.colorScheme.labelPrimary
        Segments(
            items = listOf(Look.SOLID, Look.WIRE),
            selected = state.look,
            name = { "look_${it.tag}" },
            onSelect = { onEvent(FitEvent.LookSelected(it)) },
        ) { item ->
            val desc = rs(if (item == Look.SOLID) R.string.look_solid else R.string.look_wire)
            Icon(painter = painterResource(if (item == Look.SOLID) R.drawable.ic_look_solid else R.drawable.ic_look_wire), contentDescription = desc, modifier = Modifier.size(22.dp), tint = ink)
        }
        Segments(
            items = LanguagePref.entries,
            selected = state.settings.language,
            name = { "lang_${it.tag}" },
            onSelect = { onEvent(FitEvent.LanguageSelected(it)) },
        ) { item ->
            when (item) {
                LanguagePref.SYSTEM -> Icon(painter = painterResource(R.drawable.ic_globe), contentDescription = rs(R.string.lang_system), modifier = Modifier.size(20.dp), tint = ink)
                LanguagePref.ZH -> Text(text = rs(R.string.lang_zh), color = ink, style = FitType.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                LanguagePref.EN -> {
                    val english = rs(R.string.lang_en)
                    Text(text = rs(R.string.lang_en_short), color = ink, style = FitType.label, maxLines = 1, softWrap = false, modifier = Modifier.semantics { contentDescription = english })
                }
            }
        }
        Segments(
            items = LengthUnit.entries,
            selected = state.unit,
            name = { "unit_${it.tag}" },
            onSelect = { onEvent(FitEvent.UnitSelected(it)) },
        ) { item -> Text(text = rs(if (item == LengthUnit.CM) R.string.unit_cm else R.string.unit_in), color = ink, style = FitType.label, maxLines = 1) }
        Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canClear = state.pieces.isNotEmpty()
            val clearInk = if (canClear) ink else PicoTheme.colorScheme.labelQuaternary
            Button(
                onClick = { onEvent(FitEvent.ClearAllClicked) },
                modifier = Modifier.weight(1f).probe("menu_clear"),
                size = ButtonDefaults.Regular.copy(height = RowH),
                shape = CircleShape,
                enabled = canClear,
                colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = clearInk),
            ) {
                Icon(painter = painterResource(R.drawable.ic_trash), contentDescription = null, modifier = Modifier.size(20.dp), tint = clearInk)
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = rs(R.string.menu_clear), color = clearInk, maxLines = 1)
            }
            IconButton(
                onClick = { onEvent(FitEvent.ExitClicked) },
                modifier = Modifier.probe("menu_exit"),
                colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
                size = IconButtonDefaults.iconButtonSize(width = 56.dp, height = RowH),
            ) { Icon(painter = painterResource(R.drawable.ic_exit), contentDescription = rs(R.string.menu_exit), modifier = Modifier.size(24.dp), tint = ink) }
        }
    }
}

@Composable
private fun <T> Segments(items: List<T>, selected: T, name: (T) -> String, onSelect: (T) -> Unit, title: @Composable (T) -> Unit) {
    SegmentControl(modifier = Modifier.fillMaxWidth().height(RowH), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        val w = ((PanelSizes.MENU_W - 16f - 8f) / items.size).dp
        items.forEach { item ->
            SegmentItem(
                selected = item == selected,
                onClick = { onSelect(item) },
                modifier = Modifier.width(w).fillMaxHeight().probe(name(item)),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = { title(item) },
            )
        }
    }
}

/** Dialog buttons: 48 dp, capsule, icon + one word (Spatial Ruler). */
@Composable
private fun DialogButton(name: String, iconRes: Int, text: String, container: Color, content: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.probe(name),
        size = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = 120.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, color = content, maxLines = 1)
    }
}

/**
 * The two dialogs (design `panels.clearDialog` / `blockedDialog`), built to AlertDialog metrics on a
 * plain attachment panel (AlertDialog composes nothing in a Stage, Spatial Ruler): Thick glass, 32 dp
 * padding, 32 dp corners. [blocked] wins.
 */
@Composable
internal fun DialogPanel(clear: Boolean, blocked: Boolean, count: Int, onEvent: (FitEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.DIALOG_W.dp, PanelSizes.DIALOG_H.dp)
                .clip(RoundedCornerShape(32.dp))
                .backgroundMaterial(enable = true, style = Material.Thick)
                .then(glassRefresh())
                .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!clear && !blocked) return@Column
        val ink = PicoTheme.colorScheme.labelPrimary
        if (blocked) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painter = painterResource(R.drawable.ic_unavailable), contentDescription = null, modifier = Modifier.size(32.dp), tint = ink)
                Text(text = rs(R.string.blocked_title), color = ink, style = FitType.title, maxLines = 1)
            }
            Text(text = rs(R.string.blocked_body), color = ink, style = FitType.body)
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                DialogButton("blocked_exit", R.drawable.ic_exit, rs(R.string.action_exit), PicoTheme.colorScheme.fillTertiary, ink) { onEvent(FitEvent.BlockedExit) }
                DialogButton("blocked_retry", R.drawable.ic_retry, rs(R.string.action_retry), PicoTheme.colorScheme.interaction, FitColors.TapeInk) { onEvent(FitEvent.BlockedRetry) }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painter = painterResource(R.drawable.ic_trash), contentDescription = rs(R.string.menu_clear), modifier = Modifier.size(32.dp), tint = ink)
                Icon(painter = painterResource(R.drawable.fu_sofa3), contentDescription = null, modifier = Modifier.size(32.dp), tint = ink)
                Text(text = count.toString(), color = ink, style = FitType.title, maxLines = 1)
            }
            Text(text = rs(R.string.dialog_clear_body), color = ink, style = FitType.body)
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                DialogButton("dialog_cancel", R.drawable.ic_close, rs(R.string.action_cancel), PicoTheme.colorScheme.fillTertiary, ink) { onEvent(FitEvent.DialogDismissed) }
                DialogButton("dialog_confirm", R.drawable.ic_trash, rs(R.string.action_clear), PicoTheme.colorScheme.error, FitColors.TapeInk) { onEvent(FitEvent.ClearConfirmed) }
            }
        }
    }
}

/**
 * The snackbar (design `panels.snackbar`): Thick glass capsule, icon 24 + one short sentence +
 * optional action. SnackbarHost is not supported in a Stage (Spatial Ruler), so this is a panel.
 */
@Composable
internal fun SnackPanel(snack: SnackUi?, onEvent: (FitEvent) -> Unit) {
    Box(modifier = Modifier.size(PanelSizes.SNACK_W.dp, PanelSizes.SNACK_H.dp), contentAlignment = Alignment.Center) {
        if (snack == null) return@Box
        val ink = PicoTheme.colorScheme.labelPrimary
        val (icon, text, action) =
            when (snack.kind) {
                SnackKind.SCANNING -> Triple(0, rs(R.string.snack_scanning), null)
                SnackKind.NO_FLOOR -> Triple(0, rs(R.string.snack_no_floor), null)
                SnackKind.RESTORED -> Triple(R.drawable.ic_check, rq(R.plurals.snack_restored, snack.count), null)
                SnackKind.ELSEWHERE -> Triple(R.drawable.ic_warning, rs(R.string.snack_elsewhere), R.string.action_clear to R.drawable.ic_trash)
                SnackKind.UNSAVED -> Triple(R.drawable.ic_warning, rs(R.string.snack_unsaved), R.string.action_retry to R.drawable.ic_retry)
                SnackKind.LIMIT -> Triple(R.drawable.ic_warning, rs(R.string.snack_limit), null)
                SnackKind.LOST -> Triple(R.drawable.ic_unavailable, rs(R.string.snack_lost), null)
                SnackKind.CLEARED -> Triple(R.drawable.ic_check, rs(R.string.snack_cleared), null)
            }
        Row(
            modifier =
                Modifier
                    .widthIn(min = 240.dp, max = PanelSizes.SNACK_W.dp)
                    .height(PanelSizes.SNACK_H.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                .then(glassRefresh())
                    .probe("snackbar")
                    .padding(start = 16.dp, end = if (action != null) 8.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon == 0) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = ink, progressSize = CircularProgressDefaults.circleProgressSize(20.dp))
            } else {
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = ink)
            }
            Text(text = text, color = ink, style = FitType.dims, maxLines = 1, softWrap = false)
            if (action != null) {
                Button(
                    onClick = { onEvent(FitEvent.SnackActionClicked) },
                    modifier = Modifier.probe("snackbar_action"),
                    size = ButtonDefaults.Regular.copy(height = 40.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = FitColors.TapeInk),
                ) {
                    Icon(painter = painterResource(action.second), contentDescription = null, modifier = Modifier.size(18.dp), tint = FitColors.TapeInk)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = rs(action.first), color = FitColors.TapeInk, maxLines = 1)
                }
            }
        }
    }
}

/** The one-step coachmark beside the library (design `panels.coachmark`): pictogram, "Hold and drag to a wall", tick. */
@Composable
internal fun CoachPanel(visible: Boolean, onEvent: (FitEvent) -> Unit) {
    Row(
        modifier =
            Modifier
                .size(PanelSizes.COACH_W.dp, PanelSizes.COACH_H.dp)
                .clip(RoundedCornerShape(24.dp))
                .backgroundMaterial(enable = true, style = Material.Thick)
                .then(glassRefresh())
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!visible) return@Row
        val ink = PicoTheme.colorScheme.labelPrimary
        Icon(painter = painterResource(R.drawable.ic_hand_drag), contentDescription = null, modifier = Modifier.size(40.dp), tint = ink)
        Text(text = rs(R.string.coach), color = ink, style = FitType.body, maxLines = 2, modifier = Modifier.weight(1f))
        IconButton(
            onClick = { onEvent(FitEvent.CoachDismissed) },
            modifier = Modifier.probe("coach_ok"),
            colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = FitColors.TapeInk),
            size = IconButtonDefaults.iconButtonSize(width = 48.dp, height = 48.dp),
        ) { Icon(painter = painterResource(R.drawable.ic_check), contentDescription = rs(R.string.coach_ok), modifier = Modifier.size(24.dp), tint = FitColors.TapeInk) }
    }
}
