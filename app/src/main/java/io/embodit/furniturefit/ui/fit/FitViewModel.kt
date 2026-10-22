package io.embodit.furniturefit.ui.fit

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.furniturefit.data.AnchorGateway
import io.embodit.furniturefit.data.AnchorOutcome
import io.embodit.furniturefit.data.AnchorRecord
import io.embodit.furniturefit.data.LayoutStore
import io.embodit.furniturefit.domain.AnchorPose
import io.embodit.furniturefit.domain.FitRules
import io.embodit.furniturefit.domain.GapKind
import io.embodit.furniturefit.domain.HeadPose
import io.embodit.furniturefit.domain.P2
import io.embodit.furniturefit.domain.Piece
import io.embodit.furniturefit.domain.RoomModel
import io.embodit.furniturefit.domain.SavedLayout
import io.embodit.furniturefit.domain.SavedPiece
import io.embodit.furniturefit.domain.Settings
import io.embodit.furniturefit.domain.Templates
import io.embodit.furniturefit.domain.Units
import io.embodit.furniturefit.domain.Vec3
import io.embodit.furniturefit.platform.FitGraph
import io.embodit.furniturefit.platform.FitLog
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The whole behaviour of the app (design-package `rules`, `states`, `interactions`): events from the
 * panels and the scene come in through [onEvent], the state goes out through [state]. Pure Kotlin
 * apart from the injected platform services; unit-tested with fakes.
 */
class FitViewModel(
    private val anchors: AnchorGateway,
    private val store: LayoutStore,
    private val head: () -> HeadPose?,
    private val blockReason: () -> String?,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(FitUiState(settings = store.loadSettings()))
    val state: StateFlow<FitUiState> = _state

    private val _effects = MutableSharedFlow<FitEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<FitEffect> = _effects

    private var anchor: AnchorRecord? = null
    private var lockedAxis: Float? = null
    private var nextId = 1
    private val undo = ArrayDeque<List<Piece>>()
    private var timedSnack: SnackUi? = null
    private var timedSnackJob: Job? = null
    private var saveJob: Job? = null
    private var restoreJob: Job? = null
    private var blockJob: Job? = null
    private var saved: SavedLayout? = null
    private var lastLogged: String? = null
    private var rawRoom: RoomModel = RoomModel.EMPTY

    init {
        saved = store.loadLayout()
        if (saved != null && saved!!.pieces.isNotEmpty()) {
            update { it.copy(restore = RestoreState.LOOKING) }
            startRestore()
        } else if (saved != null) {
            saved = null
            store.saveLayout(null)
        }
    }

    // =========================================================================================
    // events
    // =========================================================================================

    fun onEvent(e: FitEvent) {
        val s = _state.value
        // a dialog is modal: only its own buttons act
        if (s.clearDialog && e !is FitEvent.ClearConfirmed && e !is FitEvent.DialogDismissed && !e.isBackground()) return
        // the menu is open: the first press anywhere else only closes it (rules.menuOpen)
        if (s.menuOpen && !e.isMenuOwn() && !e.isBackground()) {
            FitLog.i("TAP consumed reason=menu event=${e.name()}")
            update { it.copy(menuOpen = false) }
            return
        }
        if (!e.isBackground()) FitLog.i("UI ${e.name()}")
        when (e) {
            is FitEvent.TileClicked -> place(e.templateId)
            FitEvent.UndoClicked -> undoLast()
            FitEvent.MoreClicked -> update { it.copy(menuOpen = !it.menuOpen) }
            is FitEvent.PieceTapped -> select(e.id)
            FitEvent.EmptyTapped -> update { it.copy(selected = null, sizing = null) }
            is FitEvent.DragStarted -> {
                if (!s.dragEnabled || s.pieces.none { it.id == e.id }) return
                update { it.copy(dragging = e.id, selected = e.id, sizing = null) }
            }
            is FitEvent.DragEnded -> dragEnded(e.id, e.center)
            FitEvent.RotateClicked -> mutateSelected("rotate") { p, room -> FitRules.snap(FitRules.rotate(p, room), room, _state.value.pieces).first }
            FitEvent.DeleteClicked -> deleteSelected()
            FitEvent.SizeClicked -> toggleSizing()
            is FitEvent.SizingRow -> update { st -> st.copy(sizing = st.sizing?.copy(row = e.row.coerceIn(0, 2), fresh = true)) }
            is FitEvent.SizingKey -> sizingKey(e.key)
            is FitEvent.LookSelected -> settings { it.copy(look = e.look) }
            is FitEvent.LanguageSelected -> settings { it.copy(language = e.language) }
            is FitEvent.UnitSelected -> settings { it.copy(unit = e.unit) }.also { update { st -> st.copy(sizing = null) } }
            FitEvent.ClearAllClicked -> if (s.pieces.isNotEmpty()) update { it.copy(menuOpen = false, clearDialog = true) }
            FitEvent.ClearConfirmed -> clearAll()
            FitEvent.DialogDismissed -> update { it.copy(clearDialog = false) }
            FitEvent.ExitClicked, FitEvent.BlockedExit -> {
                update { it.copy(menuOpen = false) }
                _effects.tryEmit(FitEffect.Exit)
            }
            FitEvent.SnackActionClicked -> snackAction()
            FitEvent.CoachDismissed -> dismissCoach("got_it")
            FitEvent.BlockedRetry -> {
                update { it.copy(blocked = false) }
                watchBlocked()
            }
            FitEvent.SceneReady -> {
                update { it.copy(ready = true) }
                watchBlocked()
            }
            is FitEvent.RoomChanged -> roomChanged(e.room)
            is FitEvent.TrackingChanged -> {
                FitLog.i("TRACK ${if (e.lost) "lost" else "recovered"}")
                update { it.copy(trackingLost = e.lost, dragging = if (e.lost) null else it.dragging) }
            }
            is FitEvent.InsideChanged -> update { it.copy(inside = e.id) }
        }
    }

    private fun FitEvent.isBackground(): Boolean =
        this is FitEvent.SceneReady || this is FitEvent.RoomChanged || this is FitEvent.TrackingChanged || this is FitEvent.InsideChanged

    private fun FitEvent.isMenuOwn(): Boolean =
        this is FitEvent.MoreClicked || this is FitEvent.LookSelected || this is FitEvent.LanguageSelected || this is FitEvent.UnitSelected ||
            this is FitEvent.ClearAllClicked || this is FitEvent.ExitClicked

    private fun FitEvent.name(): String = toString().substringBefore('(').substringAfterLast('.').let { if (this is FitEvent.TileClicked) "tile:$templateId" else if (this is FitEvent.SizingKey) "key:$key" else if (this is FitEvent.PieceTapped) "piece:$id" else it }

    // =========================================================================================
    // placing, selecting, moving
    // =========================================================================================

    private fun place(templateId: String) {
        val s = _state.value
        if (!s.tilesEnabled) {
            FitLog.i("PIECE refused reason=tiles-disabled state=${s.designState}")
            return
        }
        if (s.atLimit) {
            showSnack(SnackUi(SnackKind.LIMIT))
            return
        }
        val pose = head() ?: return
        val template = Templates.byId(templateId)
        val room = roomWithAxis(s.room)
        val placed = FitRules.placement(template, template.dims, pose, room, nextId++)
        pushUndo()
        val first = s.pieces.isEmpty()
        if (first && lockedAxis == null) lockedAxis = rawRoom.axisYaw
        update { st ->
            st.copy(
                pieces = st.pieces + placed,
                selected = placed.id,
                sizing = if (templateId == Templates.CUSTOM) sizingFor(placed, st) else null,
                coach = st.coach || (first && !st.settings.coachSeen),
            )
        }
        FitLog.i(String.format(Locale.US, "PIECE add id=%d t=%s center=(%.3f, %.3f) yaw=%.1f verdict=%s", placed.id, templateId, placed.center.x, placed.center.z, Math.toDegrees(placed.yaw.toDouble()), _state.value.verdict(placed.id)))
        _effects.tryEmit(FitEffect.Feedback("place"))
        if (anchor == null && _state.value.save != SaveState.SAVING) createRoomAnchor(placed) else scheduleSave()
    }

    private fun select(id: Int) {
        val s = _state.value
        if (s.pieces.none { it.id == id }) return
        if (s.restore == RestoreState.ELSEWHERE) return
        update { it.copy(selected = id, sizing = if (it.sizing?.pieceId == id) it.sizing else null) }
    }

    private fun dragEnded(id: Int, center: P2?) {
        val s = _state.value
        if (s.dragging != id) return
        if (center == null || !center.isFinite()) {
            update { it.copy(dragging = null) }
            return
        }
        val piece = s.pieces.firstOrNull { it.id == id } ?: return
        pushUndo()
        val (snapped, sides) = FitRules.snap(piece.copy(center = center), roomWithAxis(s.room), s.pieces)
        if (sides.isNotEmpty()) _effects.tryEmit(FitEffect.Feedback("snap"))
        update { st -> st.copy(dragging = null, selected = id, pieces = st.pieces.map { if (it.id == id) snapped else it }) }
        FitLog.i(String.format(Locale.US, "PIECE move id=%d center=(%.3f, %.3f) snapped=%s verdict=%s", id, snapped.center.x, snapped.center.z, sides.joinToString("+") { it.name.lowercase() }, _state.value.verdict(id)))
        if (_state.value.coach) dismissCoach("first_drag")
        scheduleSave()
    }

    private fun mutateSelected(what: String, change: (Piece, RoomModel) -> Piece) {
        val s = _state.value
        val p = s.selectedPiece ?: return
        if (!s.dragEnabled) return
        pushUndo()
        val next = change(p, roomWithAxis(s.room))
        update { st -> st.copy(pieces = st.pieces.map { if (it.id == p.id) next else it }) }
        FitLog.i(String.format(Locale.US, "PIECE %s id=%d center=(%.3f, %.3f) yaw=%.1f dims=%s verdict=%s", what, p.id, next.center.x, next.center.z, Math.toDegrees(next.yaw.toDouble()), Units.dims(next.dims, s.unit), _state.value.verdict(p.id)))
        scheduleSave()
    }

    private fun deleteSelected() {
        val s = _state.value
        val p = s.selectedPiece ?: return
        pushUndo()
        update { st -> st.copy(pieces = st.pieces.filter { it.id != p.id }, selected = null, sizing = null) }
        FitLog.i("PIECE remove id=${p.id}")
        scheduleSave()
    }

    private fun undoLast() {
        val previous = undo.removeLastOrNull() ?: return
        update { st ->
            st.copy(
                pieces = previous,
                selected = st.selected?.takeIf { id -> previous.any { it.id == id } },
                sizing = null,
                canUndo = undo.isNotEmpty(),
            )
        }
        FitLog.i("UNDO pieces=${previous.size} left=${undo.size}")
        scheduleSave()
    }

    private fun pushUndo() {
        undo.addLast(_state.value.pieces)
        while (undo.size > UNDO_STEPS) undo.removeFirst()
        update { it.copy(canUndo = true) }
    }

    // =========================================================================================
    // sizing (rules.sizing): the keypad edits the cells only; OK writes the piece
    // =========================================================================================

    private fun sizingFor(p: Piece, s: FitUiState): SizingUi {
        val u = s.unit
        return SizingUi(p.id, 0, listOf(Units.whole(p.dims.w, u), Units.whole(p.dims.d, u), Units.whole(p.dims.h, u)).map { it.toString() }, fresh = true)
    }

    private fun toggleSizing() {
        val s = _state.value
        val p = s.selectedPiece ?: return
        update { it.copy(sizing = if (it.sizing != null) null else sizingFor(p, it)) }
    }

    private fun sizingKey(key: String) {
        val s = _state.value
        val sz = s.sizing ?: return
        when (key) {
            "ok" -> {
                val dims = sz.dims(s.unit) ?: return
                update { it.copy(sizing = null) }
                mutateSelected("resize") { p, room -> FitRules.snap(FitRules.pushOut(p.copy(dims = dims), room), room, _state.value.pieces).first }
            }
            "back" -> {
                val t = sz.texts[sz.row]
                val next = if (sz.fresh) "" else t.dropLast(1)
                update { it.copy(sizing = sz.copy(texts = sz.texts.toMutableList().also { l -> l[sz.row] = next }, fresh = false)) }
            }
            else -> {
                if (key.length != 1 || !key[0].isDigit()) return
                val t = if (sz.fresh) "" else sz.texts[sz.row]
                if (t.length >= 3) return
                val next = (t + key).trimStart('0').ifEmpty { "0" }
                update { it.copy(sizing = sz.copy(texts = sz.texts.toMutableList().also { l -> l[sz.row] = next }, fresh = false)) }
            }
        }
    }

    // =========================================================================================
    // room, coach, settings, clear
    // =========================================================================================

    private fun roomWithAxis(room: RoomModel): RoomModel = if (lockedAxis != null) room.copy(axisYaw = lockedAxis) else room

    /** The room axis is fixed with the first piece (rules.roomAxes): the state's room always carries it. */
    private fun reapplyAxis() = update { it.copy(room = roomWithAxis(rawRoom)) }

    private fun roomChanged(room: RoomModel) {
        val before = _state.value.room
        if (before.hasFloor != room.hasFloor || before.obstacles.size != room.obstacles.size || before.doorZones.size != room.doorZones.size) {
            FitLog.i(
                String.format(
                    Locale.US, "ROOM floor=%s floors=%d walls=%d real=%d doors=%d axis=%s",
                    room.floorY?.let { String.format(Locale.US, "%.3f", it) } ?: "none", room.floors.size, room.walls.size, room.real.size, room.doorZones.size,
                    room.axisYaw?.let { String.format(Locale.US, "%.1f", Math.toDegrees(it.toDouble())) } ?: "none",
                )
            )
        }
        rawRoom = room
        update { it.copy(room = roomWithAxis(room)) }
    }

    private fun dismissCoach(reason: String) {
        if (!_state.value.coach) return
        FitLog.i("COACH dismissed reason=$reason")
        update { it.copy(coach = false) }
        settings { it.copy(coachSeen = true) }
    }

    private fun settings(change: (Settings) -> Settings) {
        val next = change(_state.value.settings)
        store.saveSettings(next)
        update { it.copy(settings = next) }
        FitLog.i("SETTINGS unit=${next.unit.tag} lang=${next.language.tag} look=${next.look.tag} coach_seen=${next.coachSeen}")
    }

    private fun clearAll() {
        val old = anchor
        anchor = null
        lockedAxis = null
        reapplyAxis()
        saved = null
        undo.clear()
        restoreJob?.cancel()
        saveJob?.cancel()
        store.saveLayout(null)
        update { it.copy(clearDialog = false, pieces = emptyList(), selected = null, sizing = null, canUndo = false, save = SaveState.NONE, savingPiece = null, restore = RestoreState.NONE, coach = false) }
        if (old != null) scope.launch { anchors.remove(old.id).also { FitLog.i("ANCHOR removed id=${old.id.take(8)} result=$it") } }
        removeStaleAnchors()
        FitLog.i("CLEAR")
        showSnack(SnackUi(SnackKind.CLEARED))
    }

    private fun removeStaleAnchors() {
        scope.launch {
            val all = anchors.loadAll()
            if (all is AnchorOutcome.Ok) for (a in all.value) if (a.id != anchor?.id) anchors.remove(a.id)
        }
    }

    private fun snackAction() {
        when (_state.value.snack?.kind) {
            SnackKind.ELSEWHERE -> {
                FitLog.i("RESTORE forget pieces=${saved?.pieces?.size ?: 0}")
                restoreJob?.cancel()
                clearAll()
            }
            SnackKind.UNSAVED -> _state.value.pieces.firstOrNull()?.let { createRoomAnchor(it) }
            else -> Unit
        }
    }

    // =========================================================================================
    // persistence: one room anchor + every piece relative to it (design `persistence`)
    // =========================================================================================

    private fun createRoomAnchor(at: Piece) {
        val floorY = _state.value.room.floorY ?: head()?.position?.y?.minus(1.6f) ?: 0f
        update { it.copy(save = SaveState.SAVING, savingPiece = at.id) }
        FitLog.i("ANCHOR create start piece=${at.id}")
        scope.launch {
            val r = anchors.create(at.center.at(floorY), "room")
            when (r) {
                is AnchorOutcome.Ok -> {
                    anchor = r.value
                    FitLog.i("ANCHOR saved id=${r.value.id.take(8)} pos=${FitLog.pos(r.value.pose.position)}")
                    update { it.copy(save = SaveState.SAVED, savingPiece = null) }
                    removeStaleAnchors()
                    scheduleSave(immediate = true)
                }
                is AnchorOutcome.Failed -> {
                    FitLog.w("ANCHOR failed code=${r.code} msg=${r.message}")
                    update { it.copy(save = SaveState.UNSAVED, savingPiece = null) }
                }
            }
        }
    }

    private fun scheduleSave(immediate: Boolean = false) {
        val a = anchor ?: return
        saveJob?.cancel()
        saveJob =
            scope.launch {
                if (!immediate) delay(SAVE_DELAY_MS)
                val s = _state.value
                val floorY = s.room.floorY ?: 0f
                if (s.pieces.isEmpty()) {
                    // everything was deleted one by one: the room anchor stays, nothing to restore
                    store.saveLayout(null)
                    saved = null
                    return@launch
                }
                val pose = a.pose
                val pieces =
                    s.pieces.map { p ->
                        val (local, yaw) = pose.toLocal(p.center, p.yaw, floorY)
                        SavedPiece(p.id, p.templateId, p.dims, local, yaw)
                    }
                val axis = lockedAxis?.let { pose.toLocal(P2.ZERO, it, floorY).second }
                val layout = SavedLayout(a.id, axis, pieces, nextId)
                store.saveLayout(layout)
                saved = layout
                FitLog.i("SAVE pieces=${pieces.size} anchor=${a.id.take(8)}")
            }
    }

    private fun startRestore() {
        val layout = saved ?: return
        restoreJob?.cancel()
        val started = clock()
        restoreJob =
            scope.launch {
                while (true) {
                    val found = (anchors.loadAll() as? AnchorOutcome.Ok)?.value?.firstOrNull { it.id == layout.anchorId }
                    if (found != null) {
                        restoreFrom(layout, found)
                        return@launch
                    }
                    if (_state.value.restore == RestoreState.LOOKING && clock() - started >= RESTORE_TIMEOUT_MS) {
                        FitLog.i("RESTORE elsewhere after_ms=${clock() - started} pieces=${layout.pieces.size}")
                        update { it.copy(restore = RestoreState.ELSEWHERE) }
                    }
                    delay(if (_state.value.restore == RestoreState.ELSEWHERE) RESTORE_POLL_SLOW_MS else RESTORE_POLL_MS)
                }
            }
    }

    private fun restoreFrom(layout: SavedLayout, found: AnchorRecord) {
        anchor = found
        val pose: AnchorPose = found.pose
        val pieces =
            layout.pieces.map { sp ->
                val (c, yaw) = pose.toStage(sp.local, sp.yaw)
                Piece(sp.id, sp.templateId, sp.dims, c, yaw)
            }
        lockedAxis = layout.axisYaw?.let { pose.toStage(Vec3.ZERO, it).second }
        reapplyAxis()
        nextId = maxOf(layout.nextId, (pieces.maxOfOrNull { it.id } ?: 0) + 1)
        val now = clock()
        update { it.copy(pieces = pieces, restore = RestoreState.RESTORING, restoredAtMs = now, save = SaveState.SAVED, selected = null) }
        FitLog.i("RESTORE pieces=${pieces.size} anchor=${found.id.take(8)}")
        showSnack(SnackUi(SnackKind.RESTORED, pieces.size))
        scope.launch {
            delay(RESTORING_MS)
            if (_state.value.restore == RestoreState.RESTORING) update { it.copy(restore = RestoreState.NONE) }
        }
    }

    // =========================================================================================
    // blocked (S20): the head cannot be tracked in Full Space; given a grace period
    // =========================================================================================

    private fun watchBlocked() {
        blockJob?.cancel()
        blockJob =
            scope.launch {
                delay(BLOCK_GRACE_MS)
                val reason = blockReason() ?: return@launch
                FitLog.w("BLOCKED reason=$reason")
                update { it.copy(blocked = true, menuOpen = false) }
            }
    }

    // =========================================================================================
    // snackbar
    // =========================================================================================

    private fun showSnack(snack: SnackUi) {
        timedSnack = snack
        timedSnackJob?.cancel()
        FitLog.i("SNACK ${snack.kind.tag} n=${snack.count}")
        update { it }
        timedSnackJob =
            scope.launch {
                delay(if (longSnacks) 60_000L else SNACK_MS)
                timedSnack = null
                update { it }
            }
    }

    /** The snackbar shown now: a sticky condition wins over a timed message (design `copy.snackbar`). */
    private fun snackFor(s: FitUiState): SnackUi? =
        when {
            s.blocked || s.clearDialog -> null
            s.trackingLost -> SnackUi(SnackKind.LOST)
            s.restore == RestoreState.ELSEWHERE -> SnackUi(SnackKind.ELSEWHERE)
            timedSnack != null -> timedSnack
            s.save == SaveState.UNSAVED -> SnackUi(SnackKind.UNSAVED)
            s.starting -> null
            !s.hasFloor && s.pieces.isEmpty() -> SnackUi(SnackKind.SCANNING)
            !s.hasFloor -> SnackUi(SnackKind.NO_FLOOR)
            else -> null
        }

    // =========================================================================================
    // state
    // =========================================================================================

    /** Every state change goes through here: fit results, the snackbar and the S7 contact are derived. */
    private fun update(change: (FitUiState) -> FitUiState) {
        val before = _state.value
        var next = change(before)
        val room = roomWithAxis(next.room)
        if (next.pieces !== before.pieces || next.room !== before.room) next = next.copy(results = FitRules.checkAll(room, next.pieces))
        val sel = next.selectedPiece
        val touching = if (sel != null) FitRules.gaps(sel, room, next.pieces).count { it.kind == GapKind.CONTACT } else 0
        next = next.copy(snappedSides = touching, snack = snackFor(next))
        _state.value = next
        val label = next.designState
        if (label != lastLogged) {
            lastLogged = label
            FitLog.i("STATE $label ${FitUiState.STATE_NAMES[label]} pieces=${next.pieces.size} selected=${next.selected ?: "-"}")
        }
    }

    // =========================================================================================
    // debug seams (DebugCommands only)
    // =========================================================================================

    var longSnacks = false

    fun debugSet(change: (FitUiState) -> FitUiState) = update(change)

    fun debugPieces(pieces: List<Piece>, select: Int?) {
        pushUndo()
        nextId = maxOf(nextId, (pieces.maxOfOrNull { it.id } ?: 0) + 1)
        if (lockedAxis == null) lockedAxis = rawRoom.axisYaw
        update { it.copy(pieces = pieces, selected = select, sizing = null) }
        if (anchor == null && pieces.isNotEmpty() && _state.value.save != SaveState.SAVING) createRoomAnchor(pieces.first()) else scheduleSave()
    }

    fun debugNextId(): Int = nextId++

    /** Forgets the room anchor (and removes it), so that the next piece makes a new one (S17 / S21). */
    fun debugClearAnchor() {
        val old = anchor ?: return
        anchor = null
        store.saveLayout(null)
        saved = null
        scope.launch { anchors.remove(old.id) }
        update { it.copy(save = SaveState.NONE, savingPiece = null) }
    }

    fun debugRoom(): RoomModel = roomWithAxis(_state.value.room)

    fun debugRestoreTimeout() {
        val layout = saved ?: _state.value.pieces.takeIf { it.isNotEmpty() }?.let { null }
        FitLog.i("RESTORE debug-timeout saved=${layout != null}")
        update { it.copy(restore = RestoreState.ELSEWHERE, selected = null, sizing = null) }
    }

    /** Re-runs the restore from storage (S15) without a relaunch. */
    fun debugRestore() {
        saved = store.loadLayout() ?: return
        update { it.copy(pieces = emptyList(), restore = RestoreState.LOOKING) }
        startRestore()
    }

    fun debugCoachReset() {
        settings { it.copy(coachSeen = false) }
        update { it.copy(coach = it.pieces.isNotEmpty()) }
        FitLog.i("COACH reset")
    }

    fun dumpJson(): String {
        val s = _state.value
        val o = org.json.JSONObject()
        o.put("designState", s.designState)
        o.put("ready", s.ready)
        o.put("hasFloor", s.hasFloor)
        o.put("blocked", s.blocked)
        o.put("restore", s.restore.name)
        o.put("save", s.save.name)
        o.put("selected", s.selected ?: -1)
        o.put("dragging", s.dragging ?: -1)
        o.put("sizing", s.sizing?.let { "${it.row}:${it.texts.joinToString(",")}" } ?: "")
        o.put("menuOpen", s.menuOpen)
        o.put("clearDialog", s.clearDialog)
        o.put("coach", s.coach)
        o.put("snack", s.snack?.kind?.tag ?: "")
        o.put("trackingLost", s.trackingLost)
        o.put("inside", s.inside ?: -1)
        o.put("unit", s.unit.tag)
        o.put("lang", s.settings.language.tag)
        o.put("look", s.look.tag)
        o.put("canUndo", s.canUndo)
        o.put("snappedSides", s.snappedSides)
        o.put("anchor", anchor?.id ?: "")
        val arr = org.json.JSONArray()
        for (p in s.pieces) {
            val r = s.results[p.id]
            arr.put(
                org.json.JSONObject()
                    .put("id", p.id)
                    .put("t", p.templateId)
                    .put("dims", Units.dims(p.dims, s.unit))
                    .put("x", p.center.x.toDouble())
                    .put("z", p.center.z.toDouble())
                    .put("yawDeg", Math.toDegrees(p.yaw.toDouble()))
                    .put("verdict", r?.verdict?.name ?: "")
                    .put("hitPieces", r?.hitPieces?.joinToString(",") ?: "")
                    .put("hitReal", r?.hitReal?.joinToString(",") ?: "")
                    .put("hitWalls", r?.hitWalls?.joinToString(",") ?: "")
                    .put("out", r?.outOfRoom ?: false)
                    .put("doors", r?.doors?.joinToString(",") ?: "")
            )
        }
        o.put("pieces", arr)
        val sel = s.selectedPiece
        if (sel != null) {
            val gaps = org.json.JSONArray()
            for (g in FitRules.gaps(sel, roomWithAxis(s.room), s.pieces)) gaps.put(org.json.JSONObject().put("side", g.side.name).put("m", g.distance.toDouble()).put("kind", g.kind.name).put("what", g.what))
            o.put("gaps", gaps)
        }
        return o.toString()
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FitViewModel(FitGraph.anchors, FitGraph.store(context.applicationContext), { FitGraph.head.pose() }, { FitGraph.head.blockReason() }) as T
    }

    companion object {
        const val UNDO_STEPS = 20
        const val SAVE_DELAY_MS = 500L
        const val RESTORE_TIMEOUT_MS = 10_000L
        const val RESTORE_POLL_MS = 1_000L
        const val RESTORE_POLL_SLOW_MS = 3_000L
        const val RESTORING_MS = 1_200L
        const val BLOCK_GRACE_MS = 2_500L
        const val SNACK_MS = 3_500L
    }
}
