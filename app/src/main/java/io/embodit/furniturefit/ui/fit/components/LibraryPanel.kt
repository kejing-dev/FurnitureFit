package io.embodit.furniturefit.ui.fit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.furniturefit.R
import io.embodit.furniturefit.domain.Templates
import io.embodit.furniturefit.domain.Units
import io.embodit.furniturefit.ui.fit.FitEvent
import io.embodit.furniturefit.ui.fit.FitUiState
import io.embodit.furniturefit.ui.fit.debug.probe
import io.embodit.furniturefit.ui.theme.FitType

private val KeyW = 56.dp
private val KeyH = 48.dp
private val TileW = 88.dp
private val TileH = 64.dp

/**
 * The library (design `panels.library`), the only resident panel: undo · (n/12 from 10 pieces) · more,
 * then eleven tiles of icon + size. A tap on a tile puts that piece on the floor in front of you; no
 * confirmation. While starting the tile area is a spinner; while scanning / blocked / elsewhere the
 * tiles are disabled; at 12 pieces they look disabled but answer with the "Max 12" snackbar.
 */
@Composable
internal fun LibraryPanel(state: FitUiState?, onEvent: (FitEvent) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier =
            Modifier
                .size(PanelSizes.LIBRARY_W.dp, PanelSizes.LIBRARY_H.dp)
                .clip(shape)
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
                .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state == null) return@Column
        val ink = PicoTheme.colorScheme.labelPrimary
        val keysOn = !state.starting && !state.blocked
        Row(modifier = Modifier.fillMaxWidth().height(KeyH), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onEvent(FitEvent.UndoClicked) },
                modifier = Modifier.probe("undo"),
                colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
                size = IconButtonDefaults.iconButtonSize(width = KeyW, height = KeyH),
                enabled = keysOn && state.canUndo,
            ) {
                Icon(painter = painterResource(R.drawable.ic_undo), contentDescription = rs(R.string.action_undo), modifier = Modifier.size(24.dp), tint = if (keysOn && state.canUndo) ink else PicoTheme.colorScheme.labelQuaternary)
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (state.count >= 10) Text(text = "${state.count}/${Templates.MAX_PIECES}", color = ink, style = FitType.label, maxLines = 1)
            }
            IconButton(
                onClick = { onEvent(FitEvent.MoreClicked) },
                modifier = Modifier.probe("more"),
                colors = IconButtonDefaults.iconButtonColors(containerColor = if (state.menuOpen) PicoTheme.colorScheme.fillSecondary else PicoTheme.colorScheme.fillTertiary, contentColor = ink),
                size = IconButtonDefaults.iconButtonSize(width = KeyW, height = KeyH),
                enabled = keysOn,
            ) {
                Icon(painter = painterResource(R.drawable.ic_more), contentDescription = rs(R.string.action_more), modifier = Modifier.size(24.dp), tint = if (keysOn) ink else PicoTheme.colorScheme.labelQuaternary)
            }
        }
        if (state.starting) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val starting = rs(R.string.starting)
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp).semantics { contentDescription = starting },
                    color = ink,
                    progressSize = CircularProgressDefaults.circleProgressSize(32.dp),
                )
            }
            return@Column
        }
        val rows = Templates.all.chunked(4)
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in row) Tile(templateId = t.id, state = state, onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun Tile(templateId: String, state: FitUiState, onEvent: (FitEvent) -> Unit) {
    val t = Templates.byId(templateId)
    val enabled = state.tilesEnabled
    val limitLook = enabled && state.atLimit
    val ink = PicoTheme.colorScheme.labelPrimary
    val name = templateName(t)
    Button(
        onClick = { onEvent(FitEvent.TileClicked(templateId)) },
        modifier = Modifier.size(TileW, TileH).alpha(if (limitLook) PanelSizes.DISABLED_ALPHA else 1f).semantics { contentDescription = name }.probe("tile_$templateId"),
        size = ButtonDefaults.buttonSize(width = TileW, height = TileH),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(4.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val tint = if (enabled) ink else PicoTheme.colorScheme.labelQuaternary
            Icon(painter = painterResource(templateIcon(templateId)), contentDescription = null, modifier = Modifier.size(32.dp), tint = tint)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = Units.dims(t.dims, state.unit).substringBeforeLast('×'), color = tint, style = FitType.label, maxLines = 1, softWrap = false)
        }
    }
}
