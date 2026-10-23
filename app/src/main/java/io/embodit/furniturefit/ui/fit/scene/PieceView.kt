package io.embodit.furniturefit.ui.fit.scene

import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Vector3
import io.embodit.furniturefit.domain.Dims
import io.embodit.furniturefit.domain.Finish
import io.embodit.furniturefit.domain.Look
import io.embodit.furniturefit.domain.P2
import io.embodit.furniturefit.domain.Piece
import io.embodit.furniturefit.domain.Quat4
import io.embodit.furniturefit.domain.Recipes
import io.embodit.furniturefit.domain.Templates
import io.embodit.furniturefit.domain.Vec3
import io.embodit.furniturefit.domain.Verdict
import kotlin.math.min

/** How a piece is drawn this frame (from the UI state and the live drag). */
internal data class PieceLook(
    val look: Look,
    val selected: Boolean,
    val verdict: Verdict,
    val inside: Boolean,
    val stale: Boolean,
    /** Footprint: 0 solid blue, 1 saving (yellow dashes), 2 unsaved (white dashes). */
    val footprint: Int,
) {
    /** One colour per piece, by priority stale > error > selected > normal (QA #1 q1-3: the red stayed under the grey). */
    val tint: PieceTint
        get() =
            when {
                stale -> PieceTint.STALE
                verdict == Verdict.COLLIDES -> PieceTint.ERROR
                selected -> PieceTint.SELECTED
                else -> PieceTint.NORMAL
            }
}

internal enum class PieceTint { STALE, ERROR, SELECTED, NORMAL }

/**
 * One piece of furniture (design `entities3d.piece`): the size box (12 edge bars + six see-through
 * faces), the basic-shape look, the masking-tape footprint, a contact shadow and the collider the
 * system pointer hits. Everything hangs below one root at the footprint centre on the floor, turned by
 * the piece's yaw; size changes re-scale the shared unit cube, no mesh is built.
 */
internal class PieceView(parent: Entity, private val meshes: FitMeshes, private val m: FitMaterials, val id: Int) {
    private val root = Entity()
    private val body = Entity()
    private val tapeRoot = Entity()
    private val collider = Entity()
    private val fill: Box
    private val edges: List<Box>
    private val skin: List<Box>
    private val shadow: Box
    private val tape: List<Strip>
    private val dashed: List<Strip>

    var dims: Dims? = null
        private set
    private var templateId = ""
    private var lastLook: PieceLook? = null
    private var footprintStartMs = -1L
    private var shownFloorY = 0f
    private var footKind = 0
    private var footProgress = 1f

    init {
        runCatching { root.setName("pieceRoot:$id") }
        parent.addChild(root)
        root.addChild(body)
        root.addChild(tapeRoot)
        runCatching { collider.setName("piece:$id") }
        root.addChild(collider)
        fill = Box(body, meshes.cube, m.fill, "pieceFill:$id")
        edges = List(12) { Box(body, meshes.cube, m.edge, "pieceEdge:$id:$it") }
        skin = List(Recipes.MAX_BOXES) { Box(body, meshes.cube, m.fabric, "pieceSkin:$id:$it") }
        shadow = Box(body, meshes.square, m.shadow, "pieceShadow:$id")
        tape = List(4) { Strip(tapeRoot, meshes.band, m.tapeBlue, "footprint:$id:$it") }
        dashed = List(4) { Strip(tapeRoot, meshes.dashes, m.tapeSaving, "footprintDash:$id:$it") }
    }

    /** True when [entity] is this piece's collider. */
    fun owns(entity: Entity): Boolean = entity.id == collider.id

    /** Footprint centre and yaw on the floor at [floorY]; [lift] raises the whole piece (drop / rise). */
    fun pose(center: P2, yaw: Float, floorY: Float, lift: Float = 0f) {
        shownFloorY = floorY
        root.setLocal(position = Vec3(center.x, floorY + lift, center.z), rotation = Quat4.axisAngle(Vec3.UP, yaw))
        // the footprint stays on the floor while the piece drops or rises
        tapeRoot.setLocal(position = Vec3(0f, -lift, 0f))
    }

    /** Builds the size box, the look and the collider for [piece]'s size (only when it changed). */
    fun shape(piece: Piece) {
        if (piece.dims == dims && piece.templateId == templateId) return
        dims = piece.dims
        templateId = piece.templateId
        val d = piece.dims
        fill.place(Vec3(0f, d.h / 2f, 0f), Vec3(d.w, d.h, d.d))
        val e = EDGE_M
        val hw = d.w / 2f - e / 2f
        val hd = d.d / 2f - e / 2f
        var i = 0
        // four uprights, then the bottom ring (shown in the solid look), then the top ring
        for (sx in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f)) edges[i++].place(Vec3(sx * hw, d.h / 2f, sz * hd), Vec3(e, d.h, e))
        for (y in listOf(e / 2f, d.h - e / 2f)) {
            for (sz in listOf(-1f, 1f)) edges[i++].place(Vec3(0f, y, sz * hd), Vec3(d.w, e, e))
            for (sx in listOf(-1f, 1f)) edges[i++].place(Vec3(sx * hw, y, 0f), Vec3(e, e, d.d))
        }
        val boxes = Recipes.boxes(Templates.byId(piece.templateId).shape, d)
        for (k in skin.indices) {
            val b = boxes.getOrNull(k)
            if (b == null) skin[k].show(false) else skin[k].place(b.center, b.size)
        }
        shadow.place(Vec3(0f, 0.003f, 0f), Vec3(d.w + 0.06f, 1f, d.d + 0.06f))
        collider.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(d.w, d.h, d.d))), physicsMaterial = PhysicsMaterialResource()))
        if (collider.components[InteractableComponent::class.java] == null) collider.components.set(InteractableComponent())
        collider.setLocal(position = Vec3(0f, d.h / 2f, 0f))
        drawFootprint(1f)
        lastLook = null
    }

    /** Starts the footprint's "slap" (it is taped down from one corner in 0.35 s). */
    fun slap(nowMs: Long) {
        footprintStartMs = nowMs
    }

    /** Per-frame animation of the footprint; returns true while it runs. */
    fun animate(nowMs: Long): Boolean {
        if (footprintStartMs < 0) return false
        val t = ((nowMs - footprintStartMs) / SLAP_MS).coerceIn(0f, 1f)
        drawFootprint(t)
        if (t >= 1f) footprintStartMs = -1L
        return t < 1f
    }

    /** The four sides of masking tape, laid one after the other as [progress] goes 0 -> 1. */
    private fun drawFootprint(progress: Float) {
        val d = dims ?: return
        footProgress = progress
        val inset = TAPE_M / 2f
        val y = FOOT_Y
        val c = listOf(Vec3(-d.w / 2f + inset, y, d.d / 2f - inset), Vec3(d.w / 2f - inset, y, d.d / 2f - inset), Vec3(d.w / 2f - inset, y, -d.d / 2f + inset), Vec3(-d.w / 2f + inset, y, -d.d / 2f + inset))
        val active = if (footKind == 0) tape else dashed
        val other = if (footKind == 0) dashed else tape
        other.forEach { it.hide() }
        for (k in 0 until 4) {
            val a = c[k]
            val b = c[(k + 1) % 4]
            val dir = (b - a).normalized()
            // a little overlap at the corners, like torn tape
            val from = a - dir * (TAPE_M / 2f + 0.006f)
            val full = b + dir * (TAPE_M / 2f + 0.006f)
            val share = ((progress * 4f) - k).coerceIn(0f, 1f)
            if (share <= 0f) active[k].hide() else active[k].draw(from, from + (full - from) * share, TAPE_M, Vec3.UP)
        }
    }

    fun look(l: PieceLook) {
        if (l == lastLook) return
        lastLook = l
        val wire = l.look == Look.WIRE
        val tint = l.tint
        val error = tint == PieceTint.ERROR
        val edgeMat =
            when (tint) {
                PieceTint.STALE -> m.edgeStale
                PieceTint.ERROR -> m.edgeError
                PieceTint.SELECTED -> m.edgeSelected
                PieceTint.NORMAL -> m.edge
            }
        edges.forEachIndexed { i, b ->
            b.material(edgeMat)
            // solid look: only the bottom ring of the size box (indices 4 - 7); wireframe: all twelve
            b.show(wire || i in 4..7 || l.selected || error)
        }
        fill.material(if (error) m.fillError else m.fill)
        fill.show(wire || error)
        val boxes = dims?.let { Recipes.boxes(Templates.byId(templateId).shape, it) } ?: emptyList()
        for (k in skin.indices) {
            val b = boxes.getOrNull(k)
            if (b == null || wire) {
                skin[k].show(false)
            } else {
                skin[k].show(true)
                skin[k].material(
                    when {
                        tint == PieceTint.STALE -> m.stale
                        error -> if (b.finish == Finish.FABRIC) m.fabricError else m.woodError
                        else -> if (b.finish == Finish.FABRIC) m.fabric else m.wood
                    }
                )
            }
        }
        shadow.show(!wire)
        body.setOpacity(if (l.inside) INSIDE_OPACITY else 1f)
        applyFootprint(l.footprint, l.stale)
    }

    private fun applyFootprint(kind: Int, stale: Boolean) {
        tape.forEach { it.material(if (stale) m.tapeStale else m.tapeBlue) }
        dashed.forEach { it.material(if (kind == 1) m.tapeSaving else m.tapeUnsaved) }
        if (kind != footKind) {
            footKind = kind
            drawFootprint(footProgress)
        }
    }

    fun destroy() {
        runCatching { root.destroy(true) }
    }

    companion object {
        const val EDGE_M = 0.008f
        const val TAPE_M = 0.04f
        const val FOOT_Y = 0.002f
        const val SLAP_MS = 350f
        const val INSIDE_OPACITY = 0.25f
        const val DROP_M = 0.20f
        const val DROP_MS = 300f
        const val RISE_MS = 600f

        fun heightLift(h: Float): Float = -min(h, 2f)
    }
}
