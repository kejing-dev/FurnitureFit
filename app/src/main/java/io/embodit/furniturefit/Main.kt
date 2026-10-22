package io.embodit.furniturefit

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.furniturefit.ui.fit.FitScreen
import io.embodit.furniturefit.ui.theme.FurnitureFitTheme

/**
 * App entry. One Mixed Stage (AndroidManifest: `pico.spatial.stage.style=1`, immersion 0/0/0): Full
 * Space with passthrough holds the whole product. There is no WindowContainer and no Shared Space
 * version (design `container`).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            FurnitureFitTheme {
                FitScreen()
            }
        }
    }
