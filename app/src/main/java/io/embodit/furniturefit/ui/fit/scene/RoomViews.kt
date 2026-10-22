package io.embodit.furniturefit.ui.fit.scene

import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Vector3
import io.embodit.furniturefit.domain.Gap
import io.embodit.furniturefit.domain.GapKind
import io.embodit.furniturefit.domain.P2
import io.embodit.furniturefit.domain.PlaneSurface
import io.embodit.furniturefit.domain.Prism
import io.embodit.furniturefit.domain.Quat4
import io.embodit.furniturefit.domain.Vec3
import io.embodit.furniturefit.platform.FitLog
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * One gap tape (design `entities3d.gapTape`, the Spatial Ruler blade): printed 10 cm segments tiled
 * from the piece's side to the obstacle, a plain blade for the rest, the red zero end at the piece and
 * a steel hook at the far end. The face turns towards the viewer about the tape's length. A side that
 * touches gets the short yellow contact bar instead (`entities3d.contactMark`).
 */
internal class GapTapeView(parent: Entity, meshes: FitMeshes, private val m: FitMaterials, index: Int) {
    private val segments = List(MAX_SEGMENTS) { Strip(parent, meshes.segment, m.tapeFace, "tapeSeg:$index:$it") }
    private val rest = Strip(parent, meshes.band, m.blade, "tapeRest:$index")
    private val zero = Strip(parent, meshes.band, m.tapeFace, "tapeZero:$index")
    private val hook = Strip(parent, meshes.band, m.steel, "tapeHook:$index")
    private val contact = Strip(parent, meshes.band, m.contact, "contact:$index")
    private val stem = Strip(parent, meshes.band, m.stem, "stem:$index")

    init {
        zero.material(m.meterRed)
    }

    /** Draws [gap] at height [y]; returns where its reading plate's foot is (null for a contact bar). */
    fun draw(gap: Gap, y: Float, head: Vec3, sideHalf: Float): Vec3? {
        val from = gap.from.at(y)
        val to = gap.to.at(y)
        if (gap.kind == GapKind.CONTACT) {
            hideTape()
            val n = gap.normal.at(0f)
            val alongDir = P2(-gap.normal.z, gap.normal.x).at(0f)
            val half = sideHalf * 0.6f
            // the bar lies on the side that touches, a hair in front of it
            val mid = from - n * 0.004f
            contact.draw(mid - alongDir * half, mid + alongDir * half, CONTACT_M, (head - mid).rejectFrom(Vec3.UP).let { if (it.length < 1e-3f) n else it })
            return null
        }
        contact.hide()
        val full = to - from
        val length = full.length
        if (length < 1e-3f) {
            hideTape()
            return null
        }
        val dir = full / length
        val face = (head - (from + to) * 0.5f).let { if (it.rejectFrom(dir).length < 1e-3f) Vec3.UP else it }
        val n = min(MAX_SEGMENTS, floor(length / MeshFactory.SEGMENT_M).toInt())
        for (i in segments.indices) {
            if (i < n) segments[i].drawFixed(from + dir * (i * MeshFactory.SEGMENT_M), dir, MeshFactory.SEGMENT_M, face) else segments[i].hide()
        }
        val done = n * MeshFactory.SEGMENT_M
        if (length - done > 1e-3f) rest.draw(from + dir * done, to, FitMeshes.TAPE_WIDTH_M, face) else rest.hide()
        zero.draw(from, from + dir * min(0.006f, length), FitMeshes.TAPE_WIDTH_M, face)
        hook.draw(to - dir * 0.003f, to, FitMeshes.TAPE_WIDTH_M * 1.4f, face)
        val mid = from + dir * (length / 2f)
        stem.draw(mid, mid + Vec3(0f, STEM_M, 0f), 0.002f, face.rejectFrom(Vec3.UP).let { if (it.length < 1e-3f) Vec3.AHEAD else it })
        return mid + Vec3(0f, STEM_M, 0f)
    }

    private fun hideTape() {
        segments.forEach { it.hide() }
        rest.hide()
        zero.hide()
        hook.hide()
        stem.hide()
    }

    fun hide() {
        hideTape()
        contact.hide()
    }

    companion object {
        const val MAX_SEGMENTS = 20
        const val CONTACT_M = 0.006f
        const val STEM_M = 0.05f
    }
}

/** A dashed rectangle on the floor (door zone edge, real-furniture outline) with an optional fill. */
internal class FloorRect(parent: Entity, meshes: FitMeshes, edge: com.pico.spatial.core.ecs.resource.Material, fill: com.pico.spatial.core.ecs.resource.Material?, name: String) {
    private val sides = List(4) { Strip(parent, meshes.dashes, edge, "$name:edge$it") }
    private val area: Box? = fill?.let { Box(parent, meshes.square, it, "$name:fill").also { b -> b.show(false) } }

    fun draw(p: Prism, y: Float, width: Float) {
        val c = p.corners
        for (k in 0 until 4) sides[k].draw(c[k].at(y + 0.003f), c[(k + 1) % 4].at(y + 0.003f), width, Vec3.UP)
        area?.place(p.center.at(y + 0.0025f), Vec3(p.hx * 2f, 1f, p.hz * 2f), Quat4.axisAngle(Vec3.UP, p.yaw))
    }

    fun hide() {
        sides.forEach { it.hide() }
        area?.show(false)
    }
}

/**
 * Tap targets of the room for the system pointer (Spatial Ruler): every plane gets a thin two-sided
 * box collider (a plane's own static-mesh collider is one-sided and wall quads face outwards); a shell
 * of slabs catches taps that hit nothing. A tap on any of them = "tapped the empty room" (deselect,
 * close the menu); the design's `floorHit`.
 */
internal class RoomColliders(private val root: Entity) {
    private class Collider(val entity: Entity, var center: Vec3, var size: Vec3, var rotation: Quat4)

    private val colliders = HashMap<String, Collider>()
    private val ids = HashSet<Long>()
    private var backstop: Entity? = null

    fun isRoom(entity: Entity): Boolean = entity.id in ids || runCatching { entity.getName() }.getOrNull()?.startsWith("backstop") == true

    fun sync(planes: Collection<PlaneSurface>) {
        val seen = HashSet<String>()
        for (s in planes) {
            seen += s.id
            upsert(s)
        }
        for (id in colliders.keys.filter { it !in seen }) colliders.remove(id)?.let {
            ids.remove(it.entity.id)
            runCatching { it.entity.destroy(true) }
        }
    }

    private fun upsert(surface: PlaneSurface) {
        val fit = fit(surface) ?: return
        val existing = colliders[surface.id]
        if (existing == null) {
            val e = Entity()
            runCatching { e.setName("plane:${surface.label.replace('-', '_')}") }
            e.components.set(collision(fit.second))
            e.components.set(InteractableComponent())
            root.addChild(e)
            e.setLocal(position = fit.first, rotation = fit.third)
            colliders[surface.id] = Collider(e, fit.first, fit.second, fit.third)
            ids += e.id
            return
        }
        if (existing.size.distanceTo(fit.second) > 0.001f) {
            existing.entity.components.set(collision(fit.second))
            existing.size = fit.second
        }
        if (existing.center.distanceTo(fit.first) > 0.0005f || abs(existing.rotation.x - fit.third.x) + abs(existing.rotation.w - fit.third.w) > 1e-4f) {
            existing.entity.setLocal(position = fit.first, rotation = fit.third)
            existing.center = fit.first
            existing.rotation = fit.third
        }
    }

    fun buildBackstop() {
        if (backstop != null) return
        val shell = Entity()
        runCatching { shell.setName("backstop") }
        root.addChild(shell)
        val h = 9f
        val t = 0.2f
        val cy = 1.5f
        val slabs =
            listOf(
                Triple("px", Vec3(h, cy, 0f), Vec3(t, 2 * h, 2 * h)), Triple("nx", Vec3(-h, cy, 0f), Vec3(t, 2 * h, 2 * h)),
                Triple("pz", Vec3(0f, cy, h), Vec3(2 * h, 2 * h, t)), Triple("nz", Vec3(0f, cy, -h), Vec3(2 * h, 2 * h, t)),
                Triple("py", Vec3(0f, cy + h, 0f), Vec3(2 * h, t, 2 * h)), Triple("ny", Vec3(0f, cy - h, 0f), Vec3(2 * h, t, 2 * h)),
            )
        for ((tag, center, size) in slabs) {
            val slab = Entity()
            runCatching { slab.setName("backstop:$tag") }
            slab.components.set(collision(size))
            slab.components.set(InteractableComponent())
            shell.addChild(slab)
            slab.setLocal(position = center)
        }
        backstop = shell
    }

    fun clear() {
        colliders.values.forEach { runCatching { it.entity.destroy(true) } }
        colliders.clear()
        ids.clear()
        runCatching { backstop?.destroy(true) }
        backstop = null
    }

    private fun collision(size: Vec3) = CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(size.x, size.y, size.z))), physicsMaterial = PhysicsMaterialResource())

    /** Oriented box around the polygon in its own plane: centre, size (6 mm thick), rotation. */
    private fun fit(s: PlaneSurface): Triple<Vec3, Vec3, Quat4>? {
        val v = s.vertices
        if (v.size < 3) return null
        val n = s.normal
        var u = Vec3.ZERO
        for (i in v.indices) {
            val e = (v[(i + 1) % v.size] - v[i]).rejectFrom(n)
            if (e.length > 0.01f) {
                u = e.normalized()
                break
            }
        }
        if (u.length < 0.5f) return null
        val w = n.cross(u).normalized()
        var u0 = Float.MAX_VALUE
        var u1 = -Float.MAX_VALUE
        var w0 = Float.MAX_VALUE
        var w1 = -Float.MAX_VALUE
        for (p in v) {
            val d = p - v[0]
            u0 = minOf(u0, d.dot(u))
            u1 = maxOf(u1, d.dot(u))
            w0 = minOf(w0, d.dot(w))
            w1 = maxOf(w1, d.dot(w))
        }
        if (u1 - u0 < 0.01f || w1 - w0 < 0.01f) {
            FitLog.w("PLANES skip degenerate ${s.label}")
            return null
        }
        val center = v[0] + u * ((u0 + u1) / 2f) + w * ((w0 + w1) / 2f)
        return Triple(center, Vec3(u1 - u0, w1 - w0, 0.006f), Quat4.fromBasis(u, w, n))
    }
}
