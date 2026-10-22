package io.embodit.furniturefit.ui.fit.scene

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.furniturefit.domain.Quat4
import io.embodit.furniturefit.domain.Vec3
import io.embodit.furniturefit.platform.FitLog
import io.embodit.furniturefit.ui.theme.FitColors

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. The scene works in Stage metres: every entity hangs below the calibrated
// stage root, whose local space equals Stage space.
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

/** Overall opacity of an entity and its children. */
internal fun Entity.setOpacity(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f) else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

/** Metres per dp of an attachment panel at scale 1 (Spatial Ruler feasibility spike). */
internal const val DP_M = 0.0008f

/**
 * Resources shared by entities that come and go. The SDK releases a resource when its last user is
 * destroyed (Spatial Ruler: deleting one pin closed the materials of all pins), so shared ones are made
 * global and closed when the scene goes away.
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/**
 * Scene-lifetime materials, one per look, shared by every entity. Unlit colours are exact only with
 * tone mapping off (Spatial Ruler: `#FFC72C` arrived as `#D1B132` with it on). The looks of the
 * furniture are lit (physically based), so they take the room's light in the Mixed Stage.
 */
internal class FitMaterials(private val shared: SharedResources) {
    val edge: Material = unlit(FitColors.PieceEdge.copy(alpha = 1f))
    val edgeSelected: Material = unlit(FitColors.Interaction)
    val edgeError: Material = unlit(FitColors.Error)
    val edgeStale: Material = unlit(FitColors.StaleGrey)
    val fill: Material = sheer(FitColors.White, 0.14f)
    val fillError: Material = sheer(FitColors.Error, 0.35f)
    val fabric: Material = lit(FitColors.Fabric, 0.85f)
    val wood: Material = lit(FitColors.Wood, 0.6f)
    val fabricError: Material = lit(blend(FitColors.Fabric, FitColors.Error, 0.35f), 0.85f)
    val woodError: Material = lit(blend(FitColors.Wood, FitColors.Error, 0.35f), 0.6f)
    val stale: Material = unlit(FitColors.StaleGrey)
    val shadow: Material = sheer(Color.Black, 0.30f)
    val tapeBlue: Material = unlit(FitColors.MaskingBlue, twoSided = true)
    val tapeSaving: Material = unlit(FitColors.TapeYellow, twoSided = true)
    val tapeUnsaved: Material = unlit(FitColors.White, twoSided = true)
    val tapeStale: Material = unlit(FitColors.StaleGrey, twoSided = true)
    val blade: Material = unlit(FitColors.TapeYellow, twoSided = true)
    val steel: Material = unlit(FitColors.Steel, twoSided = true)
    val contact: Material = unlit(FitColors.Interaction, twoSided = true)
    val doorFill: Material = sheer(FitColors.Alert, 0.25f)
    val doorEdge: Material = unlit(FitColors.Alert, twoSided = true)
    val realEdge: Material = unlit(FitColors.Error, twoSided = true)
    val debug: Material = sheer(FitColors.Debug, 0.22f)
    val stem: Material = unlit(FitColors.TapeInk, twoSided = true)
    val meterRed: Material = unlit(FitColors.MeterRed, twoSided = true)

    /** The printed tape face: a palette texture (blade yellow, ink, red) sampled per rectangle: one mesh per segment. */
    val tapeFace: Material = paletted(listOf(FitColors.TapeYellow, FitColors.TapeInk, FitColors.MeterRed))

    private fun blend(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

    private fun unlit(color: Color, twoSided: Boolean = false): Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                runCatching { setApplyToneMapping(false) }
                if (twoSided) setCullingMode(MaterialCullingMode.NONE)
            }
        )

    /** See-through: the colour is opaque, the material's opacity carries the share (Spatial Ruler). */
    private fun sheer(color: Color, opacity: Float): Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )

    private fun lit(color: Color, roughness: Float): Material =
        shared.keep(
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                setMetallic(0f)
                setRoughness(roughness)
                // a share of its own colour keeps the look from going dull under the room light
                setEmissiveColor(Color4(color.red * 0.25f, color.green * 0.25f, color.blue * 0.25f, 1f))
            }
        )

    private fun paletted(colors: List<Color>): Material =
        try {
            val cell = 8
            val bitmap = Bitmap.createBitmap(cell * colors.size, cell, Bitmap.Config.ARGB_8888)
            for (i in colors.indices) for (x in 0 until cell) for (y in 0 until cell) bitmap.setPixel(i * cell + x, y, colors[i].toArgb())
            shared.keep(
                UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                    setBaseColor(Color4(1f, 1f, 1f, 1f))
                    setBaseColorTexture(TextureResource.create(bitmap))
                    runCatching { setApplyToneMapping(false) }
                    setCullingMode(MaterialCullingMode.NONE)
                }
            )
        } catch (t: Throwable) {
            FitLog.e("SCENE tape palette failed: $t", t)
            unlit(colors[0], twoSided = true)
        }

    companion object {
        const val PALETTE_COLUMNS = 3
    }
}

/** Programmatic meshes, built once per scene (creating a mesh costs about one rendered frame). */
internal object MeshFactory {
    private val FRONT = Vector3(0f, 0f, 1f)
    private val UP = Vector3(0f, 1f, 0f)

    /** A unit cube centred on the origin (scaled to any box). */
    fun unitCube(name: String): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        val faces =
            listOf(
                Vector3(1f, 0f, 0f), Vector3(-1f, 0f, 0f), Vector3(0f, 1f, 0f),
                Vector3(0f, -1f, 0f), Vector3(0f, 0f, 1f), Vector3(0f, 0f, -1f),
            )
        for (n in faces) {
            val nn = Vec3(n.x, n.y, n.z)
            // two in-plane axes with u x v = n, so the quad winds counter-clockwise seen from outside
            val u = (if (kotlin.math.abs(n.y) > 0.5f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)).let { it.rejectFrom(nn).normalized() }
            val v = nn.cross(u)
            val base = positions.size
            for ((a, b) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                val p = (nn + u * a + v * b) * 0.5f
                positions += Vector3(p.x, p.y, p.z)
                normals += n
            }
            indices += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.5f)), name = name)
    }

    /** A unit square on the floor (local XZ, centred, facing +Y). */
    fun unitSquare(name: String): MeshResource {
        val positions = listOf(Vector3(-0.5f, 0f, -0.5f), Vector3(0.5f, 0f, -0.5f), Vector3(0.5f, 0f, 0.5f), Vector3(-0.5f, 0f, 0.5f))
        val model = MeshModel(positions = positions, triangleIndices = listOf(0, 2, 1, 0, 3, 2), normals = List(4) { UP })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.001f, 0.5f)), name = name)
    }

    /** Strip x 0..1, y -0.5..0.5 facing +Z, cut into [dashes] (0 = solid): scaled to any length and width. */
    fun unitBand(name: String, dashes: Int = 0, fill: Float = 0.55f): MeshResource {
        val rects = if (dashes <= 0) listOf(0f to 1f) else List(dashes) { i -> val p = 1f / dashes; (i * p + p * (1f - fill) / 2f) to (i * p + p * (1f + fill) / 2f) }
        val positions = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for ((x0, x1) in rects) {
            val b = positions.size
            positions += listOf(Vector3(x0, -0.5f, 0f), Vector3(x1, -0.5f, 0f), Vector3(x1, 0.5f, 0f), Vector3(x0, 0.5f, 0f))
            indices += listOf(b, b + 1, b + 2, b, b + 2, b + 3)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { FRONT })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0.5f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.002f)), name = name)
    }

    /**
     * 10 cm of printed blade (local XY, x 0..0.1, facing +Z, [width] high): yellow band, ink ticks every
     * centimetre (longer at 5, longest at 0), the tape's own print. Tiled along a gap.
     */
    fun tapeSegment(width: Float, name: String): MeshResource {
        val positions = ArrayList<Vector3>()
        val uvs = ArrayList<Vector2>()
        val indices = ArrayList<Int>()
        fun rect(x0: Float, x1: Float, y0: Float, y1: Float, column: Int, z: Float) {
            val uv = Vector2((column + 0.5f) / FitMaterials.PALETTE_COLUMNS, 0.5f)
            val b = positions.size
            positions += listOf(Vector3(x0, y0, z), Vector3(x1, y0, z), Vector3(x1, y1, z), Vector3(x0, y1, z))
            repeat(4) { uvs += uv }
            indices += listOf(b, b + 1, b + 2, b, b + 2, b + 3)
        }
        val h = width / 2f
        rect(0f, SEGMENT_M, -h, h, 0, 0f)
        for (i in 0 until 10) {
            val x = i * 0.01f
            val len = when (i) { 0 -> width * 0.62f; 5 -> width * 0.45f; else -> width * 0.26f }
            val tw = if (i == 0) 0.0012f else 0.0008f
            rect(x, x + tw, h - len, h, 1, 0.0004f)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { FRONT }, uv0 = uvs)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(SEGMENT_M / 2f, 0f, 0f), halfExtent = Vector3(SEGMENT_M / 2f, h, 0.002f)), name = name)
    }

    const val SEGMENT_M = 0.1f
}

/** The shared meshes of the scene. */
internal class FitMeshes(shared: SharedResources) {
    val cube: MeshResource = shared.keep(MeshFactory.unitCube("unitCube"))
    val square: MeshResource = shared.keep(MeshFactory.unitSquare("unitSquare"))
    val band: MeshResource = shared.keep(MeshFactory.unitBand("unitBand"))
    val dashes: MeshResource = shared.keep(MeshFactory.unitBand("unitDashes", dashes = 12))
    val segment: MeshResource = shared.keep(MeshFactory.tapeSegment(TAPE_WIDTH_M, "tapeSegment"))

    companion object {
        const val TAPE_WIDTH_M = 0.025f
    }
}

/** A box entity on the shared unit cube: placed by centre, size and rotation. */
internal class Box(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    val entity: Entity = ModelEntity(mesh, material)
    private var material: Material = material
    private var on = true

    init {
        runCatching { entity.setName(name) }
        parent.addChild(entity)
    }

    fun place(center: Vec3, size: Vec3, rotation: Quat4 = Quat4.IDENTITY) {
        entity.setLocal(position = center, rotation = rotation, scale = size)
        show(true)
    }

    fun material(m: Material) {
        if (m === material) return
        material = m
        entity.setMaterial(m)
    }

    fun show(visible: Boolean) {
        if (visible != on) {
            entity.enabled = visible
            on = visible
        }
    }
}

/**
 * A straight strip between two points with its face turned along a normal (made square to the strip):
 * the shared unit band scaled to length and width (tapes, footprint tape, outlines, stems).
 */
internal class Strip(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    private val entity: Entity = ModelEntity(mesh, material)
    private var material: Material = material
    private var on = true

    init {
        runCatching { entity.setName(name) }
        parent.addChild(entity)
        hide()
    }

    fun draw(from: Vec3, to: Vec3, width: Float, faceNormal: Vec3) {
        val full = to - from
        val length = full.length
        if (length < 1e-4f) {
            hide()
            return
        }
        val dir = full / length
        var z = faceNormal.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.UP.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.RIGHT.rejectFrom(dir)
        z = z.normalized()
        entity.setLocal(position = from, rotation = Quat4.fromBasis(dir, z.cross(dir).normalized(), z), scale = Vec3(length, width, 1f))
        if (!on) {
            entity.enabled = true
            on = true
        }
    }

    /** A segment of fixed length [length] from [from] along [dir] (the tape's printed 10 cm). */
    fun drawFixed(from: Vec3, dir: Vec3, length: Float, faceNormal: Vec3) {
        var z = faceNormal.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.UP.rejectFrom(dir)
        z = z.normalized()
        entity.setLocal(position = from, rotation = Quat4.fromBasis(dir, z.cross(dir).normalized(), z), scale = Vec3(length / MeshFactory.SEGMENT_M, 1f, 1f))
        if (!on) {
            entity.enabled = true
            on = true
        }
    }

    fun material(m: Material) {
        if (m === material) return
        material = m
        entity.setMaterial(m)
    }

    fun hide() {
        if (on) {
            entity.enabled = false
            on = false
        }
    }
}
