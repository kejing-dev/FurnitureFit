package io.embodit.furniturefit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 4 x 4 m room: floor at y = 0 from x -2..2, z -4..0, walls on all four sides (the back wall at
 * z = -4 is a WALL plane facing outwards, as on the emulator), a door in the right wall.
 */
class FitRulesTest {
    private fun quad(sem: String, vararg v: Vec3) = PlaneSurface("$sem-${v[0]}", sem, v.toList())

    private val floor = quad("FLOOR", Vec3(-2f, 0f, -4f), Vec3(2f, 0f, -4f), Vec3(2f, 0f, 0f), Vec3(-2f, 0f, 0f))
    private val back = quad("WALL", Vec3(-2f, 0f, -4f), Vec3(2f, 0f, -4f), Vec3(2f, 2.5f, -4f), Vec3(-2f, 2.5f, -4f))
    private val left = quad("WALL", Vec3(-2f, 0f, 0f), Vec3(-2f, 0f, -4f), Vec3(-2f, 2.5f, -4f), Vec3(-2f, 2.5f, 0f))
    private val right = quad("WALL", Vec3(2f, 0f, -4f), Vec3(2f, 0f, 0f), Vec3(2f, 2.5f, 0f), Vec3(2f, 2.5f, -4f))
    private val door = quad("DOOR", Vec3(2f, 0f, -1.5f), Vec3(2f, 0f, -0.6f), Vec3(2f, 2.1f, -0.6f), Vec3(2f, 2.1f, -1.5f))
    private val room = RoomModel.build(listOf(floor, back, left, right, door), emptyList(), Vec3(0f, 1.6f, -1f))
    private val sofa = Templates.byId("sofa3")

    private fun piece(x: Float, z: Float, yaw: Float = 0f, t: Template = sofa, id: Int = 1) = Piece(id, t.id, t.dims, P2(x, z), yaw)

    @Test
    fun roomHasFloorWallsDoorAndAxis() {
        assertEquals(0f, room.floorY!!, 1e-4f)
        assertEquals(4, room.walls.size)
        assertEquals(1, room.doorZones.size)
        // inward of the back wall is +z (towards the room), its yaw 0
        assertNotNull(room.axisYaw)
        val zone = room.doorZones.first().prism
        assertEquals(2f - 0.4f, zone.center.x, 1e-3f)
        assertEquals(0.9f, zone.hx * 2f, 1e-3f)
    }

    @Test
    fun freePieceFits() {
        val r = FitRules.check(piece(0f, -2f), room, emptyList())
        assertEquals(Verdict.FITS, r.verdict)
    }

    @Test
    fun pieceIntoBackWallCollides() {
        // sofa 90 deep: centre 0.35 from the wall puts 10 cm of it into the wall
        val r = FitRules.check(piece(0f, -3.65f), room, emptyList())
        assertEquals(Verdict.COLLIDES, r.verdict)
        assertEquals(1, r.hitWalls.size)
    }

    @Test
    fun twoPiecesOverlappingBothCollide() {
        val a = piece(0f, -2f, id = 1)
        val b = piece(0.5f, -2.3f, id = 2)
        val all = FitRules.checkAll(room, listOf(a, b))
        assertEquals(Verdict.COLLIDES, all[1]!!.verdict)
        assertEquals(setOf(2), all[1]!!.hitPieces)
        assertEquals(setOf(1), all[2]!!.hitPieces)
    }

    @Test
    fun outOfTheRoomCollides() {
        val r = FitRules.check(piece(0f, 1.0f, t = Templates.byId("armchair")), room, emptyList())
        assertTrue(r.outOfRoom)
        assertEquals(Verdict.COLLIDES, r.verdict)
    }

    @Test
    fun pieceInFrontOfDoorBlocksIt() {
        val chair = Templates.byId("armchair")
        val r = FitRules.check(piece(1.4f, -1.05f, t = chair), room, emptyList())
        assertEquals(Verdict.DOOR, r.verdict)
        assertEquals(1, r.doors.size)
    }

    @Test
    fun snapPullsBackAgainstWall() {
        // back side 3 cm off the wall face (z = -4): pulled onto it
        val p = piece(0f, -4f + 0.45f + 0.03f)
        val (s, sides) = FitRules.snap(p, room, emptyList())
        assertEquals(-4f + 0.45f, s.center.z, 1e-3f)
        assertTrue(Side.BACK in sides)
    }

    @Test
    fun snapPushesOutOfWallWithinFiveCm() {
        val p = piece(0f, -4f + 0.45f - 0.04f)
        val (s, _) = FitRules.snap(p, room, emptyList())
        assertEquals(-4f + 0.45f, s.center.z, 1e-3f)
    }

    @Test
    fun noSnapBeyondFiveCm() {
        val p = piece(0f, -4f + 0.45f + 0.08f)
        val (s, sides) = FitRules.snap(p, room, emptyList())
        assertEquals(p.center.z, s.center.z, 1e-5f)
        assertTrue(sides.isEmpty())
    }

    @Test
    fun snapToAnotherPieceNeedsOverlap() {
        val a = piece(0f, -2f, id = 1)
        // side by side, 3 cm apart, fully overlapping along z: snaps
        val b = piece(2.1f + 0.03f, -2f, t = sofa, id = 2).copy(center = P2(0f + 2.1f + 0.03f - 0.0f, -2f))
        val (s, sides) = FitRules.snap(b, room, listOf(a, b))
        assertTrue(sides.isNotEmpty())
        assertEquals(2.1f, s.center.x, 1e-3f)
    }

    @Test
    fun gapsScanTheWholeSide() {
        val p = piece(0f, -2f)
        val gaps = FitRules.gaps(p, room, emptyList()).associateBy { it.side }
        // back: wall at z = -4, sofa back at -2.45 -> 1.55 m passable
        assertEquals(1.55f, gaps[Side.BACK]!!.distance, 0.01f)
        assertEquals(GapKind.PASSABLE, gaps[Side.BACK]!!.kind)
        // left / right: walls at x = -+2, sofa half 1.05 -> 0.95 m
        assertEquals(0.95f, gaps[Side.LEFT]!!.distance, 0.01f)
        // front: open room (no wall within 2 m in front at z = 0 is 1.55 away -> there is none: z = 0 has no wall)
        assertNull(gaps[Side.FRONT])
    }

    @Test
    fun tightGapAndContact() {
        val p = piece(0f, -4f + 0.45f + 0.3f)
        val back = FitRules.gaps(p, room, emptyList()).first { it.side == Side.BACK }
        assertEquals(GapKind.TIGHT, back.kind)
        val flush = piece(0f, -4f + 0.45f)
        val c = FitRules.gaps(flush, room, emptyList()).first { it.side == Side.BACK }
        assertEquals(GapKind.CONTACT, c.kind)
    }

    @Test
    fun rotateTurnsClockwiseAndPushesOutOfWall() {
        // a 2.1 m sofa turned near the left wall would go 0.6 m into it: pushed back out
        val p = piece(-2f + 0.5f, -2f)
        val r = FitRules.rotate(p, room)
        assertEquals(wrapAngle(-(Math.PI / 2).toFloat()), r.yaw, 1e-4f)
        val check = FitRules.check(r, room, emptyList())
        assertTrue(check.hitWalls.isEmpty())
    }

    @Test
    fun placementLandsAheadFacingTheViewer() {
        val head = HeadPose(Vec3(0f, 1.6f, -0.5f), Quat4.IDENTITY)
        val p = FitRules.placement(sofa, sofa.dims, head, room, 7)
        assertEquals(-0.5f - 1.4f, p.center.z, 1e-3f)
        // front (+z local) towards the viewer
        assertTrue(P2.dir(p.yaw).z > 0.9f)
    }

    @Test
    fun placementStopsShortOfANearWall() {
        val head = HeadPose(Vec3(0f, 1.6f, -3f), Quat4.IDENTITY)
        val p = FitRules.placement(sofa, sofa.dims, head, room, 7)
        // wall at -4: centre at -4 + 0.45 + 0.05
        assertEquals(-4f + 0.5f, p.center.z, 1e-3f)
    }

    @Test
    fun insideNearTheFootprint() {
        val p = piece(0f, -2f)
        assertTrue(FitRules.inside(p, Vec3(0.5f, 1.6f, -2f)))
        assertTrue(FitRules.inside(p, Vec3(1.15f, 1.6f, -2f)))
        assertFalse(FitRules.inside(p, Vec3(1.3f, 1.6f, -2f)))
    }

    @Test
    fun meshClustersBecomeRealFurniture() {
        val pts = ArrayList<MeshPoint>()
        // a 1.0 x 0.6 table top at 0.75 m, sampled every 5 cm, and a lone noise vertex
        var x = -0.5f
        while (x <= 0.5f) {
            var z = -2.3f
            while (z <= -1.7f) {
                pts += MeshPoint(Vec3(x, 0.75f, z), "TABLE")
                z += 0.05f
            }
            x += 0.05f
        }
        pts += MeshPoint(Vec3(1.5f, 0.05f, -1f), "TABLE")
        val r = RoomModel.build(listOf(floor, back, left, right), pts, null)
        assertEquals(1, r.real.size)
        val t = r.real.first().prism
        assertEquals(0.75f, t.y1, 1e-3f)
        assertEquals(1.0f, maxOf(t.hx, t.hz) * 2f, 0.06f)
        val hit = FitRules.check(piece(0f, -2f, t = Templates.byId("armchair")), r, emptyList())
        assertEquals(setOf(r.real.first().id), hit.hitReal)
    }

    @Test
    fun recipesStayWithinTheSizeBoxAndBudget() {
        for (t in Templates.all) {
            val boxes = Recipes.boxes(t.shape, t.dims)
            assertTrue(t.id, boxes.size in 1..Recipes.MAX_BOXES)
            for (b in boxes) {
                assertTrue("${t.id} x", kotlin.math.abs(b.center.x) + b.size.x / 2f <= t.dims.w / 2f + 1e-3f)
                assertTrue("${t.id} z", kotlin.math.abs(b.center.z) + b.size.z / 2f <= t.dims.d / 2f + 1e-3f)
                assertTrue("${t.id} y", b.center.y + b.size.y / 2f <= t.dims.h + 1e-3f && b.center.y - b.size.y / 2f >= -1e-3f)
            }
        }
    }

    @Test
    fun layoutRoundTripsThroughAnAnchor() {
        val anchor = AnchorPose(Vec3(1f, 0f, -2f), Quat4.axisAngle(Vec3.UP, 0.4f))
        val (local, yaw) = anchor.toLocal(P2(0.3f, -1.2f), 0.7f, 0f)
        val layout = SavedLayout("abc", 0.1f, listOf(SavedPiece(3, "desk", Dims(1.2f, 0.6f, 0.75f), local, yaw)), 4)
        val back = LayoutCodec.decode(LayoutCodec.encode(layout))!!
        val (c, y) = anchor.toStage(back.pieces[0].local, back.pieces[0].yaw)
        assertEquals(0.3f, c.x, 1e-4f)
        assertEquals(-1.2f, c.z, 1e-4f)
        assertEquals(0.7f, y, 1e-4f)
        assertEquals(4, back.nextId)
    }

    @Test
    fun unitsReadWholeCentimetresOrInches() {
        assertEquals("92 cm", Units.reading(0.9249f, LengthUnit.CM))
        assertEquals("36 in", Units.reading(0.92f, LengthUnit.IN))
        assertEquals("210×90×85", Units.dims(sofa.dims, LengthUnit.CM))
    }
}
