package io.embodit.furniturefit.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.furniturefit.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
