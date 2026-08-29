package com.betterblue.kit.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class MeasurementsTest {

    // Distance

    @Test
    fun `distance units from integer`() {
        assertEquals(Distance.Units.KILOMETERS, Distance.Units.fromInt(1))
        assertEquals(Distance.Units.MILES, Distance.Units.fromInt(0))
        assertEquals(Distance.Units.MILES, Distance.Units.fromInt(2))
        assertEquals(Distance.Units.MILES, Distance.Units.fromInt(-1))
    }

    @Test
    fun `distance units display properties`() {
        assertEquals("Miles", Distance.Units.MILES.displayName)
        assertEquals("Kilometers", Distance.Units.KILOMETERS.displayName)
        assertEquals("mi", Distance.Units.MILES.abbreviation)
        assertEquals("km", Distance.Units.KILOMETERS.abbreviation)
    }

    @Test
    fun `distance conversion miles to kilometers`() {
        assertTrue(abs(Distance.Units.MILES.convert(100.0, Distance.Units.KILOMETERS) - 160.9344) < 0.0001)
    }

    @Test
    fun `distance conversion kilometers to miles`() {
        assertTrue(abs(Distance.Units.KILOMETERS.convert(160.9344, Distance.Units.MILES) - 100.0) < 0.0001)
    }

    @Test
    fun `distance conversion same units`() {
        assertEquals(100.0, Distance.Units.MILES.convert(100.0, Distance.Units.MILES))
        assertEquals(160.0, Distance.Units.KILOMETERS.convert(160.0, Distance.Units.KILOMETERS))
    }

    @Test
    fun `distance format with conversion`() {
        assertEquals("161 km", Distance.Units.MILES.format(100.0, Distance.Units.KILOMETERS))
        assertEquals("100 mi", Distance.Units.KILOMETERS.format(160.9344, Distance.Units.MILES))
        assertEquals("100 mi", Distance.Units.MILES.format(100.0, Distance.Units.MILES))
    }

    @Test
    fun `distance format groups thousands`() {
        // A 19,500 mi odometer must not render as the ungrouped "19500";
        // assert grouping happened rather than a specific separator so the
        // test stays locale-independent.
        val formatted = Distance.Units.MILES.format(19500.0, Distance.Units.MILES)
        assertFalse(formatted.contains("19500"))
        assertTrue(formatted.endsWith(" mi"))
    }

    @Test
    fun `distance conversion edge cases`() {
        assertEquals(0.0, Distance.Units.MILES.convert(0.0, Distance.Units.KILOMETERS))
        assertTrue(abs(Distance.Units.MILES.convert(1_000_000.0, Distance.Units.KILOMETERS) - 1_609_344.0) < 1.0)
        assertTrue(abs(Distance.Units.MILES.convert(0.001, Distance.Units.KILOMETERS) - 0.001609344) < 0.000001)
    }

    // Temperature

    @Test
    fun `temperature units from integer`() {
        assertEquals(Temperature.Units.FAHRENHEIT, Temperature.Units.fromInt(1))
        assertEquals(Temperature.Units.CELSIUS, Temperature.Units.fromInt(0))
        assertEquals(Temperature.Units.CELSIUS, Temperature.Units.fromInt(null))
        assertEquals(Temperature.Units.CELSIUS, Temperature.Units.fromInt(2))
    }

    @Test
    fun `temperature units display properties`() {
        assertEquals("Fahrenheit", Temperature.Units.FAHRENHEIT.displayName)
        assertEquals("Celsius", Temperature.Units.CELSIUS.displayName)
        assertEquals("°F", Temperature.Units.FAHRENHEIT.symbol)
        assertEquals("°C", Temperature.Units.CELSIUS.symbol)
        assertEquals(1, Temperature.Units.FAHRENHEIT.toInt())
        assertEquals(0, Temperature.Units.CELSIUS.toInt())
    }

    @Test
    fun `temperature hvac ranges`() {
        assertEquals(62.0, Temperature.Units.FAHRENHEIT.hvacRange.start)
        assertEquals(82.0, Temperature.Units.FAHRENHEIT.hvacRange.endInclusive)
        assertEquals(16.0, Temperature.Units.CELSIUS.hvacRange.start)
        assertEquals(28.0, Temperature.Units.CELSIUS.hvacRange.endInclusive)
    }

    @Test
    fun `temperature format between units`() {
        assertEquals("72°F", Temperature.Units.CELSIUS.format(22.0, Temperature.Units.FAHRENHEIT))
        assertEquals("22°C", Temperature.Units.FAHRENHEIT.format(72.0, Temperature.Units.CELSIUS))
        assertEquals("72°F", Temperature.Units.FAHRENHEIT.format(72.0, Temperature.Units.FAHRENHEIT))
        assertEquals("22°C", Temperature.Units.CELSIUS.format(22.0, Temperature.Units.CELSIUS))
    }

    @Test
    fun `temperature format edge cases`() {
        assertEquals("32°F", Temperature.Units.CELSIUS.format(0.0, Temperature.Units.FAHRENHEIT))
        assertEquals("212°F", Temperature.Units.CELSIUS.format(100.0, Temperature.Units.FAHRENHEIT))
        assertEquals("-460°F", Temperature.Units.CELSIUS.format(-273.15, Temperature.Units.FAHRENHEIT))
    }

    @Test
    fun `temperature from api string and int`() {
        val temp1 = Temperature.fromApi(1, "72")
        assertEquals(Temperature.Units.FAHRENHEIT, temp1.units)
        assertEquals(72.0, temp1.value)

        val temp2 = Temperature.fromApi(0, "22")
        assertEquals(Temperature.Units.CELSIUS, temp2.units)
        assertEquals(22.0, temp2.value)

        // Temperature.MINIMUM is defined in Fahrenheit (62.0); when units
        // default to Celsius the minimum is converted to match.
        val temp3 = Temperature.fromApi(null, null)
        assertEquals(Temperature.Units.CELSIUS, temp3.units)
        assertEquals(
            Temperature.Units.FAHRENHEIT.convert(Temperature.MINIMUM, Temperature.Units.CELSIUS),
            temp3.value,
        )
    }

    @Test
    fun `temperature from api HI value`() {
        val temp = Temperature.fromApi(1, "HI")
        assertEquals(Temperature.Units.FAHRENHEIT, temp.units)
        assertEquals(Temperature.MAXIMUM, temp.value)
    }

    @Test
    fun `temperature from api invalid value falls back to minimum`() {
        val temp = Temperature.fromApi(1, "invalid")
        assertEquals(Temperature.Units.FAHRENHEIT, temp.units)
        assertEquals(Temperature.MINIMUM, temp.value)
    }

    @Test
    fun `temperature from api hex code`() {
        // "10H" = index 16 → (28 + 16) * 0.5 = 22.0°C → 71.6°F
        val temp = Temperature.fromApi(0, "10H")
        assertEquals(Temperature.Units.CELSIUS, temp.units)
        assertEquals(22.0, temp.value)
    }

    // HVAC table-based conversion

    @Test
    fun `hvacConvert snaps to grid on EU table`() {
        // 72°F → 22.0°C exactly under the EU table (not the off-grid 22.22°C
        // the linear formula produces).
        assertEquals(
            22.0,
            Temperature.hvacConvert(
                72.0,
                Temperature.Units.FAHRENHEIT,
                Temperature.Units.CELSIUS,
                HvacTemperatureTable.EUROPEAN,
            ),
        )
    }

    @Test
    fun `hvacConvert round-trips on the EU table`() {
        val pairs = listOf(15.0 to 58, 18.0 to 64, 22.0 to 72, 25.0 to 78, 30.0 to 88)
        for ((c, f) in pairs) {
            assertEquals(
                f.toDouble(),
                Temperature.hvacConvert(
                    c,
                    Temperature.Units.CELSIUS,
                    Temperature.Units.FAHRENHEIT,
                    HvacTemperatureTable.EUROPEAN,
                ),
            )
            assertEquals(
                c,
                Temperature.hvacConvert(
                    f.toDouble(),
                    Temperature.Units.FAHRENHEIT,
                    Temperature.Units.CELSIUS,
                    HvacTemperatureTable.EUROPEAN,
                ),
            )
        }
    }

    @Test
    fun `hvacConvert handles standard table duplicate-target rows`() {
        // The standard table maps BOTH 17.5°C and 18.0°C → 63°F. F→C from 63
        // returns the lower (17.5) — Hyundai's own UI tie-break.
        assertEquals(
            17.5,
            Temperature.hvacConvert(
                63.0,
                Temperature.Units.FAHRENHEIT,
                Temperature.Units.CELSIUS,
                HvacTemperatureTable.STANDARD,
            ),
        )
        assertEquals(
            63.0,
            Temperature.hvacConvert(
                17.5,
                Temperature.Units.CELSIUS,
                Temperature.Units.FAHRENHEIT,
                HvacTemperatureTable.STANDARD,
            ),
        )
        assertEquals(
            63.0,
            Temperature.hvacConvert(
                18.0,
                Temperature.Units.CELSIUS,
                Temperature.Units.FAHRENHEIT,
                HvacTemperatureTable.STANDARD,
            ),
        )
    }

    @Test
    fun `hvacConvert is identity when units match`() {
        assertEquals(22.5, Temperature.hvacConvert(22.5, Temperature.Units.CELSIUS, Temperature.Units.CELSIUS))
        assertEquals(72.0, Temperature.hvacConvert(72.0, Temperature.Units.FAHRENHEIT, Temperature.Units.FAHRENHEIT))
    }

    @Test
    fun `snapToHalfDegreeCelsius rounds to grid`() {
        assertEquals(22.0, Temperature.snapToHalfDegreeCelsius(22.22))
        assertEquals(22.5, Temperature.snapToHalfDegreeCelsius(22.25))
        assertEquals(22.0, Temperature.snapToHalfDegreeCelsius(22.0))
        assertEquals(21.5, Temperature.snapToHalfDegreeCelsius(21.74))
    }

    @Test
    fun `encodeAirTempToHex produces the legacy codes`() {
        assertEquals("06H", Temperature.encodeAirTempToHex(17.0))
        assertEquals("10H", Temperature.encodeAirTempToHex(22.0))
        assertEquals("1AH", Temperature.encodeAirTempToHex(27.0))
        // Off-grid input snaps before encoding.
        assertEquals("10H", Temperature.encodeAirTempToHex(22.22))
    }

    @Test
    fun `climate options honors preferred units`() {
        val f = ClimateOptions.forPreferredUnits(Temperature.Units.FAHRENHEIT)
        assertEquals(Temperature.Units.FAHRENHEIT, f.temperature.units)
        assertEquals(72.0, f.temperature.value)

        val c = ClimateOptions.forPreferredUnits(Temperature.Units.CELSIUS)
        assertEquals(Temperature.Units.CELSIUS, c.temperature.units)
        assertEquals(22.0, c.temperature.value)
    }

    @Test
    fun `climate options heat value combinations`() {
        assertEquals(0, ClimateOptions().heatValue)
        assertEquals(2, ClimateOptions(rearDefrost = true).heatValue)
        assertEquals(3, ClimateOptions(steeringWheel = 1).heatValue)
        assertEquals(4, ClimateOptions(rearDefrost = true, steeringWheel = 1).heatValue)
    }

    @Test
    fun `seat setting conversion matches scriptable reference`() {
        assertEquals(0, convertSeatSetting(0, cooling = false))
        assertEquals(0, convertSeatSetting(0, cooling = true))
        // Cooling: 1 → 3, 2 → 4, 3 → 5
        assertEquals(3, convertSeatSetting(1, cooling = true))
        assertEquals(4, convertSeatSetting(2, cooling = true))
        assertEquals(5, convertSeatSetting(3, cooling = true))
        // Heat: 1 → 6, 2 → 7, 3 → 8
        assertEquals(6, convertSeatSetting(1, cooling = false))
        assertEquals(7, convertSeatSetting(2, cooling = false))
        assertEquals(8, convertSeatSetting(3, cooling = false))
    }
}
