package io.embodit.furniturefit.platform.sense

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.mesh.MeshAnchor
import com.pico.spatial.sense.mesh.MeshTrackingManager
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneTrackingManager
import io.embodit.furniturefit.domain.MeshPoint
import io.embodit.furniturefit.domain.PlaneSurface
import io.embodit.furniturefit.domain.RoomModel
import io.embodit.furniturefit.domain.Vec3
import io.embodit.furniturefit.platform.FitLog

/**
 * Plane detection (`PlaneTrackingManager`, Full Space only). Planes arrive progressively (on the
 * emulator the first after about 20 s) and keep being re-reported; unchanged ones are dropped.
 * Vertices are converted to Stage metres here (anchor transform applied). Main thread only.
 */
class PlaneTracker {
    private val main = Handler(Looper.getMainLooper())
    private val surfaces = LinkedHashMap<String, PlaneSurface>()
    private var subscription: Cancellable? = null
    private var onChange: (() -> Unit)? = null

    val all: Collection<PlaneSurface>
        get() = surfaces.values

    fun start(onChange: () -> Unit) {
        if (subscription != null) return
        this.onChange = onChange
        // subscribe first, then start: no event is lost
        subscription = PlaneTrackingManager.subscribeAnchorUpdate { u -> if (Looper.myLooper() == Looper.getMainLooper()) handle(u) else main.post { handle(u) } }
        runCatching { PlaneTrackingManager.start() }.onFailure { FitLog.e("PLANES start failed: $it", it) }
        FitLog.i("PLANES tracking started state=${runCatching { PlaneTrackingManager.state }.getOrNull()}")
    }

    fun stop() {
        runCatching { subscription?.cancel() }
        subscription = null
        runCatching { PlaneTrackingManager.stop() }
        onChange = null
        main.removeCallbacksAndMessages(null)
    }

    /** Planes stay known across a pause (they are re-reported on the next start). */
    fun clear() = surfaces.clear()

    private fun handle(update: AnchorUpdate<PlaneAnchor>) {
        if (subscription == null) return
        val anchor = update.anchor
        val id = anchor.anchorUUID.toString()
        when (update.event) {
            AnchorUpdate.Event.ADDED, AnchorUpdate.Event.LOADED, AnchorUpdate.Event.UPDATED -> {
                val s = runCatching { toSurface(anchor, id) }.getOrNull() ?: return
                if (s.vertices.size < 3) return
                val prev = surfaces[id]
                if (prev != null && same(prev, s)) return
                surfaces[id] = s
                if (prev == null) FitLog.i("PLANES add ${s.label} n=${surfaces.size} center=${FitLog.pos(s.centroid)} normal=${FitLog.pos(s.normal)}")
                onChange?.invoke()
            }
            AnchorUpdate.Event.REMOVED -> if (surfaces.remove(id) != null) onChange?.invoke()
            else -> Unit
        }
    }

    private fun toSurface(anchor: PlaneAnchor, id: String): PlaneSurface {
        val q = anchor.transform.quaternion
        val p = anchor.transform.position
        val stage =
            anchor.vertices.map { v ->
                val w = q.rotateVector(v) + p
                Vec3(w.x, w.y, w.z)
            }
        return PlaneSurface(id, anchor.semantics.name, stage)
    }

    private fun same(a: PlaneSurface, b: PlaneSurface): Boolean {
        if (a.vertices.size != b.vertices.size || a.semantic != b.semantic) return false
        for (i in a.vertices.indices) if (a.vertices[i].distanceTo(b.vertices[i]) > 0.0005f) return false
        return true
    }
}

/**
 * Spatial mesh (`MeshTrackingManager`, Full Space only): per-vertex semantics. Only vertices with a
 * furniture semantic are kept (that is all the room model uses), converted to Stage metres. The
 * design runs the mesh while the room is being looked at and stops it when nothing new has come for a
 * while (the documentation's advice: keep the data, stop the provider); [restart] reopens it.
 */
class MeshTracker {
    private val main = Handler(Looper.getMainLooper())
    private val anchors = LinkedHashMap<String, List<MeshPoint>>()
    private var subscription: Cancellable? = null
    private var onChange: (() -> Unit)? = null
    private var cache: List<MeshPoint>? = null
    private val seenSemantics = HashMap<String, Int>()

    var lastNewAtMs = 0L
        private set

    var running = false
        private set

    val anchorCount: Int
        get() = anchors.size

    fun points(): List<MeshPoint> = cache ?: anchors.values.flatten().also { cache = it }

    /** Vertex count per semantic seen so far (all semantics, before the furniture filter): QA `needsCheck`. */
    fun semanticHistogram(): Map<String, Int> = HashMap(seenSemantics)

    fun start(onChange: () -> Unit) {
        this.onChange = onChange
        if (subscription != null) return
        subscription = MeshTrackingManager.subscribeAnchorUpdate { u -> if (Looper.myLooper() == Looper.getMainLooper()) handle(u) else main.post { handle(u) } }
        runCatching { MeshTrackingManager.start() }.onFailure { FitLog.e("MESH start failed: $it", it) }
        running = true
        lastNewAtMs = SystemClock.elapsedRealtime()
        FitLog.i("MESH tracking started state=${runCatching { MeshTrackingManager.state }.getOrNull()} kept_anchors=${anchors.size}")
    }

    /** Stops the provider; the furniture found so far stays. */
    fun stop(reason: String) {
        if (subscription == null) return
        runCatching { subscription?.cancel() }
        subscription = null
        runCatching { MeshTrackingManager.stop() }
        running = false
        main.removeCallbacksAndMessages(null)
        FitLog.i("MESH tracking stopped reason=$reason anchors=${anchors.size} points=${points().size}")
    }

    fun restart(reason: String) {
        val cb = onChange ?: return
        if (subscription != null) return
        FitLog.i("MESH restart reason=$reason")
        start(cb)
    }

    private fun handle(update: AnchorUpdate<MeshAnchor>) {
        if (subscription == null) return
        val anchor = update.anchor
        val id = anchor.anchorUUID.toString()
        when (update.event) {
            AnchorUpdate.Event.ADDED, AnchorUpdate.Event.LOADED, AnchorUpdate.Event.UPDATED -> {
                val pts = runCatching { toPoints(anchor) }.onFailure { FitLog.w("MESH convert failed: $it") }.getOrNull() ?: return
                val isNew = id !in anchors
                anchors[id] = pts
                cache = null
                if (isNew) {
                    lastNewAtMs = SystemClock.elapsedRealtime()
                    FitLog.i("MESH add id=${id.take(8)} furniture_pts=${pts.size} n=${anchors.size}")
                }
                onChange?.invoke()
            }
            AnchorUpdate.Event.REMOVED ->
                if (anchors.remove(id) != null) {
                    cache = null
                    onChange?.invoke()
                }
            else -> Unit
        }
    }

    private fun toPoints(anchor: MeshAnchor): List<MeshPoint> {
        val q = anchor.transform.quaternion
        val p = anchor.transform.position
        val vertices = anchor.vertices
        val semantics = anchor.semantics
        val single = semantics.size == 1
        val out = ArrayList<MeshPoint>()
        for (i in vertices.indices) {
            val s = (if (single) semantics[0] else semantics.getOrNull(i))?.name ?: "UNKNOWN"
            seenSemantics[s] = (seenSemantics[s] ?: 0) + 1
            if (s !in RoomModel.FURNITURE) continue
            val w = q.rotateVector(vertices[i]) + p
            out += MeshPoint(Vec3(w.x, w.y, w.z), s)
        }
        if (semantics.size != vertices.size && !single) FitLog.w("MESH semantics=${semantics.size} vertices=${vertices.size} id=${anchor.anchorUUID.toString().take(8)}")
        return out
    }
}
