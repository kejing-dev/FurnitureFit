package io.embodit.furniturefit.domain

import kotlin.math.roundToInt

enum class LengthUnit(val tag: String) { CM("cm"), IN("in") }

enum class LanguagePref(val tag: String) { SYSTEM("system"), ZH("zh"), EN("en") }

enum class Look(val tag: String) { SOLID("solid"), WIRE("wire") }

data class Settings(
    val unit: LengthUnit = LengthUnit.CM,
    val language: LanguagePref = LanguagePref.SYSTEM,
    val look: Look = Look.SOLID,
    val coachSeen: Boolean = false,
)

/** Readings and sizes (`rules.units`, `rules.precisionNote`): whole centimetres or whole inches; metres inside. */
object Units {
    private const val INCH_M = 0.0254f

    fun whole(m: Float, unit: LengthUnit): Int = if (unit == LengthUnit.CM) (m * 100f).roundToInt() else (m / INCH_M).roundToInt()

    fun metres(value: Int, unit: LengthUnit): Float = if (unit == LengthUnit.CM) value / 100f else value * INCH_M

    /** "92 cm" / "36 in". */
    fun reading(m: Float, unit: LengthUnit): String = "${whole(m, unit)} ${unit.tag}"

    /** "210×90×85" (W x D x H, no unit). */
    fun dims(d: Dims, unit: LengthUnit): String = "${whole(d.w, unit)}×${whole(d.d, unit)}×${whole(d.h, unit)}"

    fun minInput(unit: LengthUnit): Int = if (unit == LengthUnit.CM) FitRules.MIN_SIZE_CM else 4

    fun maxInput(unit: LengthUnit): Int = if (unit == LengthUnit.CM) FitRules.MAX_SIZE_CM else 160

    fun defaultUnit(country: String?): LengthUnit = if (country == "US") LengthUnit.IN else LengthUnit.CM
}
