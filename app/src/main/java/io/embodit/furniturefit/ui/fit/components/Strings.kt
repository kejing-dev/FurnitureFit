package io.embodit.furniturefit.ui.fit.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.furniturefit.R
import io.embodit.furniturefit.domain.LanguagePref
import io.embodit.furniturefit.domain.Template
import java.util.Locale

/**
 * Copy for the language chosen in the app (follow system / 中文 / EN), switched at run time without
 * restarting the Stage: the screen provides a Resources object for that locale and every panel reads
 * its strings from it (Spatial Ruler). `LocalContext` is not overridden.
 */
val LocalFitResources = staticCompositionLocalOf<Resources> { error("LocalFitResources not provided") }

fun localizedResources(context: Context, language: LanguagePref): Resources {
    val tag =
        when (language) {
            LanguagePref.SYSTEM -> return context.resources
            LanguagePref.ZH -> "zh-CN"
            LanguagePref.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalFitResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalFitResources.current.getString(id, *args)

@Composable
@ReadOnlyComposable
fun rq(@androidx.annotation.PluralsRes id: Int, count: Int): String = LocalFitResources.current.getQuantityString(id, count, count)

/** A template's name in the current language (accessibility name only: tiles show icon + size). */
@Composable
@ReadOnlyComposable
fun templateName(t: Template): String = if (LocalFitResources.current.getString(R.string.lang_code) == "zh") t.zh else t.en

@DrawableRes
fun templateIcon(id: String): Int =
    when (id) {
        "sofa3" -> R.drawable.fu_sofa3
        "sofa2" -> R.drawable.fu_sofa2
        "armchair" -> R.drawable.fu_armchair
        "bedDouble" -> R.drawable.fu_bed_double
        "bedSingle" -> R.drawable.fu_bed_single
        "dining" -> R.drawable.fu_dining
        "desk" -> R.drawable.fu_desk
        "bookcase" -> R.drawable.fu_bookcase
        "wardrobe" -> R.drawable.fu_wardrobe
        "tvStand" -> R.drawable.fu_tv_stand
        else -> R.drawable.fu_custom
    }

/** Panel sizes (design-package `panels[].sizeDp`), in dp. */
object PanelSizes {
    const val LIBRARY_W = 400f
    const val LIBRARY_H = 288f
    const val STRIP_W = 384f
    const val STRIP_H = 96f
    const val SIZING_W = 384f
    const val SIZING_H = 312f
    const val GAP_W = 128f
    const val GAP_H = 40f
    const val MENU_W = 256f
    const val MENU_H = 232f
    const val DIALOG_W = 400f
    const val DIALOG_H = 268f
    const val SNACK_W = 560f
    const val SNACK_H = 56f
    const val COACH_W = 360f
    const val COACH_H = 80f

    /** Disabled content (SpatialUI's own disabled alpha; `LocalDisableAlpha` was not confirmed, design needsCheck). */
    const val DISABLED_ALPHA = 0.38f
}
