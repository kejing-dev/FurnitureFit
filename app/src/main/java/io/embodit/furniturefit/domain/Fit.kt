package io.embodit.furniturefit.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

enum class Verdict { FITS, DOOR, COLLIDES }

enum class GapKind { CONTACT, TIGHT, PASSABLE }

/** The clearance in front of one side of a piece: from [from] (on the side) to [to] (on the obstacle). */
data class Gap(val side: Side, val distance: Float, val from: P2, val to: P2, val kind: GapKind, val what: String, val normal: P2 = P2.ZERO)

/** What the rules say about one piece. */
data class FitResult(
    val pieceId: Int,
    val verdict: Verdict,
    /** Other pieces it goes into. */
    val hitPieces: Set<Int>,
    /** Real furniture (obstacle ids) it goes into: outlined on the floor. */
    val hitReal: Set<String>,
    val hitWalls: Set<String>,
    val outOfRoom: Boolean,
    val doors: Set<String>,
)

/**
 * The fitting rules of the design (`rules.collide / door / gap / snap / rotate / place`): pure
 * geometry on [RoomModel] and the pieces, unit-tested. Distances in metres.
 */
object FitRules {
    const val COLLIDE_DEPTH_M = 0.01f
    const val OUT_OF_ROOM_MARGIN_M = 0.20f
    const val SNAP_M = 0.05f
    const val SNAP_PARALLEL_DEG = 5f
    const val SNAP_PIECE_OVERLAP_M = 0.10f
    const val SNAP_WALL_OVERLAP_M = 0.02f
    const val GAP_CONTACT_M = 0.05f
    const val GAP_PASSABLE_M = 0.80f
    const val GAP_MAX_M = 2.0f
    const val GAP_STEP_M = 0.02f
    const val GAP_END_INSET_M = 0.01f
    const val PLACE_AHEAD_M = 1.4f
    const val PLACE_WALL_CLEAR_M = 0.05f
    const val INSIDE_MARGIN_M = 0.15f
    const val MIN_SIZE_CM = 10
    const val MAX_SIZE_CM = 400

    private val snapCos = cos(Math.toRadians(SNAP_PARALLEL_DEG.toDouble())).toFloat()

    fun check(piece: Piece, room: RoomModel, pieces: List<Piece>): FitResult {
        val y = room.floorY ?: 0f
        val me = piece.prism(y)
        val hitPieces = HashSet<Int>()
        val hitReal = HashSet<String>()
        val hitWalls = HashSet<String>()
        for (o in room.obstacles) {
            if (!me.overlapsHeight(o.prism, COLLIDE_DEPTH_M)) continue
            if (Geo2.penetration(me, o.prism) > COLLIDE_DEPTH_M) {
                if (o.kind == ObstacleKind.REAL) hitReal += o.id else hitWalls += o.id
            }
        }
        for (p in pieces) {
            if (p.id == piece.id) continue
            val other = p.prism(y)
            if (me.overlapsHeight(other, COLLIDE_DEPTH_M) && Geo2.penetration(me, other) > COLLIDE_DEPTH_M) hitPieces += p.id
        }
        val out = room.hasFloor && !room.onFloor(piece.center, OUT_OF_ROOM_MARGIN_M)
        val doors = room.doorZones.filter { Geo2.penetration(me, it.prism) > 0f }.map { it.id }.toSet()
        val verdict =
            when {
                hitPieces.isNotEmpty() || hitReal.isNotEmpty() || hitWalls.isNotEmpty() || out -> Verdict.COLLIDES
                doors.isNotEmpty() -> Verdict.DOOR
                else -> Verdict.FITS
            }
        return FitResult(piece.id, verdict, hitPieces, hitReal, hitWalls, out, doors)
    }

    fun checkAll(room: RoomModel, pieces: List<Piece>): Map<Int, FitResult> = pieces.associate { it.id to check(it, room, pieces) }

    /** Obstacles a side of [piece] can face: room slabs, real furniture and the other pieces, sharing some height. */
    private fun blockers(piece: Piece, room: RoomModel, pieces: List<Piece>): List<Pair<String, Prism>> {
        val y = room.floorY ?: 0f
        val me = piece.prism(y)
        val out = ArrayList<Pair<String, Prism>>()
        for (o in room.obstacles) if (me.overlapsHeight(o.prism)) out += "${o.kind.name.lowercase()}:${o.id}" to o.prism
        for (p in pieces) if (p.id != piece.id) out += "piece:${p.id}" to p.prism(y)
        return out
    }

    /**
     * The smallest clearance in front of each side (scanned over the side's whole width every 2 cm):
     * < 5 cm contact, 5 - 80 cm tight, 80 - 200 cm passable, beyond 2 m nothing.
     */
    fun gaps(piece: Piece, room: RoomModel, pieces: List<Piece>): List<Gap> {
        val y = room.floorY ?: 0f
        val me = piece.prism(y)
        val blockers = blockers(piece, room, pieces)
        val out = ArrayList<Gap>()
        for (s in me.sides) {
            val half = s.half - GAP_END_INSET_M
            if (half <= 0f) continue
            val n = max(1, ceil(2f * half / GAP_STEP_M).toInt())
            var best = Float.MAX_VALUE
            var bestFrom = s.mid
            var what = ""
            for (i in 0..n) {
                val t = -half + 2f * half * i / n
                val o = s.mid + s.along * t
                for ((name, prism) in blockers) {
                    val d = Geo2.ray(o, s.normal, prism) ?: continue
                    if (d < best) {
                        best = d
                        bestFrom = o
                        what = name
                    }
                }
            }
            if (best > GAP_MAX_M) continue
            val kind =
                when {
                    best < GAP_CONTACT_M -> GapKind.CONTACT
                    best < GAP_PASSABLE_M -> GapKind.TIGHT
                    else -> GapKind.PASSABLE
                }
            out += Gap(s.side, best, bestFrom, bestFrom + s.normal * best, kind, what, s.normal)
        }
        return out
    }

    /**
     * Snapping (`rules.snap`): a side within +-5 cm of a parallel face (room slab, real furniture, another
     * piece with >= 10 cm of side-by-side overlap) is moved onto it; the nearest face per axis wins.
     * Returns the moved piece and the sides that touch.
     */
    fun snap(piece: Piece, room: RoomModel, pieces: List<Piece>): Pair<Piece, Set<Side>> {
        val y = room.floorY ?: 0f
        val me = piece.prism(y)
        var bestX: Pair<Float, Side>? = null
        var bestZ: Pair<Float, Side>? = null
        val faces = ArrayList<Pair<SideLine, Float>>()
        for (o in room.obstacles) if (me.overlapsHeight(o.prism)) o.prism.sides.forEach { faces += it to SNAP_WALL_OVERLAP_M }
        for (p in pieces) if (p.id != piece.id) p.prism(y).sides.forEach { faces += it to SNAP_PIECE_OVERLAP_M }
        for (s in me.sides) {
            for ((f, need) in faces) {
                if (s.normal.dot(f.normal) > -snapCos) continue
                val d = (f.mid - s.mid).dot(s.normal)
                if (d < -SNAP_M || d > SNAP_M) continue
                val a0 = -s.half
                val a1 = s.half
                val fc = (f.mid - s.mid).dot(s.along)
                val fh = f.half * abs(f.along.dot(s.along))
                val overlap = min(a1, fc + fh) - max(a0, fc - fh)
                if (overlap < need) continue
                val alongZ = s.side == Side.FRONT || s.side == Side.BACK
                val cur = if (alongZ) bestZ else bestX
                if (cur == null || abs(d) < abs(cur.first)) {
                    if (alongZ) bestZ = d to s.side else bestX = d to s.side
                }
            }
        }
        var c = piece.center
        val touching = HashSet<Side>()
        bestZ?.let { (d, side) ->
            c = c + me.side(side).normal * d
            touching += side
        }
        bestX?.let { (d, side) ->
            c = c + me.side(side).normal * d
            touching += side
        }
        return piece.copy(center = c) to touching
    }

    /**
     * After a turn or a new size (`rules.rotate`, `rules.sizing`): out of every room slab it went into,
     * along that slab's room-side normal, by at most one depth of the piece.
     */
    fun pushOut(piece: Piece, room: RoomModel): Piece {
        val y = room.floorY ?: 0f
        var p = piece
        val limit = max(piece.dims.w, piece.dims.d)
        var moved = 0f
        for (o in room.walls) {
            val inward = o.inward ?: continue
            val me = p.prism(y)
            if (!me.overlapsHeight(o.prism, COLLIDE_DEPTH_M) || Geo2.penetration(me, o.prism) <= COLLIDE_DEPTH_M) continue
            val face = o.prism.center.dot(inward) + o.prism.hz
            val (p0, _) = Geo2.project(me, inward)
            val push = (face - p0).coerceIn(0f, limit - moved)
            if (push <= 0f) continue
            moved += push
            p = p.copy(center = p.center + inward * push)
        }
        return p
    }

    /**
     * Where a new piece lands (`rules.place`): 1.4 m ahead of the head's foot along the level view, facing
     * the viewer on a room axis; nearer when a wall is closer (wall + half a depth + 5 cm).
     */
    fun placement(template: Template, dims: Dims, head: HeadPose, room: RoomModel, id: Int): Piece {
        val foot = P2.of(head.position)
        val dir = head.level()
        var ahead = PLACE_AHEAD_M
        for (w in room.walls) {
            val t = Geo2.ray(foot, dir, w.prism) ?: continue
            if (t > 0.05f) ahead = min(ahead, t - dims.d / 2f - PLACE_WALL_CLEAR_M)
        }
        ahead = max(ahead, 0.3f)
        val center = foot + dir * ahead
        val yaw = room.snapYaw(yawOf(dir * -1f))
        return Piece(id, template.id, dims, center, yaw)
    }

    /** Turn 90 degrees clockwise (seen from above) about the footprint centre, then out of the walls. */
    fun rotate(piece: Piece, room: RoomModel): Piece {
        val turned = piece.copy(yaw = wrapAngle(piece.yaw - (Math.PI / 2).toFloat()))
        return pushOut(turned, room)
    }

    /** The head's foot within 15 cm of the footprint: the piece is drawn see-through. */
    fun inside(piece: Piece, head: Vec3): Boolean = piece.prism(0f).contains(P2.of(head), INSIDE_MARGIN_M)

    fun validSizeCm(cm: Int): Boolean = cm in MIN_SIZE_CM..MAX_SIZE_CM
}
