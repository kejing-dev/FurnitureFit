package io.embodit.furniturefit.platform

import android.content.Context
import io.embodit.furniturefit.data.LayoutStore
import io.embodit.furniturefit.data.PrefsLayoutStore
import io.embodit.furniturefit.platform.anchor.WorldAnchorGateway
import io.embodit.furniturefit.platform.sense.MeshTracker
import io.embodit.furniturefit.platform.sense.PlaneTracker
import io.embodit.furniturefit.platform.tracking.ControllerStick
import io.embodit.furniturefit.platform.tracking.HeadTracker

/**
 * Process-wide platform services. The scene starts and stops the sensing services (every start has a
 * stop: `FitScene.attach` / `detach`); the ViewModel only sees interfaces and lambdas.
 */
object FitGraph {
    val anchors = WorldAnchorGateway()
    val planes = PlaneTracker()
    val mesh = MeshTracker()
    val head = HeadTracker()
    val stick = ControllerStick()

    @Volatile private var store: LayoutStore? = null

    fun store(context: Context): LayoutStore = store ?: synchronized(this) { store ?: PrefsLayoutStore(context).also { store = it } }
}
