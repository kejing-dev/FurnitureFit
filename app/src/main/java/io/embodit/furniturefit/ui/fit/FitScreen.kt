package io.embodit.furniturefit.ui.fit

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.SpatialSoundEffect
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.furniturefit.platform.FitLog
import io.embodit.furniturefit.ui.fit.components.FitStage
import io.embodit.furniturefit.ui.fit.components.LocalFitResources
import io.embodit.furniturefit.ui.fit.components.localizedResources
import io.embodit.furniturefit.ui.fit.debug.Probe
import io.embodit.furniturefit.ui.fit.scene.FitFrameSystem
import io.embodit.furniturefit.ui.fit.scene.FitScene

/**
 * The one screen of the app, in a Mixed Stage (Full Space). It wires the ViewModel (state), the
 * [FitScene] (entities, per-frame work, taps and drags) and [FitStage] (the panels), and owns nothing.
 */
@Composable
fun FitScreen() {
    val context = LocalContext.current
    val viewModel: FitViewModel = viewModel(factory = FitViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { FitScene(viewModel::onEvent) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(scene, lifecycle) {
        // the one per-frame system; every start has its stop in onDispose
        registerSystem<FitFrameSystem>()
        Probe.host =
            object : Probe.Host {
                override fun viewModel(): FitViewModel = viewModel

                override fun scene(): FitScene = scene
            }
        // in the background every tracker stops (design `container.lifecycle`)
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> scene.pause()
                    Lifecycle.Event.ON_START -> scene.resume()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        FitLog.i("STAGE enter")
        onDispose {
            lifecycle.removeObserver(observer)
            Probe.host = null
            unregisterSystem<FitFrameSystem>()
            scene.detach()
            FitLog.i("STAGE disposed")
        }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    val audio = LocalAudioEffectPlayer.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                FitEffect.Exit -> {
                    FitLog.i("STAGE close requested")
                    runCatching { navigator.closeStage() }.onFailure { FitLog.e("STAGE close failed: $it", it) }
                }
                is FitEffect.Feedback ->
                    runCatching {
                        // "嗒" on snapping, a click on placing (design `explore.signature`)
                        audio.playSystem(if (effect.kind == "snap") SpatialSoundEffect.StateSelected else SpatialSoundEffect.OpClick)
                    }.onFailure { FitLog.w("FEEDBACK failed: $it") }
            }
        }
    }

    // the in-app language switches at run time: panels read their copy from this local
    val resources = remember(state.settings.language, context) { localizedResources(context, state.settings.language) }
    CompositionLocalProvider(LocalFitResources provides resources) {
        FitStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}
