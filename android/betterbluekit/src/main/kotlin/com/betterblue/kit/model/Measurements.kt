package com.betterblue.kit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.roundToInt

@Serializable
data class Distance(
    val length: Double,
    val units: Units,
) {
    @Serializable
    enum class Units {
        @SerialName("miles")
        MILES,

        @SerialName("kilometers")
        KILOMETERS,
        ;

        val displayName: String get() = if (this == MILES) "Miles" else "Kilometers"
        val abbreviation: String get() = if (this == MILES) "mi" else "km"

        fun convert(length: Double, targetUnits: Units): Double =
            when {
                this == targetUnits -> length
                this == MILES && targetUnits == KILOMETERS -> length * 1.609344
                this == KILOMETERS && targetUnits == MILES -> length / 1.609344
                else -> length
            }

        fun format(length: Double, targetUnits: Units): String {
            val convertedLength = convert(length, targetUnits)
            // Grouped decimal formatting so a 19,500 mi odometer doesn't render
            // as "19500"; the locale supplies the grouping separator.
            val formatter =
                NumberFormat.getNumberInstance().apply {
                    maximumFractionDigits = 0
                }
            return "${formatter.format(convertedLength)} ${targetUnits.abbreviation}"
        }

        companion object {
            /** Hyundai/Kia unit code: 1 = kilometers, anything else = miles. */
            fun fromInt(value: Int): Units = if (value == 1) KILOMETERS else MILES
        }
    }
}

/**
 * Which lookup table the vehicle's HVAC controller uses to map between
 * Celsius and Fahrenheit. Decompiled from Hyundai's Android app:
 *
 * - [STANDARD] — older Hyundai/Kia USA + Hyundai Canada vehicles. Mostly
 *   linear but with two known non-linearities (17.5°C and 18.0°C both round
 *   to 63°F; 31.0°C and 31.5°C both round to 89°F).
 * - [EUROPEAN] — CCS2 EU vehicles (Hyundai EU, Kia EU). Strictly 0.5°C →
 *   integer °F with no duplicate mappings.
 *
 * The car only accepts values that exist in its table. Sending a linear
 * formula result like 22.22°C (from 72°F) silently no-ops.
 */
enum class HvacTemperatureTable { STANDARD, EUROPEAN }

@Serializable
data class Temperature(
    val units: Units,
    val value: Double,
) {
    @Serializable
    enum class Units {
        @SerialName("celsius")
        CELSIUS,

        @SerialName("fahrenheit")
        FAHRENHEIT,
        ;

        fun toInt(): Int = if (this == FAHRENHEIT) 1 else 0

        val displayName: String get() = if (this == FAHRENHEIT) "Fahrenheit" else "Celsius"
        val symbol: String get() = if (this == FAHRENHEIT) "°F" else "°C"

        /** Standard HVAC range for this unit. */
        val hvacRange: ClosedFloatingPointRange<Double>
            get() =
                when (this) {
                    FAHRENHEIT -> 62.0..82.0
                    CELSIUS -> 16.0..28.0
                }

        fun format(temperature: Double, targetUnits: Units): String {
            val convertedTemperature = convert(temperature, targetUnits)
            // Celsius supports half-degree steps: snap to the 0.5°C grid and show
            // up to one decimal; Fahrenheit is whole-degree only. The locale
            // supplies the decimal separator ("22,5°C" in de-DE).
            val formatter = NumberFormat.getNumberInstance()
            val displayValue =
                when (targetUnits) {
                    CELSIUS -> {
                        formatter.maximumFractionDigits = 1
                        Math.round(convertedTemperature * 2).toDouble() / 2
                    }

                    FAHRENHEIT -> {
                        formatter.maximumFractionDigits = 0
                        convertedTemperature
                    }
                }
            return "${formatter.format(displayValue)}${targetUnits.symbol}"
        }

        /**
         * Pure mathematical F↔C conversion. Do NOT use this for values sent to
         * the HVAC controller — the car only accepts values from its lookup
         * table; use [Temperature.hvacConvert] for those.
         */
        fun convert(temperature: Double, targetUnits: Units): Double =
            when {
                this == CELSIUS && targetUnits == FAHRENHEIT -> (temperature * 9.0 / 5.0) + 32.0
                this == FAHRENHEIT && targetUnits == CELSIUS -> (temperature - 32.0) * 5.0 / 9.0
                else -> temperature
            }

        companion object {
            /** Hyundai/Kia unit code: 1 = fahrenheit, anything else (or null) = celsius. */
            fun fromInt(value: Int?): Units = if (value == 1) FAHRENHEIT else CELSIUS
        }
    }

    companion object {
        const val MINIMUM = 62.0
        const val MAXIMUM = 82.0

        /**
         * Parses the API's temperature representation: a plain number, "HI",
         * a "NNH" hex code, or (fallback) the HVAC minimum.
         */
        fun fromApi(units: Int?, value: String?): Temperature {
            val parsedUnits = Units.fromInt(units)
            val number = value?.toDoubleOrNull()
            val parsedValue =
                when {
                    number != null -> number
                    value == "HI" -> Units.FAHRENHEIT.convert(MAXIMUM, parsedUnits)
                    !value.isNullOrEmpty() && value.endsWith("H") -> parseAirTempFromHex(value, parsedUnits)
                    else -> Units.FAHRENHEIT.convert(MINIMUM, parsedUnits)
                }
            return Temperature(parsedUnits, parsedValue)
        }

        private fun parseAirTempFromHex(rawValue: String, targetUnits: Units): Double {
            // Hyundai/Kia HEX format "02H", "0AH", ...
            val index = rawValue.dropLast(1).toIntOrNull(16)
            if (index == null || index !in 0 until 32) {
                return Units.FAHRENHEIT.convert(MINIMUM, targetUnits)
            }
            // tempC = (28 + index) * 0.5
            val tempC = (28 + index) * 0.5
            return Units.CELSIUS.convert(tempC, targetUnits)
        }

        /**
         * Convert a temperature between °C and °F for use with the HVAC
         * controller, using the car's lookup table so the result is guaranteed
         * to land on a value the controller accepts. F→C ties (63°F under
         * [HvacTemperatureTable.STANDARD]) resolve to the FIRST match — the
         * lower Celsius value, matching Hyundai's own UI.
         */
        fun hvacConvert(
            value: Double,
            sourceUnits: Units,
            targetUnits: Units,
            table: HvacTemperatureTable = HvacTemperatureTable.EUROPEAN,
        ): Double {
            if (sourceUnits == targetUnits) return value
            val pairs = pairsFor(table)
            return when {
                sourceUnits == Units.CELSIUS && targetUnits == Units.FAHRENHEIT -> {
                    val snapped = snapToHalfDegreeCelsius(value)
                    pairs.minBy { abs(it.first - snapped) }.second.toDouble()
                }

                sourceUnits == Units.FAHRENHEIT && targetUnits == Units.CELSIUS -> {
                    val rounded = Math.round(value).toDouble()
                    pairs.minBy { abs(it.second - rounded) }.first
                }

                else -> {
                    value
                }
            }
        }

        /** Snap a Celsius value to the 0.5°C grid the HVAC controller uses. */
        fun snapToHalfDegreeCelsius(celsius: Double): Double =
            Math.round(celsius * 2).toDouble() / 2

        /**
         * Encode a 0.5°C-grid Celsius value as the HEX form the older
         * Hyundai/Kia APIs (USA + Canada non-CCS2) expect — e.g. 17.0°C →
         * "06H", 22.0°C → "10H". Inverse of the hex parser; clamped to
         * [14.0°C, 31.5°C] (32 values, 0x00–0x1F).
         */
        fun encodeAirTempToHex(celsius: Double): String {
            val snapped = snapToHalfDegreeCelsius(celsius)
            // tempC = (28 + index) * 0.5  →  index = tempC * 2 - 28
            val index = (snapped * 2).roundToInt() - 28
            val clamped = index.coerceIn(0, 31)
            return "%02XH".format(clamped)
        }

        /**
         * Standard table (`hvacTempType != 1`) — note the duplicate-target rows
         * 17.5/18.0 → 63°F and 31.0/31.5 → 89°F.
         */
        private val STANDARD_TABLE: List<Pair<Double, Int>> =
            listOf(
                14.5 to 57, 15.0 to 58, 15.5 to 59, 16.0 to 60, 16.5 to 61,
                17.0 to 62, 17.5 to 63, 18.0 to 63,
                18.5 to 64, 19.0 to 65, 19.5 to 66, 20.0 to 67, 20.5 to 68,
                21.0 to 69, 21.5 to 70, 22.0 to 71, 22.5 to 72, 23.0 to 73,
                23.5 to 74, 24.0 to 75, 24.5 to 76, 25.0 to 77, 25.5 to 78,
                26.0 to 79, 26.5 to 80, 27.0 to 81, 27.5 to 82, 28.0 to 83,
                28.5 to 84, 29.0 to 85, 29.5 to 86, 30.0 to 87, 30.5 to 88,
                31.0 to 89, 31.5 to 89,
                32.0 to 90, 32.5 to 91,
            )

        /** EU table (`hvacTempType == 1`) — strict 0.5°C → integer °F, no duplicates. */
        private val EU_TABLE: List<Pair<Double, Int>> =
            listOf(
                15.0 to 58, 15.5 to 59, 16.0 to 60, 16.5 to 61, 17.0 to 62,
                17.5 to 63, 18.0 to 64, 18.5 to 65, 19.0 to 66, 19.5 to 67,
                20.0 to 68, 20.5 to 69, 21.0 to 70, 21.5 to 71, 22.0 to 72,
                22.5 to 73, 23.0 to 74, 23.5 to 75, 24.0 to 76, 24.5 to 77,
                25.0 to 78, 25.5 to 79, 26.0 to 80, 26.5 to 81, 27.0 to 82,
                27.5 to 83, 28.0 to 84, 28.5 to 85, 29.0 to 86, 29.5 to 87,
                30.0 to 88,
            )

        private fun pairsFor(table: HvacTemperatureTable): List<Pair<Double, Int>> =
            when (table) {
                HvacTemperatureTable.STANDARD -> STANDARD_TABLE
                HvacTemperatureTable.EUROPEAN -> EU_TABLE
            }
    }
}
