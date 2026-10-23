package io.embodit.furniturefit.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Two viewer-facing panels as the eye sees them: angular boxes (yaw, elevation, half extents, degrees).
 * Used to keep the library out of a dialog's way (QA #2 q2-1 / q2-2: the dialog at 0.9 m sat right
 * behind the library at 0.6 m). Yaw follows [yawOf]: a larger yaw is further left.
 */
object PanelDodge {
    data class View(val yawDeg: Float, val elevDeg: Float, val halfWDeg: Float, val halfHDeg: Float, val dist: Float)

    const val MARGIN_DEG = 3f

    private fun deg(r: Float): Float = Math.toDegrees(r.toDouble()).toFloat()

    private fun rad(d: Float): Float = Math.toRadians(d.toDouble()).toFloat()

    private fun wrapDeg(d: Float): Float = deg(wrapAngle(rad(d)))

    /** [center] of a [w] x [h] m panel that faces [eye]. */
    fun view(eye: Vec3, center: Vec3, w: Float, h: Float): View {
        val d = center - eye
        val dist = d.length.coerceAtLeast(1e-3f)
        return View(deg(yawOf(P2(d.x, d.z))), deg(atan2(d.y, hypot(d.x, d.z))), deg(atan2(w / 2f, dist)), deg(atan2(h / 2f, dist)), dist)
    }

    /** One hides part of the other (within [margin] degrees). */
    fun overlaps(a: View, b: View, margin: Float = MARGIN_DEG): Boolean =
        abs(wrapDeg(a.yawDeg - b.yawDeg)) < a.halfWDeg + b.halfWDeg + margin && abs(a.elevDeg - b.elevDeg) < a.halfHDeg + b.halfHDeg + margin

    /**
     * Where [moving] goes to clear [fixed]: beside it at its height and at its own distance, on the side it
     * is already on (left when centred, the library's home side).
     */
    fun aside(eye: Vec3, fixed: View, moving: View, margin: Float = MARGIN_DEG): Vec3 {
        val side = if (wrapDeg(moving.yawDeg - fixed.yawDeg) >= -0.5f) 1f else -1f
        val yaw = rad(fixed.yawDeg + side * (fixed.halfWDeg + moving.halfWDeg + margin))
        val elev = rad(fixed.elevDeg)
        return eye + P2.dir(yaw).at(0f) * (moving.dist * cos(elev)) + Vec3(0f, moving.dist * sin(elev), 0f)
    }
}
