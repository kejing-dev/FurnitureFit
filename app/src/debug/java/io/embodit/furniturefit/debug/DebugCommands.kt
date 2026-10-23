package io.embodit.furniturefit.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.embodit.furniturefit.domain.FitRules
import io.embodit.furniturefit.domain.LanguagePref
import io.embodit.furniturefit.domain.LengthUnit
import io.embodit.furniturefit.domain.Look
import io.embodit.furniturefit.domain.ObstacleKind
import io.embodit.furniturefit.domain.P2
import io.embodit.furniturefit.domain.Piece
import io.embodit.furniturefit.domain.Templates
import io.embodit.furniturefit.domain.yawOf
import io.embodit.furniturefit.platform.FitGraph
import io.embodit.furniturefit.platform.FitLog
import io.embodit.furniturefit.ui.fit.FitEvent
import io.embodit.furniturefit.ui.fit.FitViewModel
import io.embodit.furniturefit.ui.fit.debug.Probe
import io.embodit.furniturefit.ui.fit.scene.FitScene
import java.io.File
import java.util.Locale

/**
 * Test hooks of the debug build (design `qaHooks`). One log line (tag `FurnitureFit`) answers every
 * command. Broadcast: `am broadcast -a io.embodit.furniturefit.DEBUG -p io.embodit.furniturefit --es cmd NAME [--es s TEXT] [--es a NUMBERS]`.
 *
 * Designed states (QA screenshots; the floor must be known for S3 - S18, S21, S22)
 * - `state` s=S1..S22         jump to that state of the design package (`STATE Sx ...` in the log)
 *
 * Driving
 * - `place` s=templateId [a=x,z[,yawDeg]]   a piece: like a tile press, or at a Stage point
 * - `select` a=id · `tap` (the empty room) · `drag` a=id,x,z[,ms] [s=hold]   glide (a system drag in all but the input)
 * - `drag_end` · `ui` s=<control>   press a control by name (list in AGENTS.md)
 * - `obstacles` s=on|off      draw every obstacle and door zone (debug geometry)
 * - `coach_reset` · `restore_timeout` (-> S16) · `restore` (re-run the restore from storage, S15)
 * - `tracking` s=lost|ok · `blocked` s=on|off · `starting` s=on|off · `nofloor` s=on|off
 * - `fail_next_anchor` · `hold_anchor` a=ms · `snack` s=long|normal · `clear` (no dialog) · `park`
 *
 * Reading back
 * - `dump` (also files/dump.json) · `pose` · `panels` · `planes` · `mesh` · `room` · `bounds`
 */
internal object DebugCommands {
    private const val NO_HOST = "CMD ignored: no Stage"
    private val main = Handler(Looper.getMainLooper())

    fun run(context: Context, cmd: String, s: String?, a: String?) {
        val host = Probe.host
        if (host == null) {
            FitLog.w(NO_HOST)
            return
        }
        val vm = host.viewModel()
        val scene = host.scene()
        val nums = a?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: emptyList()
        when (cmd) {
            "state" -> state(vm, scene, s ?: "S3")
            "place" -> place(vm, scene, s ?: "sofa3", nums)
            "select" -> nums.firstOrNull()?.let { vm.onEvent(FitEvent.PieceTapped(it.toInt())) }
            "tap" -> vm.onEvent(FitEvent.EmptyTapped)
            "drag" -> {
                val id = nums.getOrNull(0)?.toInt() ?: vm.state.value.selected ?: return
                val to = P2(nums.getOrNull(1) ?: 0f, nums.getOrNull(2) ?: 0f)
                FitLog.i("CMD ${scene.debugGlide(id, to, (nums.getOrNull(3) ?: 1200f).toLong(), hold = s == "hold")}")
            }
            "drag_end" -> FitLog.i("CMD ${scene.debugDragEnd()}")
            "stick" -> FitLog.i("CMD ${scene.debugStick(nums.getOrNull(0) ?: 0f, (nums.getOrNull(1) ?: 1000f).toLong())}")
            "ui" -> ui(vm, s ?: "")
            "obstacles" -> vm.debugSet { it.copy(obstaclesShown = s != "off") }
            "coach_reset" -> vm.debugCoachReset()
            "restore_timeout" -> vm.debugRestoreTimeout()
            "restore" -> vm.debugRestore()
            "tracking" -> FitGraph.head.forceLost = s == "lost"
            "blocked" -> vm.debugSet { it.copy(blocked = s != "off", menuOpen = false) }
            "starting" -> vm.debugSet { it.copy(holdStarting = s != "off") }
            "nofloor" -> scene.debugNoFloor = s != "off"
            "fail_next_anchor" -> FitGraph.anchors.failNext = true
            "hold_anchor" -> FitGraph.anchors.holdMs = (nums.firstOrNull() ?: 0f).toLong()
            "snack" -> vm.longSnacks = s == "long"
            "clear" -> {
                vm.debugPieces(emptyList(), null)
                vm.debugSet { it.copy(menuOpen = false, clearDialog = false, sizing = null) }
            }
            "park" -> scene.parkPanels()
            "dump" -> {
                val json = vm.dumpJson()
                runCatching { File(context.filesDir, "dump.json").writeText(json) }
                FitLog.i("DUMP $json")
            }
            "pose" -> FitLog.i(scene.poseLine())
            "panels" -> scene.panelLines().forEach { FitLog.i(it) }
            "planes" -> scene.planeLines().forEach { FitLog.i(it) }
            "mesh" -> scene.meshLines().forEach { FitLog.i(it) }
            "room" -> scene.roomLines().forEach { FitLog.i(it) }
            "bounds" ->
                listOf("library", "pieceStrip", "sizing", "moreMenu", "dialog", "snack", "coach").forEach { panel ->
                    Probe.controlsOf(panel).forEach { (name, b) -> FitLog.i(String.format(Locale.US, "CTRL panel=%s name=%s cx=%.1f cy=%.1f w=%.1f h=%.1f", panel, name, b.cx, b.cy, b.w, b.h)) }
                }
            else -> FitLog.w("CMD unknown '$cmd'")
        }
    }

    private fun ui(vm: FitViewModel, name: String) {
        val e: FitEvent? =
            when {
                name == "undo" -> FitEvent.UndoClicked
                name == "more" -> FitEvent.MoreClicked
                name == "rotate" -> FitEvent.RotateClicked
                name == "remove" -> FitEvent.DeleteClicked
                name == "size" -> FitEvent.SizeClicked
                name.startsWith("key_") -> FitEvent.SizingKey(name.removePrefix("key_"))
                name.startsWith("row_") -> FitEvent.SizingRow(name.removePrefix("row_").toIntOrNull() ?: 0)
                name.startsWith("tile_") -> FitEvent.TileClicked(name.removePrefix("tile_"))
                name == "look_solid" -> FitEvent.LookSelected(Look.SOLID)
                name == "look_wire" -> FitEvent.LookSelected(Look.WIRE)
                name.startsWith("lang_") -> LanguagePref.entries.firstOrNull { it.tag == name.removePrefix("lang_") }?.let { FitEvent.LanguageSelected(it) }
                name.startsWith("unit_") -> LengthUnit.entries.firstOrNull { it.tag == name.removePrefix("unit_") }?.let { FitEvent.UnitSelected(it) }
                name == "menu_clear" -> FitEvent.ClearAllClicked
                name == "menu_exit" -> FitEvent.ExitClicked
                name == "dialog_cancel" -> FitEvent.DialogDismissed
                name == "dialog_confirm" -> FitEvent.ClearConfirmed
                name == "blocked_exit" -> FitEvent.BlockedExit
                name == "blocked_retry" -> FitEvent.BlockedRetry
                name == "snackbar_action" -> FitEvent.SnackActionClicked
                name == "coach_ok" -> FitEvent.CoachDismissed
                else -> null
            }
        if (e == null) FitLog.w("CMD ui unknown '$name'") else vm.onEvent(e)
    }

    /** A piece by template at a Stage point (x, z; yaw in degrees, default: facing the viewer on a room axis), or like a tile press. */
    private fun place(vm: FitViewModel, scene: FitScene, templateId: String, nums: List<Float>) {
        if (nums.size < 2) {
            vm.onEvent(FitEvent.TileClicked(templateId))
            return
        }
        val t = Templates.byId(templateId)
        val center = P2(nums[0], nums[1])
        val yaw = nums.getOrNull(2)?.let { Math.toRadians(it.toDouble()).toFloat() } ?: facing(vm, scene, center)
        val p = Piece(vm.debugNextId(), t.id, t.dims, center, yaw)
        vm.debugPieces(vm.state.value.pieces + p, p.id)
        FitLog.i(String.format(Locale.US, "CMD place id=%d t=%s at=(%.3f, %.3f)", p.id, t.id, center.x, center.z))
    }

    private fun facing(vm: FitViewModel, scene: FitScene, center: P2): Float {
        val head = scene.headPose() ?: return 0f
        return vm.debugRoom().snapYaw(yawOf(P2.of(head.position) - center))
    }

    /** Resets to an empty, calm screen: no pieces, no menu / dialog / sizing, tracking ok, not blocked. */
    private fun base(vm: FitViewModel, scene: FitScene) {
        FitGraph.head.forceLost = false
        FitGraph.anchors.failNext = false
        FitGraph.anchors.holdMs = 0L
        scene.debugNoFloor = false
        scene.debugDragCancel()
        if (vm.state.value.look != Look.SOLID) vm.onEvent(FitEvent.LookSelected(Look.SOLID))
        vm.debugSet { it.copy(menuOpen = false, clearDialog = false, sizing = null, blocked = false, holdStarting = false, restore = io.embodit.furniturefit.ui.fit.RestoreState.NONE, obstaclesShown = false, dragging = null) }
        if (vm.state.value.pieces.isNotEmpty()) vm.debugPieces(emptyList(), null)
        vm.debugSet {
            val stale = it.save == io.embodit.furniturefit.ui.fit.SaveState.UNSAVED || it.save == io.embodit.furniturefit.ui.fit.SaveState.SAVING
            it.copy(coach = false, save = if (stale) io.embodit.furniturefit.ui.fit.SaveState.NONE else it.save, savingPiece = if (stale) null else it.savingPiece)
        }
    }

    /**
     * A spot in view where [templateId] fits (QA screenshots need the piece on screen and, for most
     * states, clear of the room's real furniture): view directions within 30 degrees, 0.9 - 2.2 m,
     * facing the viewer on a room axis. Falls back to the design's landing point.
     */
    private fun ahead(vm: FitViewModel, scene: FitScene, templateId: String): Piece? {
        val head = scene.headPose() ?: return null
        val t = Templates.byId(templateId)
        val room = vm.debugRoom()
        val foot = P2.of(head.position)
        val fwd = head.level()
        val id = vm.debugNextId()
        for (deg in listOf(0f, -10f, 10f, -20f, 20f, -30f, 30f)) {
            val r = Math.toRadians(deg.toDouble())
            val dir = P2((fwd.x * kotlin.math.cos(r) - fwd.z * kotlin.math.sin(r)).toFloat(), (fwd.x * kotlin.math.sin(r) + fwd.z * kotlin.math.cos(r)).toFloat())
            for (dist in listOf(2.0f, 1.8f, 2.2f, 1.6f, 2.4f, 1.4f, 1.2f, 1.0f)) {
                val c = foot + dir * dist
                val p = Piece(id, t.id, t.dims, c, room.snapYaw(yawOf(dir * -1f)))
                val fit = FitRules.check(p, room, vm.state.value.pieces)
                if (fit.verdict == io.embodit.furniturefit.domain.Verdict.FITS && !FitRules.inside(p, head.position)) return p
            }
        }
        FitLog.w("CMD no free spot in view for $templateId: using the landing point")
        return FitRules.placement(t, t.dims, head, room, id)
    }

    /**
     * A piece with its back on a wall (or a cabinet side) in view: [gap] m off the face (negative = into
     * it). For a positive gap the piece is snapped and must fit; for a negative one the wall must be its
     * only collision.
     */
    private fun againstWall(vm: FitViewModel, scene: FitScene, templateId: String, gap: Float): Piece? {
        val head = scene.headPose() ?: return null
        val room = vm.debugRoom()
        val foot = P2.of(head.position)
        val fwd = head.level()
        val t = Templates.byId(templateId)
        val id = vm.debugNextId()
        var best: Piece? = null
        var bestAngle = Float.MAX_VALUE
        for (w in room.walls) {
            val n = w.inward ?: continue
            if (w.prism.hx * 2f < t.dims.w || w.prism.y0 > room.floorY!! + 0.3f) continue
            val along = P2(n.z, -n.x)
            val face = w.prism.center + n * w.prism.hz
            val reach = w.prism.hx - t.dims.w / 2f
            var s = -reach
            while (s <= reach + 1e-3f) {
                var p = Piece(id, t.id, t.dims, face + along * s + n * (t.dims.d / 2f + gap), yawOf(n))
                if (gap > 0f) p = FitRules.snap(p, room, emptyList()).first
                val fit = FitRules.check(p, room, emptyList())
                val ok = if (gap > 0f) fit.verdict == io.embodit.furniturefit.domain.Verdict.FITS else fit.hitWalls == setOf(w.id) && fit.hitReal.isEmpty() && !fit.outOfRoom
                val to = p.center - foot
                val dist = to.length
                if (ok && dist in 1.0f..4.0f) {
                    val ang = Math.toDegrees(kotlin.math.acos((to.normalized().dot(fwd)).coerceIn(-1f, 1f).toDouble())).toFloat()
                    if (ang < bestAngle) {
                        bestAngle = ang
                        best = p
                    }
                }
                s += 0.2f
            }
        }
        FitLog.i("CMD wall spot angle=${String.format(Locale.US, "%.1f", bestAngle)} found=${best != null}")
        return best
    }

    private fun later(ms: Long, block: () -> Unit) = main.postDelayed(block, ms)

    private fun state(vm: FitViewModel, scene: FitScene, id: String) {
        base(vm, scene)
        fun put(vararg pieces: Piece?, select: Boolean = true) {
            val list = pieces.filterNotNull()
            if (list.isEmpty()) {
                FitLog.w("CMD state $id: no room yet (floor / head unknown)")
                return
            }
            vm.debugPieces(list, if (select) list.last().id else null)
        }
        when (id) {
            "S1" -> vm.debugSet { it.copy(holdStarting = true) }
            "S2" -> scene.debugNoFloor = true
            "S3" -> Unit
            "S4" -> put(ahead(vm, scene, "sofa3"), select = false)
            "S5" -> put(ahead(vm, scene, "sofa3"))
            "S6" -> {
                val p = ahead(vm, scene, "sofa2") ?: return
                put(p)
                val head = scene.headPose() ?: return
                val right = P2(head.right.x, head.right.z).normalized()
                later(2000) { FitLog.i("CMD ${scene.debugGlide(p.id, p.center + right * 0.5f, 1500, hold = true)}") }
            }
            "S7" -> put(againstWall(vm, scene, "sofa2", 0.02f)?.let { FitRules.snap(it, vm.debugRoom(), emptyList()).first })
            "S8" -> put(againstWall(vm, scene, "sofa2", -0.15f))
            "S9" -> {
                val zone = vm.debugRoom().doorZones.firstOrNull()
                if (zone == null) {
                    FitLog.w("CMD state S9: no DOOR plane known yet")
                    return
                }
                val t = Templates.byId("armchair")
                put(Piece(vm.debugNextId(), t.id, t.dims, zone.prism.center, zone.prism.yaw))
            }
            "S10" -> {
                put(ahead(vm, scene, "sofa2"))
                later(300) { vm.onEvent(FitEvent.SizeClicked) }
            }
            "S11" -> {
                val head = scene.headPose() ?: return
                val t = Templates.byId("armchair")
                put(Piece(vm.debugNextId(), t.id, t.dims, P2.of(head.position), facing(vm, scene, P2.of(head.position) + P2(0f, -1f))), select = false)
            }
            "S12" -> {
                put(ahead(vm, scene, "sofa3"), select = false)
                vm.onEvent(FitEvent.LookSelected(Look.WIRE))
            }
            "S13" -> {
                put(ahead(vm, scene, "sofa3"), select = false)
                later(300) { vm.onEvent(FitEvent.MoreClicked) }
            }
            "S14" -> {
                put(ahead(vm, scene, "sofa3"), select = false)
                later(300) { vm.debugSet { it.copy(clearDialog = true) } }
            }
            "S15" -> {
                put(ahead(vm, scene, "sofa3"), select = false)
                // wait for the anchor and the save (0.5 s after the change), then restore from storage
                later(4000) { vm.debugRestore() }
            }
            "S16" -> {
                put(ahead(vm, scene, "sofa3"), select = false)
                later(4000) { vm.debugRestoreTimeout() }
            }
            "S17" -> {
                vm.debugClearAnchor()
                FitGraph.anchors.failNext = true
                put(ahead(vm, scene, "armchair"))
            }
            "S18" -> {
                val first = ahead(vm, scene, "bookcase") ?: return
                val head = scene.headPose() ?: return
                val right = P2(head.right.x, head.right.z).normalized()
                val fwd = head.level()
                val t = Templates.byId("bookcase")
                // twelve 80 x 30 bookcases, 4 x 3, 5 cm apart: the limit without collisions between them
                val pieces = (0 until Templates.MAX_PIECES).map { i -> Piece(vm.debugNextId(), t.id, t.dims, first.center + right * ((i % 4 - 1.5f) * 0.85f) + fwd * ((i / 4) * 0.36f), first.yaw) }
                put(*pieces.toTypedArray(), select = false)
            }
            "S19" -> {
                put(ahead(vm, scene, "sofa3"), select = false)
                later(300) { FitGraph.head.forceLost = true }
            }
            "S20" -> vm.debugSet { it.copy(blocked = true) }
            "S21" -> {
                vm.debugClearAnchor()
                FitGraph.anchors.holdMs = 60_000L
                put(ahead(vm, scene, "sofa2"))
            }
            "S22" -> {
                vm.debugCoachReset()
                put(ahead(vm, scene, "sofa2"), select = false)
                vm.debugSet { it.copy(coach = true) }
            }
            else -> FitLog.w("CMD state unknown '$id'")
        }
        FitLog.i("CMD state $id requested")
    }
}
