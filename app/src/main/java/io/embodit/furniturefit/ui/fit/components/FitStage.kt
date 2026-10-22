package io.embodit.furniturefit.ui.fit.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.furniturefit.platform.FitLog
import io.embodit.furniturefit.ui.fit.FitEvent
import io.embodit.furniturefit.ui.fit.FitUiState
import io.embodit.furniturefit.ui.fit.debug.LocalProbePanel
import io.embodit.furniturefit.ui.fit.debug.Probe
import io.embodit.furniturefit.ui.fit.scene.FitScene
import io.embodit.furniturefit.ui.fit.scene.PanelIds

/**
 * The Stage content: one full-size SpatialView with the furniture, the room's tap targets and the panel
 * pool. Every attachment panel is declared once with a fixed size, shown or not ([FitScene] parents and
 * places them). Tap and drag recognizers live in separate pointerInput blocks (Spatial Ruler).
 *
 * Window model: single panel with popups. The library is the one resident panel; the piece strip,
 * plates and coachmark ride on 3D things; menu, dialog and snackbar float.
 */
@Composable
internal fun FitStage(state: FitUiState, scene: FitScene, onEvent: (FitEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { start, value -> scene.onSpatialDrag("start", start, value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", null, value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", null, value) },
                    ) { value -> scene.onSpatialDrag("move", null, value) }
                },
        attachments = {
            AttachmentPanel(PanelIds.LIBRARY) {
                PoolPanel(PanelIds.LIBRARY, scene, pressed = { scene.onLibraryPointer() }) { inView -> LibraryPanel(state = if (inView) state else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.STRIP) {
                PoolPanel(PanelIds.STRIP, scene) { inView -> PieceStrip(state = if (inView) state else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.SIZING) {
                PoolPanel(PanelIds.SIZING, scene) { inView -> SizingPanel(state = if (inView) state else null, onEvent = onEvent) }
            }
            for (i in 0 until PanelIds.GAP_SLOTS) {
                AttachmentPanel(PanelIds.gap(i)) {
                    PoolPanel(PanelIds.gap(i), scene) { inView -> GapPlate(gap = if (inView) scene.gapPlates[i] else null) }
                }
            }
            AttachmentPanel(PanelIds.MENU) {
                PoolPanel(PanelIds.MENU, scene, pressed = { scene.onLibraryPointer() }) { inView -> MoreMenu(state = if (inView) state else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.DIALOG) {
                PoolPanel(PanelIds.DIALOG, scene) { inView -> DialogPanel(clear = inView && state.clearDialog, blocked = inView && state.blocked, count = state.count, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.SNACK) {
                PoolPanel(PanelIds.SNACK, scene) { inView -> SnackPanel(snack = if (inView) state.snack else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.COACH) {
                PoolPanel(PanelIds.COACH, scene) { inView -> CoachPanel(visible = inView && state.coach, onEvent = onEvent) }
            }
        },
        update = { _, attachments ->
            // reading the tick makes this block run again while panel entities are still missing
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel: [content] gets `inView` (the panel has been moved into view; a pooled panel
 * draws only then, Spatial Ruler finding 3). Debug builds log every press as `PTR panel=<id> ev=`.
 */
@Composable
private fun PoolPanel(id: String, scene: FitScene, pressed: (() -> Unit)? = null, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    val logged = Probe.enabled
    val watcher =
        if (logged || pressed != null) {
            Modifier.pointerInput(id, logged) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move) continue
                        if (event.type == PointerEventType.Press) pressed?.invoke()
                        if (logged) {
                            val p = event.changes.firstOrNull()?.position
                            FitLog.i(String.format(java.util.Locale.US, "PTR panel=%s ev=%s x=%.1f y=%.1f", id, event.type, p?.x ?: 0f, p?.y ?: 0f))
                        }
                    }
                }
            }
        } else Modifier
    CompositionLocalProvider(LocalProbePanel provides id) {
        Box(modifier = watcher) { content(inView) }
    }
}
