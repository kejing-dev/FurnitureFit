package io.embodit.furniturefit.domain

import kotlin.math.max

enum class Shape { SOFA, BED, TABLE, DESK, BOOKCASE, WARDROBE, TV_STAND, BOX }

/** One of the eleven tiles of the library (design-package `templates`). Sizes in centimetres. */
data class Template(val id: String, val zh: String, val en: String, val wCm: Int, val dCm: Int, val hCm: Int, val shape: Shape) {
    val dims: Dims
        get() = Dims(wCm / 100f, dCm / 100f, hCm / 100f)
}

/** Width (local x), depth (local z, the front is +z) and height in metres. */
data class Dims(val w: Float, val d: Float, val h: Float)

object Templates {
    val all =
        listOf(
            Template("sofa3", "三人沙发", "3-seat sofa", 210, 90, 85, Shape.SOFA),
            Template("sofa2", "双人沙发", "Loveseat", 160, 85, 85, Shape.SOFA),
            Template("armchair", "单人椅", "Armchair", 80, 80, 85, Shape.SOFA),
            Template("bedDouble", "双人床", "Double bed", 150, 200, 90, Shape.BED),
            Template("bedSingle", "单人床", "Single bed", 90, 200, 90, Shape.BED),
            Template("dining", "餐桌", "Dining table", 140, 80, 75, Shape.TABLE),
            Template("desk", "书桌", "Desk", 120, 60, 75, Shape.DESK),
            Template("bookcase", "书柜", "Bookcase", 80, 30, 180, Shape.BOOKCASE),
            Template("wardrobe", "衣柜", "Wardrobe", 120, 60, 200, Shape.WARDROBE),
            Template("tvStand", "电视柜", "TV stand", 180, 40, 50, Shape.TV_STAND),
            Template("custom", "自定义", "Custom", 100, 50, 50, Shape.BOX),
        )

    fun byId(id: String): Template = all.firstOrNull { it.id == id } ?: all.last()

    const val CUSTOM = "custom"
    const val MAX_PIECES = 12
}

/** A placed piece: its footprint centre on the floor and yaw in Stage space, and its size. */
data class Piece(val id: Int, val templateId: String, val dims: Dims, val center: P2, val yaw: Float) {
    fun prism(floorY: Float): Prism = Prism(center, dims.w / 2f, dims.d / 2f, yaw, floorY, floorY + dims.h)
}

enum class Finish { FABRIC, WOOD }

/** One box of a piece's look, in the piece's frame: [center] (y from the floor), [size], its finish. */
data class SkinBox(val center: Vec3, val size: Vec3, val finish: Finish)

/**
 * The basic-shape looks (design-package `primitiveRecipes`): every template is 1 - 12 boxes laid out
 * by the piece's W x D x H; fixed centimetre sizes stay fixed; nothing is smaller than 2 cm. The front
 * of the piece is +z.
 */
object Recipes {
    private const val MIN = 0.02f

    fun boxes(shape: Shape, d: Dims): List<SkinBox> {
        val out = ArrayList<SkinBox>()
        val w = d.w
        val dp = d.d
        val h = d.h

        fun box(x: Float, y0: Float, z: Float, sx: Float, sy: Float, sz: Float, f: Finish) {
            val ex = max(sx, MIN)
            val ey = max(sy, MIN)
            val ez = max(sz, MIN)
            out += SkinBox(Vec3(x, y0 + ey / 2f, z), Vec3(ex, ey, ez), f)
        }

        when (shape) {
            Shape.SOFA -> {
                val leg = 0.06f
                val seatH = 0.5f * h - leg
                box(0f, leg, 0f, w, seatH, dp, Finish.FABRIC)
                val backD = 0.22f * dp
                box(0f, leg, -dp / 2f + backD / 2f, w, h - leg, backD, Finish.FABRIC)
                val arm = 0.15f.coerceAtMost(w / 3f)
                val armH = 0.68f * h - leg
                box(-w / 2f + arm / 2f, leg, 0f, arm, armH, dp, Finish.FABRIC)
                box(w / 2f - arm / 2f, leg, 0f, arm, armH, dp, Finish.FABRIC)
                val lx = w / 2f - 0.04f
                val lz = dp / 2f - 0.04f
                for (sx in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f)) box(sx * lx, 0f, sz * lz, 0.04f, leg, 0.04f, Finish.WOOD)
            }
            Shape.BED -> {
                box(0f, 0f, 0f, w, 0.3f * h, dp, Finish.WOOD)
                box(0f, 0.3f * h, 0.03f, w - 0.04f, 0.25f * h, dp - 0.04f - 0.06f, Finish.FABRIC)
                box(0f, 0f, -dp / 2f + 0.03f, w, h, 0.06f, Finish.WOOD)
            }
            Shape.TABLE, Shape.DESK -> {
                val top = 0.04f
                box(0f, h - top, 0f, w, top, dp, Finish.WOOD)
                val lx = w / 2f - 0.055f
                val lz = dp / 2f - 0.055f
                for (sx in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f)) box(sx * lx, 0f, sz * lz, 0.05f, h - top, 0.05f, Finish.WOOD)
                if (shape == Shape.DESK) {
                    val dw = 0.35f * w
                    val dh = 0.55f * h
                    box(w / 2f - 0.08f - dw / 2f, h - top - dh, 0f, dw, dh, dp - 0.06f, Finish.FABRIC)
                }
            }
            Shape.BOOKCASE -> {
                val t = 0.02f
                box(-w / 2f + t / 2f, 0f, 0f, t, h, dp, Finish.WOOD)
                box(w / 2f - t / 2f, 0f, 0f, t, h, dp, Finish.WOOD)
                box(0f, h - t, 0f, w, t, dp, Finish.WOOD)
                box(0f, 0f, 0f, w, t, dp, Finish.WOOD)
                box(0f, 0f, -dp / 2f + t / 2f, w, h, t, Finish.WOOD)
                for (i in 1..3) box(0f, h * i / 4f - t / 2f, t / 2f, w - 2 * t, t, dp - t, Finish.WOOD)
            }
            Shape.WARDROBE -> {
                box(0f, 0f, -0.02f, w, h, dp - 0.04f, Finish.WOOD)
                val doorW = 0.5f * w - 0.01f
                for (sx in listOf(-1f, 1f)) {
                    box(sx * (0.01f + doorW / 2f), 0.02f, dp / 2f - 0.03f, doorW, h - 0.04f, 0.02f, Finish.FABRIC)
                    box(sx * 0.04f, h / 2f - 0.1f, dp / 2f - 0.01f, 0.02f, 0.2f, 0.02f, Finish.WOOD)
                }
            }
            Shape.TV_STAND -> {
                box(0f, 0f, 0f, w, 0.9f * h, dp, Finish.WOOD)
                box(0f, 0.9f * h, 0f, w, 0.1f * h, dp, Finish.WOOD)
                for (sx in listOf(-1f, 1f)) box(sx * w / 4f, 0.45f * h, dp / 2f - 0.011f, 0.4f * w, 0.01f, 0.02f, Finish.FABRIC)
            }
            Shape.BOX -> box(0f, 0f, 0f, w, h, dp, Finish.FABRIC)
        }
        return out
    }

    const val MAX_BOXES = 12
}
