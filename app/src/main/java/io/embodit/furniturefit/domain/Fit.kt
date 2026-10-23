package io.embodit.furniturefit.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign

enum class Verdict { FITS, DOOR, COLLIDES }

enum class GapKind { CONTACT, TIGHT, PASSABLE }

/** The clearance in front of one side of a piece: from [from] (on the side) to [to] (on the obstacle). */
data class Gap(val side: Side, val distance: Float, val from: P2, val to: P2, val kind: GapKind, val what: String, val normal: P2 = P2.ZERO)

/** A piece after snapping: where it ended, the sides that touch a face, the real-furniture outline it sits on. */
data class Settled(val piece: Piece, val sides: Set<Side>, val outline: String?)

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
    const val OUTLINE_SNAP_M = 0.20f
    const val OUTLINE_SNAP_DEG = 12f
    const val ON_OUTLINE_M = 0.01f
    const val ON_OUTLINE_DEG = 1f
    const val OUTLINE_NEAR_M = 0.6f
    const val STICK_DEADZONE = 0.15f
    const val STICK_MAX_DEG_S = 90f

    private val snapCos = cos(Math.toRadians(SNAP_PARALLEL_DEG.toDouble())).toFloat()

    fun check(piece: Piece, room: RoomModel, pieces: List<Piece>): FitResult {
        val y = room.floorY ?: 0f
        val me = piece.prism(y)
        val hitPieces = HashSet<Int>()
        val hitReal = HashSet<String>()
        val hitWalls = HashSet<String>()
        for (o in room.obstacles) {
            if (o.kind == ObstacleKind.REAL && onOutline(piece, o.prism)) continue
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
        for (o in room.obstacles) if (me.overlapsHeight(o.prism) && !(o.kind == ObstacleKind.REAL && onOutline(piece, o.prism))) out += "${o.kind.name.lowercase()}:${o.id}" to o.prism
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
        for (o in room.obstacles) if (me.overlapsHeight(o.prism) && !(o.kind == ObstacleKind.REAL && onOutline(piece, o.prism))) o.prism.sides.forEach { faces += it to SNAP_WALL_OVERLAP_M }
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

    /**
     * The heading of [outline]'s sides (its yaw + k * 90 degrees) nearest to [yaw], and how far [yaw] is
     * from it (radians).
     */
    private fun alignedYaw(yaw: Float, outline: Prism): Pair<Float, Float> {
        val quarter = (Math.PI / 2).toFloat()
        val k = round(wrapAngle(yaw - outline.yaw) / quarter)
        val target = wrapAngle(outline.yaw + k * quarter)
        return target to abs(wrapAngle(yaw - target))
    }

    private fun rad(deg: Float): Float = Math.toRadians(deg.toDouble()).toFloat()

    /** [piece] stands exactly on [outline] (centre and heading): it stands in for that real furniture. */
    fun onOutline(piece: Piece, outline: Prism): Boolean =
        (piece.center - outline.center).length <= ON_OUTLINE_M && alignedYaw(piece.yaw, outline).second <= rad(ON_OUTLINE_DEG)

    /** The real furniture whose outline [piece] stands on, if any. */
    fun outlineOf(piece: Piece, room: RoomModel): String? = room.real.firstOrNull { onOutline(piece, it.prism) }?.id

    /**
     * Outline snap (device feedback, v0.1.1): a piece moved or turned with its centre within 20 cm of a
     * real-furniture outline's centre and its heading within 12 degrees of one of the outline's sides
     * jumps onto it, centre and heading. Standing on the outline it replaces that furniture: the two
     * do not collide. The nearest outline wins.
     */
    fun outlineSnap(piece: Piece, room: RoomModel): Pair<Piece, String>? {
        var best: Triple<Float, Piece, String>? = null
        for (o in room.real) {
            val d = (piece.center - o.prism.center).length
            if (d > OUTLINE_SNAP_M) continue
            val (yaw, off) = alignedYaw(piece.yaw, o.prism)
            if (off > rad(OUTLINE_SNAP_DEG)) continue
            val score = d / OUTLINE_SNAP_M + off / rad(OUTLINE_SNAP_DEG)
            if (best == null || score < best.first) best = Triple(score, piece.copy(center = o.prism.center, yaw = yaw), o.id)
        }
        return best?.let { it.second to it.third }
    }

    /** Snapping after a move, a turn or a new size: onto a near real-furniture outline, else against faces ([snap]). */
    fun settle(piece: Piece, room: RoomModel, pieces: List<Piece>): Settled {
        outlineSnap(piece, room)?.let { (p, id) -> return Settled(p, emptySet(), id) }
        val (p, sides) = snap(piece, room, pieces)
        return Settled(p, sides, null)
    }

    /** Real furniture whose outline is worth showing as a snap target for [piece]: within 60 cm of its footprint. */
    fun outlinesNear(piece: Piece, room: RoomModel): List<Obstacle> {
        val me = piece.prism(room.floorY ?: 0f)
        return room.real.filter { o -> (piece.center - o.prism.center).length <= hypot(me.hx, me.hz) + hypot(o.prism.hx, o.prism.hz) + OUTLINE_NEAR_M }
    }

    /**
     * Thumbstick X (-1..1, right +) -> turn rate in degrees per second: nothing inside the 0.15 dead
     * zone, then up to 90 deg/s on a square curve (fine control near the centre). Right turns the piece
     * clockwise seen from above (yaw down, as [rotate]).
     */
    fun stickRateDeg(x: Float): Float {
        val a = abs(x)
        if (!a.isFinite() || a <= STICK_DEADZONE) return 0f
        val t = ((a - STICK_DEADZONE) / (1f - STICK_DEADZONE)).coerceIn(0f, 1f)
        return -sign(x) * STICK_MAX_DEG_S * t * t
    }

    /** [piece] turned by [deg] about its own vertical axis (the footprint centre). */
    fun turn(piece: Piece, deg: Float): Piece = piece.copy(yaw = wrapAngle(piece.yaw + rad(deg)))

    /** The head's foot within 15 cm of the footprint: the piece is drawn see-through. */
    fun inside(piece: Piece, head: Vec3): Boolean = piece.prism(0f).contains(P2.of(head), INSIDE_MARGIN_M)

    fun validSizeCm(cm: Int): Boolean = cm in MIN_SIZE_CM..MAX_SIZE_CM
}
