package io.embodit.furniturefit.domain

import io.embodit.furniturefit.ui.fit.scene.PieceLook
import io.embodit.furniturefit.ui.fit.scene.PieceTint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.1 (device feedback): thumbstick turn, snap onto a real-furniture outline, and the colour
 * priority of a piece (QA #1 q1-3). Room: the 4 x 4 m room of [FitRulesTest] with a real 1.0 x 0.6 m
 * table (spatial-mesh cluster) centred at (0, -2).
 */
class StickAndOutlineTest {
    private fun quad(sem: String, vararg v: Vec3) = PlaneSurface("$sem-${v[0]}", sem, v.toList())

    private val floor = quad("FLOOR", Vec3(-2f, 0f, -4f), Vec3(2f, 0f, -4f), Vec3(2f, 0f, 0f), Vec3(-2f, 0f, 0f))
    private val back = quad("WALL", Vec3(-2f, 0f, -4f), Vec3(2f, 0f, -4f), Vec3(2f, 2.5f, -4f), Vec3(-2f, 2.5f, -4f))
    private val left = quad("WALL", Vec3(-2f, 0f, 0f), Vec3(-2f, 0f, -4f), Vec3(-2f, 2.5f, -4f), Vec3(-2f, 2.5f, 0f))
    private val right = quad("WALL", Vec3(2f, 0f, -4f), Vec3(2f, 0f, 0f), Vec3(2f, 2.5f, 0f), Vec3(2f, 2.5f, -4f))

    private val room: RoomModel = run {
        val pts = ArrayList<MeshPoint>()
        var x = -0.5f
        while (x <= 0.5001f) {
            var z = -2.3f
            while (z <= -1.6999f) {
                pts += MeshPoint(Vec3(x, 0.75f, z), "TABLE")
                z += 0.05f
            }
            x += 0.05f
        }
        RoomModel.build(listOf(floor, back, left, right), pts, null)
    }
    private val table = room.real.single().prism
    private val desk = Templates.byId("armchair")

    private fun piece(x: Float, z: Float, yawDeg: Float, id: Int = 1) = Piece(id, desk.id, desk.dims, P2(x, z), Math.toRadians(yawDeg.toDouble()).toFloat())

    @Test
    fun stickHasADeadZoneAndTurnsClockwiseToTheRight() {
        assertEquals(0f, FitRules.stickRateDeg(0.1f), 0f)
        assertEquals(0f, FitRules.stickRateDeg(-0.15f), 0f)
        assertEquals(0f, FitRules.stickRateDeg(Float.NaN), 0f)
        assertEquals(-FitRules.STICK_MAX_DEG_S, FitRules.stickRateDeg(1f), 1e-3f)
        assertEquals(FitRules.STICK_MAX_DEG_S, FitRules.stickRateDeg(-1f), 1e-3f)
        // smooth: half way out is a quarter of the speed, and the rate never jumps at the dead zone edge
        assertEquals(-FitRules.STICK_MAX_DEG_S * 0.25f, FitRules.stickRateDeg(0.575f), 0.5f)
        assertTrue(kotlin.math.abs(FitRules.stickRateDeg(0.16f)) < 0.1f)
    }

    @Test
    fun turnIsAboutTheFootprintCentre() {
        val p = piece(0.3f, -1f, 0f)
        val t = FitRules.turn(p, -30f)
        assertEquals(p.center, t.center)
        assertEquals(-30.0, Math.toDegrees(t.yaw.toDouble()), 1e-3)
        assertEquals(180.0, kotlin.math.abs(Math.toDegrees(FitRules.turn(piece(0f, 0f, 170f), 20f).yaw.toDouble())) + 10.0, 1e-3)
    }

    @Test
    fun nearOutlineSnapsCentreAndHeading() {
        val near = piece(table.center.x + 0.12f, table.center.z - 0.08f, Math.toDegrees(table.yaw.toDouble()).toFloat() + 8f)
        val (snapped, id) = FitRules.outlineSnap(near, room)!!
        assertEquals(room.real.single().id, id)
        assertEquals(table.center.x, snapped.center.x, 1e-4f)
        assertEquals(table.center.z, snapped.center.z, 1e-4f)
        assertEquals(0f, wrapAngle(snapped.yaw - table.yaw), 1e-4f)
        assertEquals(id, FitRules.outlineOf(snapped, room))
    }

    @Test
    fun aQuarterTurnedPieceSnapsToTheOtherSide() {
        val near = piece(table.center.x, table.center.z, Math.toDegrees(table.yaw.toDouble()).toFloat() + 95f)
        val (snapped, _) = FitRules.outlineSnap(near, room)!!
        assertEquals(90.0, Math.toDegrees(wrapAngle(snapped.yaw - table.yaw).toDouble()), 1e-3)
    }

    @Test
    fun farOrSkewedPiecesDoNotSnap() {
        val deg = Math.toDegrees(table.yaw.toDouble()).toFloat()
        assertNull(FitRules.outlineSnap(piece(table.center.x + 0.25f, table.center.z, deg), room))
        assertNull(FitRules.outlineSnap(piece(table.center.x, table.center.z, deg + 30f), room))
        // settle falls back to the face snap
        val s = FitRules.settle(piece(0f, 1.0f, 0f), room, emptyList())
        assertNull(s.outline)
    }

    @Test
    fun onTheOutlineItReplacesTheRealFurniture() {
        val deg = Math.toDegrees(table.yaw.toDouble()).toFloat()
        val off = FitRules.check(piece(table.center.x + 0.1f, table.center.z, deg), room, emptyList())
        assertEquals(Verdict.COLLIDES, off.verdict)
        val settled = FitRules.settle(piece(table.center.x + 0.1f, table.center.z, deg + 5f), room, emptyList())
        assertNotNull(settled.outline)
        val on = FitRules.check(settled.piece, room, emptyList())
        assertEquals(Verdict.FITS, on.verdict)
        assertTrue(on.hitReal.isEmpty())
    }

    @Test
    fun outlinesNearShowOnlyCloseFurniture() {
        assertEquals(1, FitRules.outlinesNear(piece(0.9f, -2f, 0f), room).size)
        assertEquals(0, FitRules.outlinesNear(piece(-1.6f, -0.4f, 0f), room).size)
    }

    @Test
    fun staleWinsOverTheCollisionColour() {
        val base = PieceLook(Look.SOLID, selected = true, verdict = Verdict.COLLIDES, inside = false, stale = true, footprint = 0)
        assertEquals(PieceTint.STALE, base.tint)
        assertEquals(PieceTint.ERROR, base.copy(stale = false).tint)
        assertEquals(PieceTint.SELECTED, base.copy(stale = false, verdict = Verdict.DOOR).tint)
        assertEquals(PieceTint.NORMAL, base.copy(stale = false, verdict = Verdict.FITS, selected = false).tint)
    }
}
