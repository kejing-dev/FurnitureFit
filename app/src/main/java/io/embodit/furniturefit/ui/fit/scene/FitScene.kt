package io.embodit.furniturefit.ui.fit.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.furniturefit.domain.DragLine
import io.embodit.furniturefit.domain.FitResult
import io.embodit.furniturefit.domain.FitRules
import io.embodit.furniturefit.domain.Gap
import io.embodit.furniturefit.domain.GapKind
import io.embodit.furniturefit.domain.HeadPose
import io.embodit.furniturefit.domain.P2
import io.embodit.furniturefit.domain.PanelDodge
import io.embodit.furniturefit.domain.PanelFacing
import io.embodit.furniturefit.domain.Piece
import io.embodit.furniturefit.domain.Quat4
import io.embodit.furniturefit.domain.RoomModel
import io.embodit.furniturefit.domain.Units
import io.embodit.furniturefit.domain.Vec3
import io.embodit.furniturefit.domain.Verdict
import io.embodit.furniturefit.domain.wrapAngle
import io.embodit.furniturefit.platform.FitGraph
import io.embodit.furniturefit.platform.FitLog
import io.embodit.furniturefit.ui.fit.FitEvent
import io.embodit.furniturefit.ui.fit.FitUiState
import io.embodit.furniturefit.ui.fit.GapUi
import io.embodit.furniturefit.ui.fit.RestoreState
import io.embodit.furniturefit.ui.fit.SaveState
import io.embodit.furniturefit.ui.fit.components.PanelSizes
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal object ActiveScene {
    @Volatile var scene: FitScene? = null
}

/** The one per-frame ECS system of the app (`registerSystem` in FitScreen). */
class FitFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/** Panel ids of the pool. */
object PanelIds {
    const val LIBRARY = "library"
    const val STRIP = "pieceStrip"
    const val SIZING = "sizing"
    const val MENU = "moreMenu"
    const val DIALOG = "dialog"
    const val SNACK = "snack"
    const val COACH = "coach"
    const val GAP_SLOTS = 4

    fun gap(i: Int) = "gap$i"

    val pool: List<String> = listOf(LIBRARY, STRIP, SIZING, MENU, DIALOG, SNACK, COACH) + (0 until GAP_SLOTS).map { gap(it) }
}

/**
 * The 3D side of the app. It owns the calibrated stage root, the panel pool, the furniture, the gap
 * tapes and the room's tap targets, starts and stops the sensing (planes, spatial mesh, head) and
 * turns system taps and drags into events. Per-frame work (dragging, snapping preview, gaps, panel
 * placement, animations) happens in [onFrame], never through Compose: the only Compose state written
 * here is which panel is in view and the four gap readings, both on change only. Main thread only.
 */
class FitScene(private val sink: (FitEvent) -> Unit) {

    /** Panel id -> in view. A pooled panel composes its content only while this is true (Spatial Ruler finding 3). */
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** Reading of each gap plate slot (null: hidden). */
    val gapPlates = mutableStateMapOf<Int, GapUi>()

    /** Bumped to make `SpatialView.update` run again while panel entities are still missing. */
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private val head = FitGraph.head
    private val stick = FitGraph.stick
    private val planes = FitGraph.planes
    private val mesh = FitGraph.mesh
    private var content: SpatialViewContent? = null
    private var contentProbe: Entity? = null
    private var stageRoot: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var materials: FitMaterials? = null
    private var meshes: FitMeshes? = null
    private var colliders: RoomColliders? = null
    private val views = LinkedHashMap<Int, PieceView>()
    private val tapes = ArrayList<GapTapeView>()
    private val doorRects = ArrayList<FloorRect>()
    private val realRects = ArrayList<FloorRect>()
    private val debugBoxes = ArrayList<Box>()
    private var built = false
    private var attached = false
    private var paused = false
    private var buildJob: Job? = null

    private var pose = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var ui = FitUiState()
    private var frames = 0L
    private var lastLogMs = 0L

    // room rebuilds (planes / mesh changed), at most every 0.5 s
    private var roomDirty = true
    private var lastRoomMs = 0L
    private var floorSeenMs = -1L
    private var meshRestartMs = 0L
    private var lastPieceCount = 0
    private var lastLost = false
    private var lastInside: Int? = null

    // animations: piece id -> start of its drop / rise
    private val dropStart = HashMap<Int, Long>()
    private val riseStart = HashMap<Int, Long>()

    private val libraryFollow = LazyFollow("library", ::libraryRest, windowDeg = 45f, nearM = 0.35f, farM = 0.9f)
    private val snackFollow = LazyFollow("snack", ::snackRest, windowDeg = 20f, nearM = 0.6f, farM = 1.3f)

    // ---- system drag of a piece
    private class Drag(val pieceId: Int, val press: Vec3, val grabY: Float, val offset: P2) {
        var begun = false
        var hand = Vec3.ZERO
        var prevHand = Vec3.ZERO
        var totalPx = Vec3.ZERO
        var dir: Vec3? = null
        var equivalent = 1f
        var live: P2? = null
        var moves = 0
        var lastLogMs = 0L
    }

    private var drag: Drag? = null
    private var lastDragEndMs = -1L

    // ---- debug drag: the live pose glides to a target
    private class Glide(val pieceId: Int, val from: P2, val to: P2, val startMs: Long, val ms: Long, val hold: Boolean)

    private var glide: Glide? = null

    /** Live pose of the piece being dragged (system drag or debug glide, already snapped) or turned by the stick. */
    private var livePiece: Piece? = null

    /** The dragged piece and its unsnapped centre: re-snapped every frame while the stick turns it. */
    private var liveRaw: Pair<Int, P2>? = null

    /** Heading the stick gave the dragged piece (kept after the stick lets go, until the drag ends). */
    private var dragYaw: Pair<Int, Float>? = null

    // ---- thumbstick turn (v0.1.1): live here, written once on release (FitEvent.PieceTurned)
    private class Turn(val pieceId: Int, var yaw: Float) {
        var neutralSinceMs = -1L
        var frames = 0
    }

    private var turn: Turn? = null
    private var awaitTurnCommit: Pair<Int, Long>? = null
    private var lastFrameMs = -1L

    // ---- outline snap confirmation: piece id -> the real furniture it stands on; outline id -> flash start
    private val onOutline = HashMap<Int, String>()
    private val outlineFlash = HashMap<String, Long>()

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = Entity()
        runCatching { root.setName("stageRoot") }
        content.addEntity(root)
        val probe = Entity()
        runCatching { probe.setName("contentProbe") }
        content.addEntity(probe)
        contentProbe = probe
        // An entity added to the SpatialView is not at the Stage origin (0.256 m off in z, Spatial Ruler):
        // move the root once so that its local space equals Stage space.
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        FitLog.i("STAGE root offset=${FitLog.pos(offset.domain())}")

        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(PanelIds.LIBRARY, PanelSizes.LIBRARY_W, PanelSizes.LIBRARY_H, LIBRARY_SCALE)
        rig.world(PanelIds.STRIP, PanelSizes.STRIP_W, PanelSizes.STRIP_H, 1f)
        rig.world(PanelIds.SIZING, PanelSizes.SIZING_W, PanelSizes.SIZING_H, 1f)
        rig.world(PanelIds.MENU, PanelSizes.MENU_W, PanelSizes.MENU_H, MENU_SCALE)
        rig.world(PanelIds.DIALOG, PanelSizes.DIALOG_W, PanelSizes.DIALOG_H, DIALOG_SCALE)
        rig.world(PanelIds.SNACK, PanelSizes.SNACK_W, PanelSizes.SNACK_H, SNACK_SCALE)
        rig.world(PanelIds.COACH, PanelSizes.COACH_W, PanelSizes.COACH_H, COACH_SCALE)
        for (i in 0 until PanelIds.GAP_SLOTS) rig.world(PanelIds.gap(i), PanelSizes.GAP_W, PanelSizes.GAP_H, 1f)
        head.start()
        stick.start()
        FitGraph.anchors.start()
        ActiveScene.scene = this
        attached = true
        bindPanels(attachments)
        startSensing()
        buildJob =
            scope?.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) FitLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
        FitLog.i("SCENE attached")
    }

    /**
     * Materials and the five shared meshes, then the room's tap targets and the tape pools. A mesh costs
     * about a rendered frame and blocking the main thread for all of them raised an ANR in Spatial Ruler:
     * the build yields after every step ("准备中", S1).
     */
    private suspend fun build(root: Entity, t0: Long) {
        delay(300)
        val shared = SharedResources()
        this.shared = shared
        val m = FitMaterials(shared)
        materials = m
        yield()
        val meshes = FitMeshes(shared)
        this.meshes = meshes
        yield()
        val colliders = RoomColliders(root)
        colliders.buildBackstop()
        this.colliders = colliders
        colliders.sync(planes.all)
        yield()
        repeat(4) { i -> tapes += GapTapeView(root, meshes, m, i) }
        yield()
        repeat(MAX_DOORS) { i -> doorRects += FloorRect(root, meshes, m.doorEdge, m.doorFill, "door$i") }
        repeat(MAX_REAL) { i -> realRects += FloorRect(root, meshes, m.realEdge, null, "real$i") }
        yield()
        built = true
        FitLog.i("SCENE built ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui)
        sink(FitEvent.SceneReady)
    }

    private fun startSensing() {
        planes.start {
            roomDirty = true
            colliders?.sync(planes.all)
        }
        mesh.start { roomDirty = true }
        meshRestartMs = SystemClock.elapsedRealtime()
    }

    /** The Stage went to the background: stop every tracker (design `container.lifecycle`). */
    fun pause() {
        if (paused || !attached) return
        paused = true
        planes.stop()
        mesh.stop("pause")
        head.stop()
        stick.stop()
        endDrag(cancelled = true)
        turn = null
        FitLog.i("SCENE paused")
    }

    fun resume() {
        if (!paused || !attached) return
        paused = false
        head.start()
        stick.start()
        startSensing()
        FitLog.i("SCENE resumed")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        buildJob?.cancel()
        scope?.cancel()
        scope = null
        planes.stop()
        mesh.stop("detach")
        head.stop()
        stick.stop()
        FitGraph.anchors.stop()
        panelShown.clear()
        gapPlates.clear()
        views.values.forEach { it.destroy() }
        views.clear()
        tapes.clear()
        doorRects.clear()
        realRects.clear()
        debugBoxes.clear()
        runCatching { colliders?.clear() }
        colliders = null
        runCatching { rig?.release() }
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        materials = null
        meshes = null
        stageRoot = null
        contentProbe = null
        rig = null
        content = null
        built = false
        hasHead = false
        drag = null
        glide = null
        livePiece = null
        liveRaw = null
        dragYaw = null
        turn = null
        awaitTurnCommit = null
        onOutline.clear()
        outlineFlash.clear()
        libraryFollow.reset()
        snackFollow.reset()
        libraryBeforeDialog = null
        FitLog.i("SCENE detached")
    }

    // =========================================================================================
    // state -> entities
    // =========================================================================================

    fun render(state: FitUiState) {
        val before = ui
        ui = state
        val root = stageRoot ?: return
        val rig = rig ?: return
        if (!built) {
            rig[PanelIds.LIBRARY]?.show()
            return
        }
        val m = materials ?: return
        val meshes = meshes ?: return
        val now = SystemClock.elapsedRealtime()
        // pieces: create / destroy views
        val ids = state.pieces.map { it.id }.toSet()
        for (id in views.keys.filter { it !in ids }) views.remove(id)?.destroy()
        for (p in state.pieces) {
            if (p.id !in views) {
                val v = PieceView(root, meshes, m, p.id)
                views[p.id] = v
                v.shape(p)
                if (state.restore == RestoreState.RESTORING) riseStart[p.id] = now else if (!state.starting) {
                    dropStart[p.id] = now
                    v.slap(now)
                }
            } else {
                views[p.id]?.shape(p)
            }
        }
        if (state.pieces.size > lastPieceCount) {
            // a piece was added: give the spatial mesh another look around it (design needsCheck)
            mesh.restart("place")
            meshRestartMs = now
        }
        lastPieceCount = state.pieces.size
        if (before.dragging != null && state.dragging == null) {
            livePiece = null
            liveRaw = null
            dragYaw = null
        }
        awaitTurnCommit?.let { (id, _) ->
            if (state.pieces.firstOrNull { it.id == id } != before.pieces.firstOrNull { it.id == id }) {
                awaitTurnCommit = null
                if (turn == null && liveRaw == null) livePiece = null
            }
        }
        panels(state)
    }

    private fun panels(state: FitUiState) {
        val rig = rig ?: return
        rig[PanelIds.LIBRARY]?.show()
        val stripOn = state.selected != null && state.dragging == null && state.sizing == null && !state.trackingLost && state.restore != RestoreState.ELSEWHERE && !state.blocked
        if (!stripOn) rig[PanelIds.STRIP]?.hide()
        if (state.sizing == null || state.trackingLost) rig[PanelIds.SIZING]?.hide()
        if (state.menuOpen) rig[PanelIds.MENU]?.show() else rig[PanelIds.MENU]?.hide()
        val dialogOn = state.clearDialog || state.blocked
        val dialog = rig[PanelIds.DIALOG]
        if (dialogOn && dialog?.shown != true && hasHead) {
            val at = restAt(pose, 0f, gazePitchDeg(pose), DIALOG_M) + Vec3(0f, -0.05f, 0f)
            dialog?.show(at)
            dodgeLibrary(at)
        } else if (!dialogOn) {
            dialog?.hide()
            undodgeLibrary()
        }
        if (state.snack != null) rig[PanelIds.SNACK]?.show() else {
            rig[PanelIds.SNACK]?.hide()
            snackFollow.reset()
        }
        val coachOn = state.coach && !state.menuOpen && !state.clearDialog && !state.trackingLost && !state.blocked
        if (!coachOn) rig[PanelIds.COACH]?.hide()
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        frames++
        head.pose()?.let {
            pose = it
            hasHead = it.position.length > 1e-3f
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(pose)
        if (!attached || paused) return
        pollTracking(now)
        if (built) {
            rebuildRoom(now)
            manageMesh(now)
            stepGlide(now)
            stepStick(now)
            drawPieces(now)
            drawGaps()
            drawRoomMarks()
        }
        if (hasHead) placePanels(now)
        rig.faceViewer(pose.position)
        lastFrameMs = now
        if (now - lastLogMs > LOG_MS) {
            lastLogMs = now
            FitLog.i("FRAME n=$frames head=${FitLog.pos(pose.position)} fwd=${FitLog.pos(pose.forward)} pieces=${ui.pieces.size} planes=${planes.all.size} mesh=${mesh.anchorCount}/${mesh.points().size} running=${mesh.running} stick=${stick.status()}")
        }
    }

    private fun pollTracking(now: Long) {
        val lost = head.lost(now)
        if (lost != lastLost) {
            lastLost = lost
            if (lost) endDrag(cancelled = true)
            sink(FitEvent.TrackingChanged(lost))
        }
    }

    private fun rebuildRoom(now: Long) {
        if (!roomDirty || now - lastRoomMs < ROOM_REBUILD_MS) return
        roomDirty = false
        lastRoomMs = now
        val source = if (debugNoFloor) planes.all.filter { it.semantic != "FLOOR" } else planes.all
        val room = RoomModel.build(source, mesh.points(), if (hasHead) pose.position else null)
        if (room.hasFloor && floorSeenMs < 0) floorSeenMs = now
        sink(FitEvent.RoomChanged(room))
    }

    /**
     * The spatial mesh runs while the room is looked at and 60 s after the floor arrived; then, once
     * nothing new has come for 10 s, it is stopped and what it found is kept (documentation advice).
     */
    private fun manageMesh(now: Long) {
        if (!mesh.running || floorSeenMs < 0) return
        val earliest = max(floorSeenMs + MESH_AFTER_FLOOR_MS, meshRestartMs + MESH_IDLE_MS)
        if (now >= earliest && now - mesh.lastNewAtMs >= MESH_IDLE_MS) mesh.stop("idle")
    }

    /** The pieces as they are drawn now: the dragged one at its live pose. */
    private fun livePieces(): List<Piece> {
        val live = livePiece ?: return ui.pieces
        return ui.pieces.map { if (it.id == live.id) live else it }
    }

    private var liveResults: Map<Int, FitResult> = emptyMap()

    private fun drawPieces(now: Long) {
        val m = materials ?: return
        val floorY = ui.room.floorY ?: (if (hasHead) pose.position.y - 1.6f else 0f)
        val pieces = livePieces()
        liveResults = if (livePiece != null) FitRules.checkAll(room(), pieces) else ui.results
        var inside: Int? = null
        for (p in pieces) {
            val v = views[p.id] ?: continue
            var lift = 0f
            dropStart[p.id]?.let { t0 ->
                val t = ((now - t0) / PieceView.DROP_MS).coerceIn(0f, 1f)
                lift = PieceView.DROP_M * (1f - t * t)
                if (t >= 1f) dropStart.remove(p.id)
            }
            riseStart[p.id]?.let { t0 ->
                val t = ((now - t0) / PieceView.RISE_MS).coerceIn(0f, 1f)
                val e = 1f - (1f - t) * (1f - t)
                lift = -p.dims.h * (1f - e)
                if (t >= 1f) riseStart.remove(p.id)
            }
            v.pose(p.center, p.yaw, floorY, lift)
            confirmOutline(p, v, now)
            v.animate(now)
            val isInside = hasHead && FitRules.inside(p, pose.position)
            if (isInside && inside == null) inside = p.id
            val footprint =
                when {
                    ui.save == SaveState.UNSAVED -> 2
                    ui.savingPiece == p.id -> 1
                    else -> 0
                }
            v.look(
                PieceLook(
                    look = ui.look,
                    selected = ui.selected == p.id,
                    verdict = liveResults[p.id]?.verdict ?: Verdict.FITS,
                    inside = isInside,
                    stale = ui.trackingLost,
                    footprint = footprint,
                )
            )
        }
        if (inside != lastInside) {
            lastInside = inside
            sink(FitEvent.InsideChanged(inside))
        }
    }

    private fun room(): RoomModel = ui.room

    /**
     * A piece that comes to stand on a real-furniture outline (outline snap) gets a short confirmation:
     * its footprint is taped down again and the outline flashes yellow for 0.8 s.
     */
    private fun confirmOutline(p: Piece, v: PieceView, now: Long) {
        val on = FitRules.outlineOf(p, room())
        val was = onOutline[p.id]
        if (on == null) {
            if (was != null) onOutline.remove(p.id)
            return
        }
        onOutline[p.id] = on
        if (was == null) {
            outlineFlash[on] = now
            v.slap(now)
            FitLog.i("SNAP outline piece=${p.id} real=$on")
        }
    }

    // =========================================================================================
    // thumbstick turn
    // =========================================================================================

    /** The stick may turn a piece now: the dragged one, else the selected one. */
    private fun turnTarget(): Int? {
        val s = ui
        if (!s.dragEnabled || s.menuOpen || s.sizing != null) return null
        return s.dragging ?: s.selected
    }

    /**
     * Thumbstick X turns the target piece about its own vertical axis (FitRules.stickRateDeg: dead zone,
     * square curve, 90 deg/s). After 150 ms back in the dead zone the turn is written (snap there).
     */
    private fun stepStick(now: Long) {
        val dt = if (lastFrameMs < 0) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
        val rate = FitRules.stickRateDeg(stick.x(now))
        val target = turnTarget()
        turn?.let { t -> if (t.pieceId != target) finishTurn("target") }
        awaitTurnCommit?.let { (_, at) ->
            if (now - at > COMMIT_WAIT_MS) {
                awaitTurnCommit = null
                if (turn == null && liveRaw == null) livePiece = null
            }
        }
        if (rate != 0f && target != null) {
            val t = turn ?: run {
                val from = livePieces().firstOrNull { it.id == target } ?: return
                Turn(target, from.yaw).also {
                    turn = it
                    FitLog.i(String.format(Locale.US, "STICK turn start piece=%d yaw=%.1f x=%.2f", target, Math.toDegrees(from.yaw.toDouble()), stick.x(now)))
                }
            }
            t.yaw = wrapAngle(t.yaw + Math.toRadians((rate * dt).toDouble()).toFloat())
            t.neutralSinceMs = -1L
            t.frames++
            if (liveRaw?.first == target) refreshLive() else ui.pieces.firstOrNull { it.id == target }?.let { livePiece = it.copy(yaw = t.yaw) }
        } else {
            val t = turn ?: return
            if (t.neutralSinceMs < 0) t.neutralSinceMs = now else if (now - t.neutralSinceMs >= STICK_RELEASE_MS) finishTurn("release")
        }
    }

    private fun finishTurn(why: String) {
        val t = turn ?: return
        turn = null
        FitLog.i(String.format(Locale.US, "STICK turn end piece=%d yaw=%.1f frames=%d why=%s", t.pieceId, Math.toDegrees(t.yaw.toDouble()), t.frames, why))
        if (liveRaw?.first == t.pieceId) {
            // still dragged: the heading rides with the drag and is written on release
            dragYaw = t.pieceId to t.yaw
            return
        }
        if (ui.pieces.none { it.id == t.pieceId }) {
            livePiece = null
            return
        }
        awaitTurnCommit = t.pieceId to SystemClock.elapsedRealtime()
        sink(FitEvent.PieceTurned(t.pieceId, t.yaw))
    }

    /** Debug `stick`: the stick reads [x] for [ms]. */
    fun debugStick(x: Float, ms: Long): String {
        stick.debugHold(x, ms)
        return String.format(Locale.US, "stick x=%.2f ms=%d target=%s", x, ms, turnTarget()?.toString() ?: "-")
    }

    /** Gap tapes and reading plates of the focus piece (dragged or selected). */
    private fun drawGaps() {
        val rig = rig ?: return
        val focusId = ui.dragging ?: ui.selected
        val pieces = livePieces()
        val focus = focusId?.let { id -> pieces.firstOrNull { it.id == id } }
        val show = focus != null && !ui.trackingLost && ui.restore != RestoreState.ELSEWHERE && ui.room.hasFloor && hasHead
        val gaps: List<Gap> = if (show) FitRules.gaps(focus!!, room(), pieces) else emptyList()
        val floorY = ui.room.floorY ?: 0f
        for (i in 0 until PanelIds.GAP_SLOTS) {
            val gap = gaps.getOrNull(i)
            val slot = rig[PanelIds.gap(i)]
            if (gap == null || focus == null) {
                tapes.getOrNull(i)?.hide()
                slot?.hide()
                if (gapPlates.containsKey(i)) gapPlates.remove(i)
                continue
            }
            val sideHalf = if (gap.side.name == "FRONT" || gap.side.name == "BACK") focus.dims.w / 2f else focus.dims.d / 2f
            val y = floorY + (focus.dims.h * 0.5f).coerceIn(0.05f, TAPE_MAX_Y_M)
            val foot = tapes.getOrNull(i)?.draw(gap, y, pose.position, sideHalf)
            if (foot == null || gap.kind == GapKind.CONTACT) {
                slot?.hide()
                if (gapPlates.containsKey(i)) gapPlates.remove(i)
                continue
            }
            val text = Units.reading(gap.distance, ui.unit)
            val g = GapUi(i, text, gap.kind)
            if (gapPlates[i] != g) gapPlates[i] = g
            val k = (PLATE_K * foot.distanceTo(pose.position)).coerceIn(0.4f, 2.5f)
            val at = foot + Vec3(0f, PanelSizes.GAP_H * DP_M * k / 2f, 0f)
            slot?.show(at, k, k)
        }
    }

    /** Door zones a piece reaches into, real furniture a piece goes into, and the debug obstacle boxes. */
    private fun drawRoomMarks() {
        val room = room()
        val floorY = room.floorY ?: return
        val doors = liveResults.values.flatMap { it.doors }.toSet()
        val zones = room.doorZones.filter { it.id in doors || ui.obstaclesShown }
        for (i in doorRects.indices) zones.getOrNull(i)?.let { doorRects[i].draw(it.prism, floorY, 0.02f) } ?: doorRects[i].hide()
        val m = materials ?: return
        val now = SystemClock.elapsedRealtime()
        outlineFlash.entries.removeAll { now - it.value > OUTLINE_FLASH_MS }
        // real furniture: flashing (just snapped onto) > hit (red) > near the focus piece (white snap target)
        val hits = if (ui.trackingLost) emptySet() else liveResults.values.flatMap { it.hitReal }.toSet()
        val focusId = turn?.pieceId ?: ui.dragging ?: ui.selected
        val focus = focusId?.let { id -> livePieces().firstOrNull { it.id == id } }
        val near = if (focus != null && !ui.trackingLost) FitRules.outlinesNear(focus, room).map { it.id }.toSet() else emptySet()
        val reals = room.real.filter { it.id in outlineFlash || it.id in hits || it.id in near }.sortedBy { if (it.id in outlineFlash) 0 else if (it.id in hits) 1 else 2 }
        for (i in realRects.indices) {
            val o = reals.getOrNull(i)
            if (o == null) {
                realRects[i].hide()
                continue
            }
            val flashing = o.id in outlineFlash
            realRects[i].edge(if (flashing) m.tapeSaving else if (o.id in hits) m.realEdge else m.tapeUnsaved)
            realRects[i].draw(o.prism, floorY, if (flashing) 0.035f else 0.02f)
        }
        val meshes = meshes ?: return
        val root = stageRoot ?: return
        val obstacles = if (ui.obstaclesShown) room.obstacles else emptyList()
        while (debugBoxes.size < obstacles.size && debugBoxes.size < MAX_DEBUG) debugBoxes += Box(root, meshes.cube, m.debug, "obstacle${debugBoxes.size}")
        for (i in debugBoxes.indices) {
            val o = obstacles.getOrNull(i)
            if (o == null) debugBoxes[i].show(false) else {
                val p = o.prism
                debugBoxes[i].place(p.center.at((p.y0 + p.y1) / 2f), Vec3(p.hx * 2f, p.y1 - p.y0, p.hz * 2f), Quat4.axisAngle(Vec3.UP, p.yaw))
            }
        }
    }

    // =========================================================================================
    // panels in the room
    // =========================================================================================

    /** Where the library stood before a dialog pushed it aside (null: not pushed). */
    private var libraryBeforeDialog: Vec3? = null

    /**
     * A dialog opens 0.9 m ahead; the library rests 0.6 m away and would hide it when it is in front
     * (QA #2 q2-1 / q2-2). If it hides any part of the dialog from where the head is, it slides beside
     * the dialog at its height (PanelDodge.aside) for as long as the dialog is open, then back.
     */
    private fun dodgeLibrary(dialogAt: Vec3) {
        val lib = rig?.get(PanelIds.LIBRARY) ?: return
        val libAt = libraryFollow.position ?: lib.position
        val eye = pose.position
        val d = PanelDodge.view(eye, dialogAt, PanelSizes.DIALOG_W * DP_M * DIALOG_SCALE, PanelSizes.DIALOG_H * DP_M * DIALOG_SCALE)
        val l = PanelDodge.view(eye, libAt, PanelSizes.LIBRARY_W * DP_M * LIBRARY_SCALE, PanelSizes.LIBRARY_H * DP_M * LIBRARY_SCALE)
        if (!PanelDodge.overlaps(d, l)) {
            FitLog.i(String.format(Locale.US, "DIALOG library clear lib=(%.1f, %.1f) dialog=(%.1f, %.1f)", l.yawDeg, l.elevDeg, d.yawDeg, d.elevDeg))
            return
        }
        val to = PanelDodge.aside(eye, d, l)
        if (libraryBeforeDialog == null) libraryBeforeDialog = libAt
        libraryFollow.slideTo(to, SystemClock.elapsedRealtime())
        FitLog.i(String.format(Locale.US, "DIALOG library aside from=%s to=%s lib=(%.1f, %.1f) dialog=(%.1f, %.1f)", FitLog.pos(libAt), FitLog.pos(to), l.yawDeg, l.elevDeg, d.yawDeg, d.elevDeg))
    }

    private fun undodgeLibrary() {
        val back = libraryBeforeDialog ?: return
        libraryBeforeDialog = null
        libraryFollow.slideTo(back, SystemClock.elapsedRealtime())
        FitLog.i("DIALOG library back to=${FitLog.pos(back)}")
    }

    /** Library rest pose (`panels.library.placement`): 18 degrees left, 26 down, 0.6 m. */
    private fun libraryRest(h: HeadPose): Vec3 = restAt(h, LIBRARY_LEFT_DEG, -LIBRARY_DOWN_DEG, LIBRARY_M)

    /**
     * Snackbar: 14 degrees above the centre of view, 0.9 m. Measured from the real gaze (QA #1 q1-2: from
     * the level gaze it was 51 degrees above the view when looking down 37 and never came into view).
     */
    private fun snackRest(h: HeadPose): Vec3 {
        val up = gazePitchDeg(h) + SNACK_UP_DEG
        // looking down it would sink into the library (26 deg below level, 18 left): it moves 12 deg right instead
        val left = if (up < -LIBRARY_DOWN_DEG + SNACK_CLEAR_LIBRARY_DEG) -SNACK_SIDESTEP_DEG else 0f
        return restAt(h, left, up, SNACK_M)
    }

    /** The gaze's pitch (degrees, up +), held within +-60 so a rest pose never lands overhead or underfoot. */
    private fun gazePitchDeg(h: HeadPose): Float = Math.toDegrees(asin(h.forward.y.coerceIn(-1f, 1f).toDouble())).toFloat().coerceIn(-GAZE_PITCH_MAX_DEG, GAZE_PITCH_MAX_DEG)

    private fun restAt(h: HeadPose, leftDeg: Float, upDeg: Float, dist: Float): Vec3 {
        val f = h.level()
        val l = Math.toRadians(leftDeg.toDouble()).toFloat()
        val u = Math.toRadians(upDeg.coerceIn(-80f, 80f).toDouble()).toFloat()
        val left = P2(f.z, -f.x)
        val d = (f * cos(l) + left * sin(l)).normalized()
        return h.position + d.at(0f) * (dist * cos(u)) + Vec3(0f, dist * sin(u), 0f)
    }

    private fun placePanels(now: Long) {
        val rig = rig ?: return
        val lib = rig[PanelIds.LIBRARY] ?: return
        val hold = ui.menuOpen || ui.clearDialog || ui.blocked || now - lastLibraryPressMs < KEY_HOLD_MS
        libraryFollow.step(pose, now, hold)?.let { lib.moveTo(it) }
        val facing = FacePanelOf(lib.position)
        val libW = PanelSizes.LIBRARY_W * DP_M * LIBRARY_SCALE
        val libH = PanelSizes.LIBRARY_H * DP_M * LIBRARY_SCALE
        if (ui.menuOpen) {
            val mw = PanelSizes.MENU_W * DP_M * MENU_SCALE
            val mh = PanelSizes.MENU_H * DP_M * MENU_SCALE
            val at = lib.position + facing.up * (libH / 2f + mh / 2f + 0.008f) + facing.right * (libW / 2f - mw / 2f) + facing.normal * 0.01f
            rig[PanelIds.MENU]?.moveTo(at)
        }
        val coachOn = ui.coach && !ui.menuOpen && !ui.clearDialog && !ui.trackingLost && !ui.blocked
        if (coachOn) {
            val cw = PanelSizes.COACH_W * DP_M * COACH_SCALE
            rig[PanelIds.COACH]?.show(lib.position + facing.right * (libW / 2f + cw / 2f + 0.015f))
        }
        if (ui.snack != null) snackFollow.step(pose, now, hold = false)?.let { rig[PanelIds.SNACK]?.show(it) }
        placeStrip()
    }

    private var lastLibraryPressMs = -1L

    /** A key of the library was pressed: it holds still for a moment (lazy follow, as the ruler's body). */
    fun onLibraryPointer() {
        lastLibraryPressMs = SystemClock.elapsedRealtime()
    }

    /** The piece strip / keypad stands above the side of the piece that faces the viewer (`panels.pieceStrip.placement`). */
    private fun placeStrip() {
        val rig = rig ?: return
        val id = ui.selected ?: return
        val p = livePieces().firstOrNull { it.id == id } ?: return
        val floorY = ui.room.floorY ?: return
        val prism = p.prism(floorY)
        val eye = P2.of(pose.position)
        // the middle of the side that faces the viewer most
        val side = prism.sides.maxByOrNull { (eye - it.mid).normalized().dot(it.normal) } ?: return
        val edge = side.mid
        val toward = side.normal
        val y = (floorY + p.dims.h + 0.12f).coerceIn(pose.position.y - 0.9f, pose.position.y - 0.2f)
        val base = (edge + toward * 0.05f).at(y)
        val k = (STRIP_K * base.distanceTo(pose.position)).coerceIn(0.5f, 1.8f)
        val stripOn = ui.dragging == null && ui.sizing == null && !ui.trackingLost && ui.restore != RestoreState.ELSEWHERE && !ui.blocked
        if (stripOn) rig[PanelIds.STRIP]?.show(base + Vec3(0f, PanelSizes.STRIP_H * DP_M * k / 2f, 0f), k, k)
        if (ui.sizing != null && !ui.trackingLost) rig[PanelIds.SIZING]?.show(base + Vec3(0f, PanelSizes.SIZING_H * DP_M * k / 2f, 0f), k, k)
    }

    @Suppress("FunctionName")
    private fun FacePanelOf(at: Vec3): PanelFacing = io.embodit.furniturefit.domain.FacePanel.facing(at, pose.position, null)

    // =========================================================================================
    // input: system taps and drags
    // =========================================================================================

    private fun pieceOf(entity: Entity?): Int? {
        if (entity == null) return null
        for ((id, v) in views) if (v.owns(entity)) return id
        return null
    }

    fun onSpatialTap(value: SpatialTapValue) {
        val now = SystemClock.elapsedRealtime()
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        if (lastDragEndMs > 0 && now - lastDragEndMs < TAP_AFTER_DRAG_MS) {
            FitLog.i("TAP ignored reason=after-drag target=$name")
            return
        }
        val pieceId = pieceOf(target)
        when {
            pieceId != null -> {
                FitLog.i("TAP piece id=$pieceId stage=${FitLog.pos(stage)}")
                sink(FitEvent.PieceTapped(pieceId))
            }
            target != null && colliders?.isRoom(target) == true -> {
                FitLog.i("TAP room target=$name stage=${FitLog.pos(stage)}")
                sink(FitEvent.EmptyTapped)
            }
            else -> FitLog.i("TAP other target=$name stage=${FitLog.pos(stage)}")
        }
    }

    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        val now = SystemClock.elapsedRealtime()
        when (phase) {
            "start" -> {
                drag = null
                val target = value.targetEntity
                val name = runCatching { target?.getName() }.getOrNull() ?: "none"
                val press = if (start != null) toStage(start.x, start.y, start.z) else null
                val id = pieceOf(target)
                FitLog.i("DRAG start target=$name piece=${id ?: "-"} stage=${FitLog.pos(press)}")
                if (id == null || press == null) return
                if (!ui.dragEnabled || ui.menuOpen) {
                    FitLog.i("DRAG refused reason=${if (ui.menuOpen) "menu" else "disabled"} state=${ui.designState}")
                    return
                }
                val p = ui.pieces.firstOrNull { it.id == id } ?: return
                val floorY = ui.room.floorY ?: return
                val grabY = press.y.coerceIn(floorY + GRAB_MIN_M, floorY + GRAB_MAX_M)
                drag = Drag(id, press, grabY, P2.of(press) - p.center)
            }
            "move" -> {
                val d = drag ?: return
                val a = value.dragAmount
                d.moves++
                d.totalPx = Vec3(d.totalPx.x + a.x, d.totalPx.y + a.y, d.totalPx.z + a.z)
                d.hand = handDelta(d.totalPx) ?: d.hand
                if (!d.begun && d.hand.length >= DRAG_START_M) beginDrag(d)
                if (d.begun) {
                    val dir = d.dir ?: return
                    val turned = DragLine.turn(dir, d.hand - d.prevHand, d.equivalent, pose.right, pose.up, pose.forward)
                    d.prevHand = d.hand
                    d.dir = turned
                    val hit = DragLine.onLevel(pose.position, turned, d.grabY) ?: return
                    moveLive(d.pieceId, hit - d.offset)
                    if (d.moves <= 3 || now - d.lastLogMs > 500) {
                        d.lastLogMs = now
                        FitLog.i("DRAG move piece=${d.pieceId} live=${livePiece?.center?.let { String.format(Locale.US, "(%.3f, %.3f)", it.x, it.z) }} n=${d.moves}")
                    }
                }
            }
            "end", "cancel" -> {
                val d = drag ?: return
                drag = null
                if (!d.begun) {
                    FitLog.i("DRAG $phase not-begun n=${d.moves}")
                    return
                }
                lastDragEndMs = now
                FitLog.i("DRAG $phase piece=${d.pieceId} n=${d.moves}")
                endDrag(cancelled = phase == "cancel")
            }
        }
    }

    private fun beginDrag(d: Drag) {
        d.begun = true
        val to = d.press - pose.position
        if (to.length < 1e-3f) return
        d.dir = to.normalized()
        val pitch = Math.toDegrees(asin(pose.forward.y.coerceIn(-1f, 1f).toDouble())).toFloat()
        d.equivalent = DragLine.equivalentDepth(to.length, pitch)
        d.prevHand = d.hand
        sink(FitEvent.DragStarted(d.pieceId))
        FitLog.i("DRAG begin piece=${d.pieceId} grab_y=${String.format(Locale.US, "%.3f", d.grabY)} depth=${String.format(Locale.US, "%.2f", d.equivalent)}")
    }

    /** The dragged piece's new footprint centre: snapped live (`rules.snap`, "while moving and on release"; outline snap first). */
    private fun moveLive(id: Int, center: P2) {
        liveRaw = id to center
        refreshLive()
    }

    private fun refreshLive() {
        val (id, center) = liveRaw ?: return
        val p = ui.pieces.firstOrNull { it.id == id } ?: return
        val yaw = turn?.takeIf { it.pieceId == id }?.yaw ?: dragYaw?.takeIf { it.first == id }?.second ?: p.yaw
        livePiece = FitRules.settle(p.copy(center = center, yaw = yaw), room(), ui.pieces).piece
    }

    private fun endDrag(cancelled: Boolean) {
        val live = livePiece
        val id = drag?.pieceId ?: glide?.pieceId ?: live?.id ?: ui.dragging ?: return
        drag = null
        glide = null
        sink(FitEvent.DragEnded(id, if (cancelled) null else live?.center, if (cancelled) null else live?.yaw))
    }

    private fun stepGlide(now: Long) {
        val g = glide ?: return
        val t = ((now - g.startMs).toFloat() / g.ms).coerceIn(0f, 1f)
        moveLive(g.pieceId, g.from + (g.to - g.from) * t)
        if (t >= 1f && !g.hold) {
            FitLog.i("DRAG glide end piece=${g.pieceId}")
            endDrag(cancelled = false)
        }
    }

    private fun handDelta(px: Vec3): Vec3? {
        val moved = toStage(px.x, px.y, px.z) ?: return null
        val origin = toStage(0f, 0f, 0f) ?: return null
        return moved - origin
    }

    /** View-local pixels of a spatial gesture -> Stage metres (Spatial Ruler). */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .getOrNull()
    }

    // =========================================================================================
    // debug seams
    // =========================================================================================

    /** Debug `drag`: the piece glides to [to] over [ms] (a system drag in all but the input); [hold] keeps it in S6. */
    fun debugGlide(id: Int, to: P2, ms: Long, hold: Boolean): String {
        val p = ui.pieces.firstOrNull { it.id == id } ?: return "no piece $id"
        if (!ui.dragEnabled) return "drag disabled in ${ui.designState}"
        sink(FitEvent.DragStarted(id))
        glide = Glide(id, p.center, to, SystemClock.elapsedRealtime(), ms.coerceAtLeast(1L), hold)
        return "glide piece=$id to=(${to.x}, ${to.z}) ms=$ms hold=$hold"
    }

    /** Debug: drops any drag or glide without moving the piece. */
    fun debugDragCancel() {
        if (drag == null && glide == null && ui.dragging == null) return
        endDrag(cancelled = true)
        livePiece = null
        liveRaw = null
    }

    fun debugDragEnd(): String {
        endDrag(cancelled = false)
        return "drag end"
    }

    fun poseLine(): String =
        String.format(Locale.US, "POSE hmd pos=%s %s fwd=%s right=%s valid=%s support=%s frames=%d", FitLog.pos(pose.position), FitLog.quat(pose.rotation), FitLog.pos(pose.forward), FitLog.pos(pose.right), hasHead, head.supportState(), frames)

    fun headPose(): HeadPose? = if (hasHead) pose else null

    fun panelLines(): List<String> = rig?.dump() ?: emptyList()

    fun planeLines(): List<String> =
        planes.all.map { s ->
            String.format(Locale.US, "PLANE %s area=%.2f center=%s normal=%s verts=%s", s.label, s.area, FitLog.pos(s.centroid), FitLog.pos(s.normal), s.vertices.joinToString(" ") { FitLog.pos(it) })
        }

    fun meshLines(): List<String> {
        val hist = mesh.semanticHistogram().entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key}=${it.value}" }
        return listOf("MESH anchors=${mesh.anchorCount} furniture_points=${mesh.points().size} running=${mesh.running} semantics: $hist")
    }

    fun roomLines(): List<String> {
        val r = room()
        val out = ArrayList<String>()
        out += String.format(Locale.US, "ROOM floor=%s axis=%s interior=(%.3f, %.3f) floors=%d", r.floorY?.toString() ?: "none", r.axisYaw?.let { Math.toDegrees(it.toDouble()).toString() } ?: "none", r.interior.x, r.interior.z, r.floors.size)
        for (o in r.obstacles) {
            val p = o.prism
            out += String.format(Locale.US, "OBSTACLE %s kind=%s sem=%s c=(%.3f, %.3f) half=(%.3f, %.3f) yaw=%.1f y=%.2f..%.2f", o.id, o.kind, o.semantic, p.center.x, p.center.z, p.hx, p.hz, Math.toDegrees(p.yaw.toDouble()), p.y0, p.y1)
        }
        for (d in r.doorZones) out += String.format(Locale.US, "DOORZONE %s c=(%.3f, %.3f) width=%.2f yaw=%.1f", d.id, d.prism.center.x, d.prism.center.z, d.width, Math.toDegrees(d.prism.yaw.toDouble()))
        return out
    }

    /** Debug: the room is rebuilt without its floor (S2 "scanning" on a room that is already mapped). */
    var debugNoFloor = false
        set(value) {
            field = value
            roomDirty = true
        }

    fun parkPanels() {
        libraryFollow.reset()
        snackFollow.reset()
    }

    companion object {
        const val LIBRARY_SCALE = 0.75f
        const val MENU_SCALE = 0.75f
        const val COACH_SCALE = 0.75f
        const val DIALOG_SCALE = 1.0f
        const val SNACK_SCALE = 1.125f
        const val LIBRARY_LEFT_DEG = 18f
        const val LIBRARY_DOWN_DEG = 26f
        const val LIBRARY_M = 0.6f
        const val SNACK_UP_DEG = 14f
        const val SNACK_M = 0.9f
        const val DIALOG_M = 0.9f
        const val STRIP_K = 0.6f
        const val PLATE_K = 0.8f
        const val TAPE_MAX_Y_M = 0.45f
        const val GRAB_MIN_M = 0.05f
        const val GRAB_MAX_M = 2.0f
        const val DRAG_START_M = 0.01f
        const val TAP_AFTER_DRAG_MS = 300L
        const val KEY_HOLD_MS = 600L
        const val ROOM_REBUILD_MS = 500L
        const val MESH_AFTER_FLOOR_MS = 60_000L
        const val MESH_IDLE_MS = 10_000L
        const val LOG_MS = 5_000L
        const val MAX_DOORS = 4
        const val MAX_REAL = 8
        const val STICK_RELEASE_MS = 150L
        const val COMMIT_WAIT_MS = 600L
        const val OUTLINE_FLASH_MS = 800L
        const val GAZE_PITCH_MAX_DEG = 60f
        const val SNACK_CLEAR_LIBRARY_DEG = 10f
        const val SNACK_SIDESTEP_DEG = 12f
        const val MAX_DEBUG = 64
    }
}

/**
 * Lazy follow (Spatial Ruler T11): the panel rests where it is; once its centre has been more than
 * [windowDeg] off the view direction, or nearer than [nearM] or farther than [farM], for 0.5 s, it
 * slides (0.35 s, ease-in-out cubic) to the rest pose of that moment.
 */
internal class LazyFollow(private val name: String, private val rest: (HeadPose) -> Vec3, private val windowDeg: Float, private val nearM: Float, private val farM: Float) {
    private var at: Vec3? = null
    private var from = Vec3.ZERO
    private var to = Vec3.ZERO
    private var slideStartMs = NEVER
    private var outSinceMs = NEVER
    private var lastWritten: Vec3? = null

    fun reset() {
        at = null
        slideStartMs = NEVER
        outSinceMs = NEVER
        lastWritten = null
    }

    /** Where it rests (or is sliding to); null before the first park. */
    val position: Vec3?
        get() = if (slideStartMs != NEVER) to else at

    /** Slides to [p] now (0.35 s) and rests there; following resumes from that spot. */
    fun slideTo(p: Vec3, nowMs: Long) {
        val current = at
        if (current == null) {
            at = p
            lastWritten = null
            return
        }
        from = current
        to = p
        slideStartMs = nowMs
        outSinceMs = NEVER
    }

    fun step(head: HeadPose, nowMs: Long, hold: Boolean): Vec3? {
        val current = at
        if (current == null) {
            val p = rest(head)
            at = p
            FitLog.i("FOLLOW $name park pos=${FitLog.pos(p)}")
            return write(p)
        }
        if (slideStartMs != NEVER) {
            val q = ((nowMs - slideStartMs) / SLIDE_MS).coerceIn(0f, 1f)
            val e = if (q < 0.5f) 4f * q * q * q else 1f - (-2f * q + 2f).let { it * it * it } / 2f
            val p = from.lerp(to, e)
            if (q >= 1f) {
                slideStartMs = NEVER
                at = to
            }
            return write(p)
        }
        val toPanel = current - head.position
        val dist = toPanel.length
        val away = if (dist < 1e-3f) 0f else Math.toDegrees(kotlin.math.acos((toPanel / dist).dot(head.forward).coerceIn(-1f, 1f)).toDouble()).toFloat()
        val out = away > windowDeg || dist < nearM || dist > farM
        if (!out || hold) {
            outSinceMs = NEVER
        } else if (outSinceMs == NEVER) {
            outSinceMs = nowMs
        } else if (nowMs - outSinceMs >= OUT_MS) {
            from = current
            to = rest(head)
            slideStartMs = nowMs
            outSinceMs = NEVER
            FitLog.i(String.format(Locale.US, "FOLLOW %s slide angle=%.1f dist=%.2f to=%s", name, away, dist, FitLog.pos(to)))
        }
        return write(current)
    }

    private fun write(p: Vec3): Vec3? {
        if (p == lastWritten) return null
        lastWritten = p
        return p
    }

    companion object {
        private const val NEVER = -1L
        private const val OUT_MS = 500L
        private const val SLIDE_MS = 350f
    }
}
