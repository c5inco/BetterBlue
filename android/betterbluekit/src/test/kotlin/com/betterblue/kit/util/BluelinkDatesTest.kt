package com.betterblue.kit.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class BluelinkDatesTest {

    @Test
    fun `basic14 in UTC`() {
        assertEquals(
            Instant.parse("2024-03-15T18:30:45Z"),
            BluelinkDates.parseBasic14Utc("20240315183045"),
        )
    }

    @Test
    fun `basic14 in Berlin honors DST`() {
        // March 15 is CET (+01:00); July 15 is CEST (+02:00).
        assertEquals(
            Instant.parse("2024-03-15T17:30:45Z"),
            BluelinkDates.parseBasic14("20240315183045", BluelinkDates.BERLIN),
        )
        assertEquals(
            Instant.parse("2024-07-15T16:30:45Z"),
            BluelinkDates.parseBasic14("20240715183045", BluelinkDates.BERLIN),
        )
    }

    @Test
    fun `basic14 with millis`() {
        assertEquals(
            Instant.parse("2024-03-15T18:30:45.123Z"),
            BluelinkDates.parseBasic14Millis("20240315183045.123", ZoneOffset.UTC),
        )
    }

    @Test
    fun `iso8601`() {
        assertEquals(
            Instant.parse("2024-03-15T18:30:45Z"),
            BluelinkDates.parseIso8601("2024-03-15T18:30:45Z"),
        )
        assertNull(BluelinkDates.parseIso8601("not-a-date"))
    }

    @Test
    fun `sqlish format used by Hyundai USA trips`() {
        assertEquals(
            Instant.parse("2024-03-15T18:30:45.1Z"),
            BluelinkDates.parseSqlish("2024-03-15 18:30:45.1"),
        )
    }

    @Test
    fun `day-only anchors at noon UTC`() {
        // Anchoring at 12:00 UTC keeps the calendar date stable in any zone.
        assertEquals(
            Instant.parse("2024-03-15T12:00:00Z"),
            BluelinkDates.parseDayNoonUtc("20240315"),
        )
    }

    @Test
    fun `rfc1123 header date`() {
        assertEquals(
            Instant.parse("2024-03-15T18:30:45Z"),
            BluelinkDates.parseRfc1123("Fri, 15 Mar 2024 18:30:45 GMT"),
        )
    }

    @Test
    fun `epoch fallback distinguishes seconds and milliseconds by length`() {
        assertEquals(Instant.ofEpochSecond(1710527445), BluelinkDates.parseEpoch("1710527445"))
        assertEquals(Instant.ofEpochMilli(1710527445123), BluelinkDates.parseEpoch("1710527445123"))
        assertNull(BluelinkDates.parseEpoch("12345"))
        assertNull(BluelinkDates.parseEpoch("abc"))
    }

    @Test
    fun `format helpers round-trip`() {
        val instant = Instant.parse("2024-03-15T18:30:45Z")
        assertEquals("20240315183045", BluelinkDates.formatBasic14(instant, ZoneOffset.UTC))
        assertEquals("20240315", BluelinkDates.formatDay(LocalDate.of(2024, 3, 15)))
    }
}
