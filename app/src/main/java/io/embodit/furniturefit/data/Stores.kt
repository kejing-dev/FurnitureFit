package io.embodit.furniturefit.data

import android.content.Context
import io.embodit.furniturefit.domain.AnchorPose
import io.embodit.furniturefit.domain.LanguagePref
import io.embodit.furniturefit.domain.LayoutCodec
import io.embodit.furniturefit.domain.LengthUnit
import io.embodit.furniturefit.domain.Look
import io.embodit.furniturefit.domain.SavedLayout
import io.embodit.furniturefit.domain.Settings
import io.embodit.furniturefit.domain.Units
import java.util.Locale

/** A world anchor as the app sees it. [id] is the platform's UUID as a string. */
data class AnchorRecord(val id: String, val pose: AnchorPose)

sealed interface AnchorOutcome<out T> {
    data class Ok<T>(val value: T) : AnchorOutcome<T>

    data class Failed(val code: Int, val message: String) : AnchorOutcome<Nothing>
}

/** The platform's persistent anchors (`platform.anchor.WorldAnchorGateway`; a fake in tests). */
interface AnchorGateway {
    suspend fun create(position: io.embodit.furniturefit.domain.Vec3, name: String): AnchorOutcome<AnchorRecord>

    /** All anchors of this app in this space; an empty store is `Ok(emptyList())`. */
    suspend fun loadAll(): AnchorOutcome<List<AnchorRecord>>

    suspend fun remove(anchorId: String): AnchorOutcome<Unit>
}

/** Layout and settings on the device (no network, no account). */
interface LayoutStore {
    fun loadLayout(): SavedLayout?

    fun saveLayout(layout: SavedLayout?)

    fun loadSettings(): Settings

    fun saveSettings(settings: Settings)
}

class PrefsLayoutStore(context: Context) : LayoutStore {
    private val prefs = context.getSharedPreferences("furniture_fit", Context.MODE_PRIVATE)

    override fun loadLayout(): SavedLayout? = LayoutCodec.decode(prefs.getString(KEY_LAYOUT, null))

    override fun saveLayout(layout: SavedLayout?) {
        prefs.edit().apply { if (layout == null) remove(KEY_LAYOUT) else putString(KEY_LAYOUT, LayoutCodec.encode(layout)) }.commit()
    }

    override fun loadSettings(): Settings {
        val unitDefault = Units.defaultUnit(Locale.getDefault().country)
        return Settings(
            unit = LengthUnit.entries.firstOrNull { it.tag == prefs.getString("unit", null) } ?: unitDefault,
            language = LanguagePref.entries.firstOrNull { it.tag == prefs.getString("lang", null) } ?: LanguagePref.SYSTEM,
            look = Look.entries.firstOrNull { it.tag == prefs.getString("look", null) } ?: Look.SOLID,
            coachSeen = prefs.getBoolean("coach", false),
        )
    }

    override fun saveSettings(settings: Settings) {
        prefs.edit()
            .putString("unit", settings.unit.tag)
            .putString("lang", settings.language.tag)
            .putString("look", settings.look.tag)
            .putBoolean("coach", settings.coachSeen)
            .apply()
    }

    private companion object {
        const val KEY_LAYOUT = "layout"
    }
}
