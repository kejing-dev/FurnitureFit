package io.embodit.furniturefit.ui.fit

import io.embodit.furniturefit.domain.Dims
import io.embodit.furniturefit.domain.FitResult
import io.embodit.furniturefit.domain.Gap
import io.embodit.furniturefit.domain.GapKind
import io.embodit.furniturefit.domain.LengthUnit
import io.embodit.furniturefit.domain.Look
import io.embodit.furniturefit.domain.Piece
import io.embodit.furniturefit.domain.RoomModel
import io.embodit.furniturefit.domain.Settings
import io.embodit.furniturefit.domain.Templates
import io.embodit.furniturefit.domain.Units
import io.embodit.furniturefit.domain.Verdict

enum class SnackKind(val tag: String, val sticky: Boolean) {
    SCANNING("scanning", true),
    NO_FLOOR("no_floor", true),
    RESTORED("restored", false),
    ELSEWHERE("elsewhere", true),
    UNSAVED("unsaved", true),
    LIMIT("limit", false),
    LOST("lost", true),
    CLEARED("cleared", false),
}

data class SnackUi(val kind: SnackKind, val count: Int = 0)

enum class SaveState { NONE, SAVING, SAVED, UNSAVED }

enum class RestoreState { NONE, LOOKING, RESTORING, ELSEWHERE }

/** The open size keypad: which row (0 W, 1 D, 2 H) takes the digits, and what is typed so far. */
data class SizingUi(val pieceId: Int, val row: Int, val texts: List<String>, val fresh: Boolean) {
    fun values(): List<Int?> = texts.map { it.toIntOrNull() }

    fun valid(unit: LengthUnit): Boolean = values().all { it != null && it in Units.minInput(unit)..Units.maxInput(unit) }

    fun rowValid(i: Int, unit: LengthUnit): Boolean = texts[i].toIntOrNull()?.let { it in Units.minInput(unit)..Units.maxInput(unit) } == true

    fun dims(unit: LengthUnit): Dims? {
        val v = values()
        if (!valid(unit)) return null
        return Dims(Units.metres(v[0]!!, unit), Units.metres(v[1]!!, unit), Units.metres(v[2]!!, unit))
    }
}

/** One reading plate: on the tape of one side of the selected piece. */
data class GapUi(val index: Int, val text: String, val kind: GapKind)

data class FitUiState(
    /** The scene has built its meshes and panels ("准备中" is over). */
    val ready: Boolean = false,
    val blocked: Boolean = false,
    val room: RoomModel = RoomModel.EMPTY,
    val restore: RestoreState = RestoreState.NONE,
    /** `elapsedRealtime` when the restored pieces started to rise (S15). */
    val restoredAtMs: Long = 0L,
    val pieces: List<Piece> = emptyList(),
    val results: Map<Int, FitResult> = emptyMap(),
    val selected: Int? = null,
    val sizing: SizingUi? = null,
    val menuOpen: Boolean = false,
    val clearDialog: Boolean = false,
    val snack: SnackUi? = null,
    val coach: Boolean = false,
    val settings: Settings = Settings(),
    val save: SaveState = SaveState.NONE,
    /** The piece whose anchor is being made (its footprint is the "saving" one). */
    val savingPiece: Int? = null,
    val trackingLost: Boolean = false,
    val dragging: Int? = null,
    val canUndo: Boolean = false,
    val inside: Int? = null,
    /** The selected piece touches something (a side within 5 cm): S7. */
    val snappedSides: Int = 0,
    val obstaclesShown: Boolean = false,
    /** Debug: hold the "starting" look (S1). */
    val holdStarting: Boolean = false,
) {
    val hasFloor: Boolean
        get() = room.hasFloor

    val unit: LengthUnit
        get() = settings.unit

    val look: Look
        get() = settings.look

    val count: Int
        get() = pieces.size

    val atLimit: Boolean
        get() = pieces.size >= Templates.MAX_PIECES

    val starting: Boolean
        get() = !ready || holdStarting || restore == RestoreState.LOOKING

    /** Tiles take a press (at the limit they still answer, with the "Max 12" snackbar). */
    val tilesEnabled: Boolean
        get() = !starting && !blocked && hasFloor && !trackingLost && restore != RestoreState.ELSEWHERE

    val dragEnabled: Boolean
        get() = hasFloor && !trackingLost && !blocked && restore != RestoreState.ELSEWHERE && !clearDialog

    val selectedPiece: Piece?
        get() = selected?.let { id -> pieces.firstOrNull { it.id == id } }

    fun verdict(id: Int): Verdict = results[id]?.verdict ?: Verdict.FITS

    /** The design state this screen shows (design-package `states[]`), for the log, the dump and QA. */
    val designState: String
        get() =
            when {
                blocked -> "S20"
                starting -> "S1"
                clearDialog -> "S14"
                menuOpen -> "S13"
                trackingLost -> "S19"
                restore == RestoreState.ELSEWHERE -> "S16"
                restore == RestoreState.RESTORING -> "S15"
                !hasFloor && pieces.isEmpty() -> "S2"
                save == SaveState.SAVING -> "S21"
                sizing != null -> "S10"
                dragging != null -> "S6"
                coach -> "S22"
                save == SaveState.UNSAVED -> "S17"
                atLimit -> "S18"
                selected != null && verdict(selected) == Verdict.COLLIDES -> "S8"
                selected != null && verdict(selected) == Verdict.DOOR -> "S9"
                selected != null && snappedSides > 0 -> "S7"
                selected != null -> "S5"
                inside != null -> "S11"
                look == Look.WIRE && pieces.isNotEmpty() -> "S12"
                pieces.isNotEmpty() -> "S4"
                else -> "S3"
            }

    companion object {
        val STATE_NAMES =
            mapOf(
                "S1" to "starting", "S2" to "scanning", "S3" to "empty", "S4" to "placed", "S5" to "selected", "S6" to "dragging",
                "S7" to "snapped", "S8" to "colliding", "S9" to "door-blocked", "S10" to "sizing", "S11" to "inside", "S12" to "wireframe",
                "S13" to "more-menu", "S14" to "confirm-clear", "S15" to "restoring", "S16" to "elsewhere", "S17" to "unsaved",
                "S18" to "limit", "S19" to "tracking-lost", "S20" to "blocked", "S21" to "saving", "S22" to "coach",
            )
    }
}

sealed interface FitEvent {
    data class TileClicked(val templateId: String) : FitEvent
    data object UndoClicked : FitEvent
    data object MoreClicked : FitEvent
    data class PieceTapped(val id: Int) : FitEvent
    /** A tap on the floor, a wall or the empty room. */
    data object EmptyTapped : FitEvent
    data class DragStarted(val id: Int) : FitEvent
    /** [center]: where the scene let go of the piece (already snapped); null when cancelled. [yaw]: its heading then (the stick may turn it while dragged). */
    data class DragEnded(val id: Int, val center: io.embodit.furniturefit.domain.P2?, val yaw: Float? = null) : FitEvent
    data object RotateClicked : FitEvent
    /** The thumbstick let go of the piece it was turning: [yaw] is its new heading (radians, not yet snapped). */
    data class PieceTurned(val id: Int, val yaw: Float) : FitEvent
    data object DeleteClicked : FitEvent
    data object SizeClicked : FitEvent
    data class SizingRow(val row: Int) : FitEvent
    /** "0" - "9", "back", "ok". */
    data class SizingKey(val key: String) : FitEvent
    data class LookSelected(val look: Look) : FitEvent
    data class LanguageSelected(val language: io.embodit.furniturefit.domain.LanguagePref) : FitEvent
    data class UnitSelected(val unit: LengthUnit) : FitEvent
    data object ClearAllClicked : FitEvent
    data object ClearConfirmed : FitEvent
    data object DialogDismissed : FitEvent
    data object ExitClicked : FitEvent
    data object SnackActionClicked : FitEvent
    data object CoachDismissed : FitEvent
    data object BlockedRetry : FitEvent
    data object BlockedExit : FitEvent
    data object SceneReady : FitEvent
    data class RoomChanged(val room: RoomModel) : FitEvent
    data class TrackingChanged(val lost: Boolean) : FitEvent
    data class InsideChanged(val id: Int?) : FitEvent
}

sealed interface FitEffect {
    data object Exit : FitEffect
    data class Feedback(val kind: String) : FitEffect
}
