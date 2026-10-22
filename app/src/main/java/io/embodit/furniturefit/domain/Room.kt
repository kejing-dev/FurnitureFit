package io.embodit.furniturefit.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A detected plane (plane detection): a polygon with its vertices in Stage metres and the platform's
 * semantic label by name (`SemanticLabelType.name`). Winding is not trusted; the normal's sign is
 * arbitrary (wall quads face outwards on the emulator).
 */
data class PlaneSurface(val id: String, val semantic: String, val vertices: List<Vec3>) {
    val normal: Vec3 by lazy {
        var best = Vec3.UP
        var bestLen = 0f
        for (i in 1 until vertices.size - 1) {
            val c = (vertices[i] - vertices[0]).cross(vertices[i + 1] - vertices[0])
            if (c.length > bestLen) {
                bestLen = c.length
                best = c
            }
        }
        if (bestLen < 1e-9f) Vec3.UP else best.normalized()
    }

    val centroid: Vec3 by lazy { if (vertices.isEmpty()) Vec3.ZERO else vertices.fold(Vec3.ZERO) { a, v -> a + v } / vertices.size.toFloat() }

    val isVertical: Boolean
        get() = abs(normal.y) < VERTICAL_MAX_NY

    val isHorizontal: Boolean
        get() = abs(normal.y) > HORIZONTAL_MIN_NY

    /** Area of the polygon (fan). */
    val area: Float by lazy {
        var a = Vec3.ZERO
        for (i in 1 until vertices.size - 1) a = a + (vertices[i] - vertices[0]).cross(vertices[i + 1] - vertices[0])
        a.length / 2f
    }

    /** `SEMANTIC:uuid8`, used in entity names and logs. */
    val label: String
        get() = "$semantic:${id.take(8)}"

    companion object {
        const val VERTICAL_MAX_NY = 0.2f
        const val HORIZONTAL_MIN_NY = 0.9f
    }
}

/** One vertex of the spatial mesh in Stage metres with its per-vertex semantic (by name). */
data class MeshPoint(val position: Vec3, val semantic: String)

enum class ObstacleKind {
    /** Wall, door, window and other room surfaces: a 4 cm slab behind the plane. */
    WALL,

    /** A vertical plane of real furniture (a cabinet's side): also a slab. */
    SIDE,

    /** A piece of real furniture from the spatial mesh (or a table / cabinet top pulled down to the floor). */
    REAL,
}

/** Something a piece must not go into. [prism] is in Stage metres; [inward] is the slab's room side (walls). */
data class Obstacle(val id: String, val kind: ObstacleKind, val semantic: String, val prism: Prism, val inward: P2? = null)

/** The 0.8 m in front of a door, on the room side. */
data class DoorZone(val id: String, val prism: Prism, val width: Float)

/**
 * The room as the fitting rules see it, rebuilt whenever planes or the mesh change (pure: built from
 * plain data, unit-tested).
 */
data class RoomModel(
    val floorY: Float?,
    val floors: List<List<P2>>,
    val obstacles: List<Obstacle>,
    val doorZones: List<DoorZone>,
    /** Yaw of the room's main axis (the normal of the largest wall, into the room), or null without walls. */
    val axisYaw: Float?,
    /** A point inside the room on the floor (centre of the largest floor, else the head). */
    val interior: P2,
) {
    val hasFloor: Boolean
        get() = floorY != null

    val walls: List<Obstacle>
        get() = obstacles.filter { it.kind != ObstacleKind.REAL }

    val real: List<Obstacle>
        get() = obstacles.filter { it.kind == ObstacleKind.REAL }

    /** The yaw of the four room axes nearest to [yaw]; [yaw] itself when the room has no axis yet. */
    fun snapYaw(yaw: Float, axis: Float? = axisYaw): Float {
        val a = axis ?: return yaw
        val quarter = (Math.PI / 2).toFloat()
        val k = Math.round((wrapAngle(yaw - a) / quarter).toDouble()).toFloat()
        return wrapAngle(a + k * quarter)
    }

    /** Is [p] on the floor: inside a floor polygon or within [margin] of one. True while no floor is known. */
    fun onFloor(p: P2, margin: Float): Boolean {
        if (floors.isEmpty()) return true
        return floors.any { Geo2.inside(p, it) || Geo2.distanceToEdge(p, it) <= margin }
    }

    companion object {
        val EMPTY = RoomModel(null, emptyList(), emptyList(), emptyList(), null, P2.ZERO)

        const val SLAB_M = 0.04f
        const val DOOR_ZONE_DEPTH_M = 0.8f
        const val CLUSTER_CELL_M = 0.15f
        const val MIN_CLUSTER_SIDE_M = 0.10f
        const val MIN_CLUSTER_HEIGHT_M = 0.10f

        /** Not obstacles at all. */
        val PASSIVE = setOf("FLOOR", "CEILING", "WALL_ART", "HUMAN", "UNKNOWN", "OPENING", "STAIRWAY")

        /** Room surfaces (slabs of kind WALL). */
        val ROOM_SURFACES = setOf("WALL", "DOOR", "WINDOW", "VIRTUAL_WALL", "BEAM", "CURTAIN")

        /** Mesh semantics that make a real-furniture body. */
        val FURNITURE =
            setOf("SOFA", "CHAIR", "TABLE", "BED", "CABINET", "REFRIGERATOR", "WASHING_MACHINE", "PLANT", "LAMP", "COLUMN", "SCREEN", "AIR_CONDITIONER")

        /**
         * Builds the room from the planes, the spatial mesh and (when no floor is known) the head.
         * [meshPoints] may be empty: then horizontal furniture planes are pulled down to the floor.
         */
        fun build(planes: Collection<PlaneSurface>, meshPoints: List<MeshPoint>, head: Vec3?): RoomModel {
            val floorPlanes = planes.filter { it.semantic == "FLOOR" && it.isHorizontal && it.vertices.size >= 3 }
            val mainFloor = floorPlanes.maxByOrNull { it.area }
            val floorY = mainFloor?.centroid?.y
            val floors = floorPlanes.map { f -> f.vertices.map { P2.of(it) } }
            val interior = mainFloor?.let { P2.of(it.centroid) } ?: head?.let { P2.of(it) } ?: P2.ZERO
            val obstacles = ArrayList<Obstacle>()
            val doors = ArrayList<DoorZone>()
            var axis: Float? = null
            var axisArea = 0f
            for (p in planes) {
                if (p.vertices.size < 3 || p.semantic in PASSIVE && p.semantic != "OPENING") continue
                if (p.isVertical) {
                    val slab = slab(p, interior) ?: continue
                    if (p.semantic == "OPENING") continue
                    val kind = if (p.semantic in ROOM_SURFACES) ObstacleKind.WALL else ObstacleKind.SIDE
                    obstacles += Obstacle(p.label, kind, p.semantic, slab.first, slab.second)
                    if (p.semantic == "WALL" && p.area > axisArea) {
                        axisArea = p.area
                        axis = yawOf(slab.second)
                    }
                    if (p.semantic == "DOOR") doorZone(p, slab.second, floorY)?.let { doors += it }
                }
            }
            val clusters = if (floorY != null) clusters(meshPoints, floorY) else emptyList()
            if (clusters.isNotEmpty()) {
                obstacles += clusters
            } else if (floorY != null) {
                // no mesh yet: horizontal furniture planes become pillars down to the floor
                for (p in planes) {
                    if (!p.isHorizontal || p.semantic !in FURNITURE || p.vertices.size < 3) continue
                    val r = Geo2.minRect(p.vertices.map { P2.of(it) }) ?: continue
                    val top = p.vertices.maxOf { it.y }
                    if (top - floorY < MIN_CLUSTER_HEIGHT_M) continue
                    obstacles += Obstacle(p.label, ObstacleKind.REAL, p.semantic, Prism(r.center, r.hx, r.hz, r.yaw, floorY, top))
                }
            }
            return RoomModel(floorY, floors, obstacles, doors, axis, interior)
        }

        /** The slab behind a vertical plane, thickened away from the room; and the room-side normal. */
        fun slab(p: PlaneSurface, interior: P2): Pair<Prism, P2>? {
            val n = P2(p.normal.x, p.normal.z).normalized()
            if (n.length < 0.5f) return null
            val c = P2.of(p.centroid)
            val inward = if ((interior - c).dot(n) >= 0f) n else n * -1f
            val u = P2(inward.z, -inward.x)
            var u0 = Float.MAX_VALUE
            var u1 = -Float.MAX_VALUE
            var y0 = Float.MAX_VALUE
            var y1 = -Float.MAX_VALUE
            var off = 0f
            for (v in p.vertices) {
                val q = P2.of(v)
                val du = (q - c).dot(u)
                u0 = min(u0, du)
                u1 = max(u1, du)
                y0 = min(y0, v.y)
                y1 = max(y1, v.y)
                off += (q - c).dot(inward)
            }
            off /= p.vertices.size
            if (u1 - u0 < 0.02f || y1 - y0 < 0.02f) return null
            val face = c + u * ((u0 + u1) / 2f) + inward * off
            val center = face - inward * (SLAB_M / 2f)
            return Prism(center, (u1 - u0) / 2f, SLAB_M / 2f, yawOf(inward), y0, y1) to inward
        }

        /** Width of the door, 0.8 m deep into the room from the door's foot (projected to the floor). */
        private fun doorZone(p: PlaneSurface, inward: P2, floorY: Float?): DoorZone? {
            val c = P2.of(p.centroid)
            val u = P2(inward.z, -inward.x)
            var u0 = Float.MAX_VALUE
            var u1 = -Float.MAX_VALUE
            var off = 0f
            for (v in p.vertices) {
                val du = (P2.of(v) - c).dot(u)
                u0 = min(u0, du)
                u1 = max(u1, du)
                off += (P2.of(v) - c).dot(inward)
            }
            off /= p.vertices.size
            val width = u1 - u0
            if (width < 0.3f) return null
            val foot = c + u * ((u0 + u1) / 2f) + inward * off
            val y = floorY ?: p.vertices.minOf { it.y }
            val center = foot + inward * (DOOR_ZONE_DEPTH_M / 2f)
            return DoorZone(p.label, Prism(center, width / 2f, DOOR_ZONE_DEPTH_M / 2f, yawOf(inward), y, y + 2f), width)
        }

        /**
         * Real furniture from the spatial mesh: vertices of furniture semantics grouped by semantic and
         * by neighbourhood (cells of 15 cm, touching cells join), each group the smallest rectangle
         * around its floor projection, from the floor up to its highest vertex. Small groups are noise.
         */
        fun clusters(points: List<MeshPoint>, floorY: Float): List<Obstacle> {
            val out = ArrayList<Obstacle>()
            val bySemantic = points.filter { it.semantic in FURNITURE }.groupBy { it.semantic }
            for ((semantic, list) in bySemantic) {
                val cells = HashMap<Long, MutableList<Vec3>>()
                for (pt in list) {
                    val k = key(cell(pt.position.x), cell(pt.position.y), cell(pt.position.z))
                    cells.getOrPut(k) { ArrayList() } += pt.position
                }
                val seen = HashSet<Long>()
                var n = 0
                for (start in cells.keys) {
                    if (!seen.add(start)) continue
                    val members = ArrayList<Vec3>()
                    val queue = ArrayDeque<Long>()
                    queue += start
                    while (queue.isNotEmpty()) {
                        val k = queue.removeFirst()
                        members += cells[k] ?: continue
                        val (x, y, z) = unkey(k)
                        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                            val nk = key(x + dx, y + dy, z + dz)
                            if (nk in cells && seen.add(nk)) queue += nk
                        }
                    }
                    val r = Geo2.minRect(members.map { P2.of(it) }) ?: continue
                    val top = members.maxOf { it.y }
                    if (r.hx * 2f < MIN_CLUSTER_SIDE_M || r.hz * 2f < MIN_CLUSTER_SIDE_M || top - floorY < MIN_CLUSTER_HEIGHT_M) continue
                    out += Obstacle("mesh:$semantic:${n++}", ObstacleKind.REAL, semantic, Prism(r.center, r.hx, r.hz, r.yaw, floorY, top))
                }
            }
            return out
        }

        private fun cell(v: Float): Int = floor(v / CLUSTER_CELL_M).toInt()

        private fun key(x: Int, y: Int, z: Int): Long = ((x + OFFSET).toLong() shl 42) or ((y + OFFSET).toLong() shl 21) or (z + OFFSET).toLong()

        private fun unkey(k: Long): Triple<Int, Int, Int> =
            Triple(((k shr 42) and MASK).toInt() - OFFSET, ((k shr 21) and MASK).toInt() - OFFSET, (k and MASK).toInt() - OFFSET)

        private const val OFFSET = 1 shl 20
        private const val MASK = (1L shl 21) - 1
    }
}
