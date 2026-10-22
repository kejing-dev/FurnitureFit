package io.embodit.furniturefit.domain

import org.json.JSONArray
import org.json.JSONObject

/** Pose of a world anchor in Stage space. */
data class AnchorPose(val position: Vec3, val rotation: Quat4) {
    private val yaw: Float
        get() = yawOf(P2.of(rotation.rotate(Vec3(0f, 0f, 1f))))

    /** Stage -> anchor frame for a piece (footprint centre and yaw). */
    fun toLocal(center: P2, yaw: Float, floorY: Float): Pair<Vec3, Float> {
        val local = rotation.conjugate().rotate(center.at(floorY) - position)
        return local to wrapAngle(yaw - this.yaw)
    }

    /** Anchor frame -> Stage for a piece. */
    fun toStage(local: Vec3, localYaw: Float): Pair<P2, Float> {
        val p = rotation.rotate(local) + position
        return P2.of(p) to wrapAngle(localYaw + yaw)
    }
}

/** A piece as saved: relative to the room anchor. */
data class SavedPiece(val id: Int, val templateId: String, val dims: Dims, val local: Vec3, val yaw: Float)

/**
 * The saved layout (`persistence`): one room anchor (created where the first piece landed) and every
 * piece's pose relative to it; the room axis is saved in the anchor frame as well.
 */
data class SavedLayout(val anchorId: String, val axisYaw: Float?, val pieces: List<SavedPiece>, val nextId: Int)

object LayoutCodec {
    fun encode(l: SavedLayout): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("anchor", l.anchorId)
        l.axisYaw?.let { o.put("axis", it.toDouble()) }
        o.put("next", l.nextId)
        val arr = JSONArray()
        for (p in l.pieces) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("t", p.templateId)
                    .put("w", p.dims.w.toDouble())
                    .put("d", p.dims.d.toDouble())
                    .put("h", p.dims.h.toDouble())
                    .put("x", p.local.x.toDouble())
                    .put("y", p.local.y.toDouble())
                    .put("z", p.local.z.toDouble())
                    .put("yaw", p.yaw.toDouble())
            )
        }
        o.put("pieces", arr)
        return o.toString()
    }

    fun decode(s: String?): SavedLayout? {
        if (s.isNullOrBlank()) return null
        return runCatching {
                val o = JSONObject(s)
                val arr = o.getJSONArray("pieces")
                val pieces =
                    (0 until arr.length()).map { i ->
                        val p = arr.getJSONObject(i)
                        SavedPiece(
                            p.getInt("id"),
                            p.getString("t"),
                            Dims(p.getDouble("w").toFloat(), p.getDouble("d").toFloat(), p.getDouble("h").toFloat()),
                            Vec3(p.getDouble("x").toFloat(), p.getDouble("y").toFloat(), p.getDouble("z").toFloat()),
                            p.getDouble("yaw").toFloat(),
                        )
                    }
                SavedLayout(o.getString("anchor"), if (o.has("axis")) o.getDouble("axis").toFloat() else null, pieces, o.optInt("next", pieces.maxOfOrNull { it.id + 1 } ?: 1))
            }
            .getOrNull()
    }
}
