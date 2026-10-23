package io.embodit.furniturefit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * QA #2 q2-1 / q2-2: the dialog (0.32 x 0.21 m at 0.9 m, straight ahead) and the library (0.24 x 0.17 m
 * at 0.6 m). Eye at the origin looking -Z (yaw 180 in [yawOf]); left is +yaw.
 */
class PanelDodgeTest {
    private val eye = Vec3(0f, 1.6f, 0f)
    private val dialogW = 0.32f
    private val dialogH = 0.2144f
    private val libW = 0.24f
    private val libH = 0.1728f

    /** A point [dist] away, [leftDeg] left of straight ahead and [upDeg] above level. */
    private fun at(leftDeg: Float, upDeg: Float, dist: Float): Vec3 {
        val yaw = Math.toRadians((180f + leftDeg).toDouble()).toFloat()
        val e = Math.toRadians(upDeg.toDouble()).toFloat()
        return eye + P2.dir(yaw).at(0f) * (dist * cos(e)) + Vec3(0f, dist * sin(e), 0f)
    }

    private fun dialog(upDeg: Float = 0f) = PanelDodge.view(eye, at(0f, upDeg, 0.9f), dialogW, dialogH)

    private fun library(leftDeg: Float, upDeg: Float) = PanelDodge.view(eye, at(leftDeg, upDeg, 0.6f), libW, libH)

    @Test
    fun libraryInFrontHidesTheDialogAndGoesBesideIt() {
        val d = dialog()
        val l = library(0f, 0f)
        assertTrue(PanelDodge.overlaps(d, l))
        val to = PanelDodge.aside(eye, d, l)
        val moved = PanelDodge.view(eye, to, libW, libH)
        assertFalse(PanelDodge.overlaps(d, moved, 0f))
        assertEquals(0.6f, moved.dist, 1e-3f)
        assertEquals(d.elevDeg, moved.elevDeg, 0.1f)
        // centred: it goes to the left (its home side)
        assertTrue(wrapAngle(Math.toRadians((moved.yawDeg - d.yawDeg).toDouble()).toFloat()) > 0f)
    }

    @Test
    fun libraryOnTheRightStaysOnTheRight() {
        val d = dialog()
        val l = library(-8f, -4f)
        assertTrue(PanelDodge.overlaps(d, l))
        val moved = PanelDodge.view(eye, PanelDodge.aside(eye, d, l), libW, libH)
        assertFalse(PanelDodge.overlaps(d, moved, 0f))
        assertTrue(wrapAngle(Math.toRadians((moved.yawDeg - d.yawDeg).toDouble()).toFloat()) < 0f)
    }

    @Test
    fun libraryAtItsRestPoseLookingDown23HidesPartOfTheDialog() {
        // head 23 deg down: dialog at -23, library at its rest 18 left / 26 below level
        val d = dialog(-23f)
        val l = library(18f, -26f)
        assertTrue(PanelDodge.overlaps(d, l))
        assertFalse(PanelDodge.overlaps(d, PanelDodge.view(eye, PanelDodge.aside(eye, d, l), libW, libH), 0f))
    }

    @Test
    fun libraryFarToTheSideIsLeftAlone() {
        assertFalse(PanelDodge.overlaps(dialog(), library(35f, -26f)))
        assertFalse(PanelDodge.overlaps(dialog(), library(-35f, 0f)))
        // level gaze, library at its rest pose: 26 below, clear of a level dialog
        assertFalse(PanelDodge.overlaps(dialog(), library(18f, -26f)))
    }
}
